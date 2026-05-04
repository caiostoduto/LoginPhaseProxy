package com.caiostoduto.loginPhaseProxy.intercept;

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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

import static com.caiostoduto.loginPhaseProxy.Constants.logger;

public class FrontendInterceptor extends ChannelDuplexHandler   {

    private final Queue<Object> queue = new ConcurrentLinkedQueue<>();
    private final RemovedPipelineHandlers removedPipelineHandlers = new RemovedPipelineHandlers();

    private UUID playerUUID;
    protected ProtocolVersion clientProtocolVersion;
    private ProxyLoginSession session;
    private ChannelHandlerContext ctx;

    private boolean WaitingLoginAcknowledgedPacket = false;

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
                // If client sent in wrong order Velocity will handle it automatically
            }
            case LoginPluginResponsePacket loginPluginResponsePacket -> {
                // Unrelated to this plugin flow (probably fml:loginwrapper)
                if (WaitingLoginAcknowledgedPacket) {
                    break;
                }

                // Relay LoginPluginResponse to the backend
                // REVIEW: What if out of order?

                // (session != null && session.frontendInterceptor) after [F][V->C] serverLoginSuccessPacket arrives
                // session.backendInterceptor != null after [B][S->V] setCompressionPacket arrives
                if (session == null || session.frontendInterceptor == null || session.backendInterceptor == null) {
                    // REVIEW: Shouldn't happen, but if it does, drop the packet
                    logger.warn("[F] received LoginPluginResponse but session/backend is null — dropping");
                    ReferenceCountUtil.release(msg);
                    return;
                }

                // Clone the packet so each pipeline gets its own buffer ref-count
                LoginPluginResponsePacket clone = new LoginPluginResponsePacket(
                        loginPluginResponsePacket.getId(),
                        loginPluginResponsePacket.isSuccess(),
                        loginPluginResponsePacket.content().copy()
                );

                // Release the original
                ReferenceCountUtil.release(msg);

                logger.debug("[F][F->B] ", clone);
                session.backendInterceptor.write(clone);

                // Drop packet
                return;
            }
            case LoginAcknowledgedPacket loginAcknowledgedPacket -> {
                // Should be received after [B][S->V] ServerLoginPacket => [F][V->C] LoginPluginMessagePacket
                // This means that all work needed is done, so remove FrontendInterceptor from pipeline
                // REVIEW: What if out of order?

                // (session != null && session.frontendInterceptor) after [F][V->C] serverLoginSuccessPacket arrives
                // session.frontendInterceptor != null after [B][V->S] ServerLoginPacket arrives
                // session.backendInterceptor == null before [B][V->S] ServerLoginPacket arrives ||
                //  after [B][S->V] ServerLoginSuccessPacket arrives
                if (session == null || session.frontendInterceptor == null || session.backendInterceptor != null) {
                    // REVIEW: Shouldn't happen, but if it does, drop the packet
                    logger.warn("[F] received LoginPluginResponse but session/backend is null — dropping");
                    ReferenceCountUtil.release(msg);
                    return;
                }

                // Set MinecraftConnection.state to StateRegistry.CONFIG
                MinecraftConnection MinecraftConnection = ctx.pipeline().get(MinecraftConnection.class);
                MinecraftConnection.setState(StateRegistry.CONFIG);

                // Send all buffered packets to the player
                Object packet;
                while ((packet = queue.poll()) != null) {
                    ctx.write(packet);
                }
                ctx.flush();

                // Remove FrontendInterceptor from pipeline
                ctx.pipeline().remove(this);

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
            case SetCompressionPacket setCompressionPacket -> {
                if (WaitingLoginAcknowledgedPacket) {
                    break;
                }

                // Add setCompressionPacket to buffer
                queue.add(setCompressionPacket);
                return; // Drop packet
            }
            case ServerLoginSuccessPacket serverLoginSuccessPacket -> {
                if (WaitingLoginAcknowledgedPacket) {
                    break;
                }

                // Add setCompressionPacket to buffer
                queue.add(serverLoginSuccessPacket);

                createEntryProxyLoginSession(serverLoginSuccessPacket);

                ctx.executor().execute(() -> {
                    // Gaslight Velocity: pretend the client already sent LoginAcknowledged
                    ctx.fireChannelRead(new LoginAcknowledgedPacket());

                    List<String> handlerNames = ctx.pipeline().names();

                    // Remove temporarily some handlers so that the pipeline is identical to the LoginPhase
                    //  before setCompression
                    ctx.pipeline().toMap().forEach((name, handler) -> {
                        int handlerIndex = handlerNames.indexOf(name);
                        switch (handler) {
                            // Remove temporarily MinecraftCompressorAndLengthEncoder and MinecraftCompressDecoder if exists
                            case MinecraftCompressorAndLengthEncoder compressEncoder -> {
                                ChannelHandlerContext handlerContext = StealthPipeline.stealthRemove(ctx.pipeline(), name);
                                removedPipelineHandlers.put(handlerIndex, handlerContext);
                            }
                            case MinecraftCompressDecoder compressDecoder -> {
                                ChannelHandlerContext handlerContext = StealthPipeline.stealthRemove(ctx.pipeline(), name);
                                removedPipelineHandlers.put(handlerIndex, handlerContext);
                            }
                            // Add MinecraftVarintLengthEncoder before MinecraftDecoder
                            case MinecraftDecoder minecraftDecoder -> {
                                ctx.pipeline().addBefore(name, "frame-encoder", MinecraftVarintLengthEncoder.INSTANCE);
                            }
                            // Set MinecraftConnection.state to StateRegistry.LOGIN so it can decode
                            //  LoginPluginMessagePackets needed for the login phase
                            case MinecraftConnection minecraftConnection -> {
                                minecraftConnection.setState(StateRegistry.LOGIN);
                            }
                            default -> {}
                        }
                    });
                });

                return;
            }
            case LoginPluginMessagePacket loginPluginMessagePacket -> {
                // Unrelated to this plugin flow (probably fml:loginwrapper)
                break;
            }
            default -> {
                if (WaitingLoginAcknowledgedPacket) {
                    queue.add(msg);
                    return;
                }
            }
        }

        logger.debug("sent");

        super.write(ctx, msg, promise);
    }

    // -------------------------------------------------------------------------
    // Public API used by BackendInterceptor
    // -------------------------------------------------------------------------

    /**
     * Writes a packet directly to the client channel.
     * Uses ctx.writeAndFlush so the full outbound pipeline (encryption, etc.) is applied.
     */
    public void writeRawPacket(LoginPluginMessagePacket packet) {
        logger.debug("[F][writeRawPacket] {}", packet);

        ctx.executor().execute(() -> {
            ctx.pipeline().writeAndFlush(packet);
        });
    }

    /**
     * Flushes all buffered packets to the client channel in the correct order, then clears the buffer.
     */
    protected void flushBuffer() {
        logger.debug("[F][V] flushBuffer");

        // REVIEW: shouldn't happen
        if (this.ctx == null || !this.ctx.channel().isActive()) {
            return;
        }

        ctx.executor().execute(() -> {
            if (this.clientProtocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
                ctx.pipeline().remove(this); // No LoginAcknowledgedPacket
            } else {
                this.WaitingLoginAcknowledgedPacket = true;
            }

            // Send all buffered packets to the player
            Object packet;
            while ((packet = queue.poll()) != null) {
                ctx.pipeline().write(packet);

                if (packet instanceof SetCompressionPacket) {
                    ctx.pipeline().flush();

                    // Restore any temporarily removed handlers to their original positions in the pipeline
                    // Remove MinecraftVarintLengthEncoder
                    restorePipeline();
                }
            }

            ctx.pipeline().flush();
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void createEntryProxyLoginSession(ServerLoginSuccessPacket serverLoginSuccessPacket) {
        playerUUID = serverLoginSuccessPacket.getUuid();
        session = ProxyLoginSession.open(playerUUID, this);
    }

    /**
     * Restore any temporarily removed handlers to their original positions in the pipeline
     * Remove MinecraftVarintLengthEncoder
     */
    private void restorePipeline() {
        // Restore any temporarily removed handlers to their original positions in the pipeline
        removedPipelineHandlers.forEach((index, handlerContext) -> {
            StealthPipeline.stealthRestoreAtIndex(ctx.pipeline(), handlerContext, index);
        });

        // Remove MinecraftVarintLengthEncoder
        ctx.pipeline().remove(MinecraftVarintLengthEncoder.class);
    }

    /**
     * Cleans up session state and releases any buffered packets.
     * Safe to call multiple times (playerUUID null-check guards re-entrancy).
     */
    private void cleanup() {
        if (playerUUID != null) {
            session.close(playerUUID);
            playerUUID = null;
        }

        // Release any ByteBufs still sitting in the buffer to avoid memory leaks
        Object packet;
        while ((packet = queue.poll()) != null) {
            ReferenceCountUtil.release(packet);
        }
    }
}

class RemovedPipelineHandlers {

    private final Map<Integer, ChannelHandlerContext> map = Collections.synchronizedMap(new LinkedHashMap<>());

    void put(int index, ChannelHandlerContext handler) {
        map.put(index, handler);
    }

    void forEach(BiConsumer<Integer, ChannelHandlerContext> consumer) {
        synchronized (map) {
            Iterator<Map.Entry<Integer, ChannelHandlerContext>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, ChannelHandlerContext> entry = iterator.next();
                consumer.accept(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
    }
}
