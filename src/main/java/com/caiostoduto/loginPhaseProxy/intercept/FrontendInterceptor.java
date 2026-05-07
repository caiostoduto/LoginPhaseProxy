package com.caiostoduto.loginPhaseProxy.intercept;

import com.caiostoduto.loginPhaseProxy.utils.LoginPluginPacketCopies;
import com.caiostoduto.loginPhaseProxy.utils.ProxyLoginSession;
import com.caiostoduto.loginPhaseProxy.utils.StealthPipeline;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.*;
import com.velocitypowered.proxy.protocol.packet.*;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.util.*;

import static com.caiostoduto.loginPhaseProxy.Constants.logger;

public class FrontendInterceptor extends ChannelDuplexHandler   {

    private static final String TEMP_FRAME_ENCODER = "frame-encoder";

    private final Queue<PendingWrite> pendingWrites = new ArrayDeque<>();
    private final StealthPipeline stealthPipeline = new StealthPipeline();

    private UUID playerUUID;
    protected ProtocolVersion clientProtocolVersion;
    private ProxyLoginSession session;
    private ChannelHandlerContext ctx;

    private boolean waitingLoginAcknowledgedPacket;
    private boolean pipelinePrepared;

    private record PendingWrite(Object message, ChannelPromise promise) {}

    // -------------------------------------------------------------------------
    // Handler lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.handlerRemoved(ctx);
    }

    // -------------------------------------------------------------------------
    // [C -> V]  inbound (client → Velocity)
    // -------------------------------------------------------------------------

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        logger.debug("[F][C->V] {}", msg.getClass().getName());

        switch (msg) {
            case HandshakePacket handshakePacket -> {
                // Get the client's protocol version for encoding messages later
                this.clientProtocolVersion = handshakePacket.getProtocolVersion();
            }
            case LoginPluginResponsePacket loginPluginResponsePacket -> {
                if (waitingLoginAcknowledgedPacket) {
                    // Send packet (Probably Ambassador)
                    break;
                }

                if (session == null || !session.isBackendLinked() || session.backendInterceptor == null) {
                    logger.warn("[F] received LoginPluginResponse without linked backend; dropping id {}",
                            loginPluginResponsePacket.getId());
                    ReferenceCountUtil.release(msg);
                    // Drop packet
                    return;
                }

                if (!session.consumeLoginPluginResponse(loginPluginResponsePacket)) {
                    logger.warn("[F] received unexpected LoginPluginResponse id {}; dropping",
                            loginPluginResponsePacket.getId());
                    ReferenceCountUtil.release(msg);
                    // Drop packet
                    return;
                }

                // Send to the Backend Server via BackendInterceptor
                session.backendInterceptor.writeCopiedPacket((LoginPluginResponsePacket) msg);
                // Release original message
                ReferenceCountUtil.release(msg);

                // Drop packet
                return;
            }
            case LoginAcknowledgedPacket ignored -> {
                if (!waitingLoginAcknowledgedPacket) {
                    // Send packet
                    break;
                }

                // Finish the setup (undo changes and flush buffer)
                finishFrontendLogin(ctx);
                // Release original message
                ReferenceCountUtil.release(msg);

                // Drop packet, we already sent a synthetic LoginAcknowledgedPacket
                return;
            }
            default -> {}
        }

        super.channelRead(ctx, msg);
    }

    // -------------------------------------------------------------------------
    // [V -> C]  outbound (Velocity → client)
    // -------------------------------------------------------------------------

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        logger.debug("[F][V->C] {} ({})", msg.getClass().getName(), ctx.pipeline().names());

        switch (msg) {
            case SetCompressionPacket ignored -> {
                if (waitingLoginAcknowledgedPacket) {
                    // Send packet
                    break;
                }

                // Add packet to buffer
                pendingWrites.add(new PendingWrite(msg, promise));
                // Drop packet
                return;
            }
            case ServerLoginSuccessPacket serverLoginSuccessPacket -> {
                if (waitingLoginAcknowledgedPacket) {
                    // Send packet
                    break;
                }

                // Add packet to buffer
                pendingWrites.add(new PendingWrite(msg, promise));
                // Create entry session to establish connection with BackendInterceptor later
                createEntryProxyLoginSession(serverLoginSuccessPacket);
                // Prepare pipeline removing unnecessary handlers and changing MinecraftConnection.state to LOGIN
                preparePipeline(ctx);
                // Drop packet
                return;
            }
            // Send packet (Probably Ambassador)
            case LoginPluginMessagePacket loginPluginMessagePacket -> {}
            default -> {
                if (waitingLoginAcknowledgedPacket) {
                    // Add packet to buffer
                    pendingWrites.add(new PendingWrite(msg, promise));
                    // Drop packet
                    return;
                }
            }
        }

        super.write(ctx, msg, promise);
    }

    // -------------------------------------------------------------------------
    // Public API used by BackendInterceptor
    // -------------------------------------------------------------------------

    /**
     * Flushes all buffered packets to the client channel in the correct order and post-handshake logic
     */
    protected void flushBuffer() {
        logger.debug("[F][V] flushBuffer");

        if (ctx == null || !ctx.channel().isActive()) {
            writePendingPackets();
            return;
        }

        ctx.executor().execute(() -> {
            if (clientProtocolVersion != null && clientProtocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
                removeSelf();
                // REVIEW: Apparently no need for minecraftConnection.setState(StateRegistry.CONFIG);
            } else {
                waitingLoginAcknowledgedPacket = true;
            }

            writePendingPackets();
            ctx.flush();
        });
    }

    /**
     * Writes a packet directly to the client channel.
     * Uses ctx.writeAndFlush so the full outbound pipeline (encryption, etc.) is applied.
     */
    protected void writeCopiedPacket(LoginPluginMessagePacket packet) {
        logger.debug("[B->F][write] {}", packet);
        LoginPluginMessagePacket copiedPacket = LoginPluginPacketCopies.copy(packet);

        ctx.pipeline().writeAndFlush(copiedPacket).addListener(future -> {
            if (!future.isSuccess()) {
                ReferenceCountUtil.release(copiedPacket);
                logger.warn("[F] failed to write LoginPluginMessagePacket to player", future.cause());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Cleans up session state and releases any buffered packets.
     * <p>
     * Safe to call multiple times (playerUUID null-check guards re-entrancy).
     */
    private void cleanup() {
        if (playerUUID != null && session != null) {
            session.close();
            playerUUID = null;
        }

        writePendingPackets();
    }

    /**
     * Create a new entry into the ProxyLoginSession.sessions based on the playerUUID to establish connection with
     *  BackendInterceptor later.
     */
    private void createEntryProxyLoginSession(ServerLoginSuccessPacket serverLoginSuccessPacket) {
        playerUUID = serverLoginSuccessPacket.getUuid();
        session = ProxyLoginSession.open(playerUUID, this);
    }

    /**
     * In case of clientProtocolVersion >= ProtocolVersion.MINECRAFT_1_20_2, this method should be executed so that
     *  it restores the MinecraftConnection.state to the correct one. Also, it removes this handler from the pipeline
     *  and flushes all buffered packets.
     */
    private void finishFrontendLogin(ChannelHandlerContext ctx) {
        waitingLoginAcknowledgedPacket = false;

        ctx.executor().execute(() -> {
            MinecraftConnection minecraftConnection = ctx.pipeline().get(MinecraftConnection.class);
            if (minecraftConnection != null) {
                minecraftConnection.setState(StateRegistry.CONFIG);
            }

            removeSelf();
            writePendingPackets();
            ctx.flush();
        });
    }
    /**
     * Prepare pipeline removing unnecessary handlers and changing MinecraftConnection.state to LOGIN.
     */
    private void preparePipeline(ChannelHandlerContext ctx) {
        if (pipelinePrepared) {
            return;
        }
        pipelinePrepared = true;

        ctx.executor().execute(() -> {
            ctx.fireChannelRead(new LoginAcknowledgedPacket());

            stealthPipeline.removeIfPresent(ctx, MinecraftCompressorAndLengthEncoder.class);
            stealthPipeline.removeIfPresent(ctx, MinecraftCompressDecoder.class);

            if (ctx.pipeline().get(MinecraftVarintLengthEncoder.class) == null) {
                ChannelHandlerContext decoder = ctx.pipeline().context(MinecraftDecoder.class);
                if (decoder != null) {
                    ctx.pipeline().addBefore(decoder.name(), TEMP_FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE);
                }
            }

            MinecraftConnection minecraftConnection = ctx.pipeline().get(MinecraftConnection.class);
            if (minecraftConnection != null) {
                minecraftConnection.setState(StateRegistry.LOGIN);
            }
        });
    }

    /**
     * Removes this handler from the pipeline.
     */
    private void removeSelf() {
        if (ctx.pipeline().context(this) != null) {
            ctx.pipeline().remove(this);
        }
    }

    /**
     * Restore any temporarily removed handlers to their original positions in the pipeline and remove
     *  MinecraftVarintLengthEncoder that was artificially added to the pipeline.
     */
    private void restorePipeline() {
        // Remove MinecraftVarintLengthEncoder
        ctx.pipeline().remove(MinecraftVarintLengthEncoder.class);

        // Restore any temporarily removed handlers to their original positions in the pipeline
        stealthPipeline.restoreHandlers(ctx);
    }

    /**
     * Flushes all buffered packets to the client channel in the correct order.
     */
    private void writePendingPackets() {
        PendingWrite pendingWrite;
        while ((pendingWrite = pendingWrites.poll()) != null) {
            ctx.write(pendingWrite.message(), pendingWrite.promise());
            if (pendingWrite.message() instanceof SetCompressionPacket) {
                ctx.flush();
                restorePipeline();
            }
        }
    }
}
