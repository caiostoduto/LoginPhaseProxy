package com.caiostoduto.loginPhaseProxy.intercept;

import com.caiostoduto.loginPhaseProxy.utils.LoginPluginPacketCopies;
import com.caiostoduto.loginPhaseProxy.utils.ProxyLoginSession;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.*;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.kyori.adventure.text.Component;
import org.adde0109.ambassador.forge.ForgeConstants;

import java.util.UUID;

import static com.caiostoduto.loginPhaseProxy.Constants.logger;

public class FrontendInterceptor extends ChannelDuplexHandler {

    private final FrontendLoginGate loginGate = new FrontendLoginGate(this);

    private UUID playerUUID;
    private ProxyLoginSession session;
    private ChannelHandlerContext ctx;

    // -------------------------------------------------------------------------
    // Handler lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        loginGate.attach(ctx);
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
            case HandshakePacket packet -> {
                // Get the client's protocol version for encoding messages later
                loginGate.setClientProtocolVersion(packet.getProtocolVersion());
            }
            case LoginPluginResponsePacket packet -> {
                if (loginGate.waitingLoginAcknowledgedPacket()) {
                    logger.debug("[F][C->V][pass] LoginPluginResponse id={} reason=waiting-login-acknowledged",
                            packet.getId());
                    break; // Continue packet in pipeline
                }

                if (session == null || !session.isBackendLinked() || session.backendBridge == null) {
                    logger.debug("[F][C->V][pass] LoginPluginResponse id={} reason=no-linked-backend",
                            packet.getId());
                    break; // Continue packet in pipeline
                }

                if (!session.consumeLoginPluginResponse(packet)) {
                    logger.debug("[F][C->V][pass] LoginPluginResponse id={} reason=untracked",
                            packet.getId());
                    break; // Continue packet in pipeline
                }

                // Send to the Backend Server via BackendInterceptor
                logger.debug("[F][C->B][relay] LoginPluginResponse id={} success={}",
                        packet.getId(), packet.isSuccess());
                session.backendBridge.writeLoginPluginResponse((LoginPluginResponsePacket) msg);
                ReferenceCountUtil.release(msg); // Release original message

                return; // Drop packet
            }
            case LoginAcknowledgedPacket ignored -> {
                if (!loginGate.waitingLoginAcknowledgedPacket()) {
                    logger.debug("[F][C->V][pass] LoginAcknowledgedPacket reason=no-waiting-login-acknowledged");
                    break; // REVIEW: Continue packet in pipeline
                }

                // Finish the setup (undo changes and flush buffer)
                loginGate.finishFrontendLogin(ctx);
                ReferenceCountUtil.release(msg); // Release original message

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
        logger.debug("[F][V->C] {} ({})", msg, ctx.pipeline().names());

        switch (msg) {
            case SetCompressionPacket ignored -> {
                if (loginGate.waitingLoginAcknowledgedPacket()) {
                    logger.debug("[F][C->V][pass] SetCompressionPacket reason=waiting-login-acknowledged");
                    break; // Continue packet in pipeline
                }

                if (isAmbassadorLoginOwner(ctx)) {
                    logger.debug("[F][V->C][pass] SetCompressionPacket owner=ambassador");
                    break; // Continue packet in pipeline
                }

                // Add packet to buffer
                logger.debug("[F][V->C][buffer] SetCompressionPacket");
                loginGate.buffer(msg, promise);
                return; // Drop packet
            }
            case ServerLoginSuccessPacket serverLoginSuccessPacket -> {
                if (loginGate.waitingLoginAcknowledgedPacket()) {
                    logger.debug("[F][C->V][pass] ServerLoginSuccessPacket reason=waiting-login-acknowledged");
                    break; // Continue packet in pipeline
                }

                createEntryProxyLoginSession(serverLoginSuccessPacket);

                if (isAmbassadorLoginOwner(ctx)) {
                    logger.debug("[F][V->C][pass] ServerLoginSuccessPacket uuid={} owner=ambassador",
                            serverLoginSuccessPacket.getUuid());
                    break; // Continue packet in pipeline
                }

                loginGate.preparePipeline(ctx);

                // Add packet to buffer
                logger.debug("[F][V->C][buffer] ServerLoginSuccessPacket uuid={}",
                        serverLoginSuccessPacket.getUuid());
                loginGate.buffer(msg, promise);
                return; // Drop packet
            }
            case LoginPluginMessagePacket ignored -> {
                // Send packet (Probably Ambassador)
            }
            case DisconnectPacket disconnect -> {
                // The client may already be in CONFIG when a LOGIN DisconnectPacket is sent.
                // Re-frame it as CONFIG to avoid 0x00 being interpreted as cookie_request.
                if (loginGate.clientAcknowledged()) {
                    MinecraftConnection mc = ctx.pipeline().get(MinecraftConnection.class);

                    if (mc != null && mc.getState() == StateRegistry.LOGIN) {
                        Component reason = disconnect.getReason().getComponent();
                        ProtocolVersion version = loginGate.clientProtocolVersionOrDefault();

                        // Encoder reads connection state to choose the packet id — flip first.
                        mc.setState(StateRegistry.CONFIG);

                        DisconnectPacket fixed = DisconnectPacket.create(reason, version, StateRegistry.CONFIG);

                        logger.debug("[F][V->C][fix] re-framed Disconnect LOGIN->CONFIG: {}", reason);
                        ReferenceCountUtil.release(msg);
                        ctx.write(fixed, promise);
                        return;
                    }
                }
                // Already correct phase (or client never acked) - pass through untouched.
            }
            default -> {
                if (loginGate.waitingLoginAcknowledgedPacket()) {
                    // Add packet to buffer
                    logger.debug("[F][V->C][buffer] {} reason=waiting-login-ack", msg.getClass().getSimpleName());
                    loginGate.buffer(msg, promise);
                    return; // Drop packet
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
    public void backendLoginComplete() {
        loginGate.flushAfterBackendLogin();
    }

    /**
     * Writes a packet directly to the client channel.
     * Uses ctx.writeAndFlush so the full outbound pipeline (encryption, etc.) is applied.
     */
    public void writeLoginPluginMessage(LoginPluginMessagePacket packet) {
        logger.debug("[B->F][relay] LoginPluginMessage id={} channel={}", packet.getId(), packet.getChannel());
        LoginPluginMessagePacket copiedPacket = LoginPluginPacketCopies.copy(packet);

        ctx.pipeline().writeAndFlush(copiedPacket).addListener(future -> {
            if (!future.isSuccess()) {
                ReferenceCountUtil.release(copiedPacket);
                logger.warn("[F][B->C][fail] LoginPluginMessage id={} channel={} reason=write-failed",
                        copiedPacket.getId(), copiedPacket.getChannel(), future.cause());
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

        loginGate.cleanup();
    }

    /**
     * Create a new entry into the ProxyLoginSession.sessions based on the playerUUID to establish connection with
     *  BackendInterceptor later.
     */
    private void createEntryProxyLoginSession(ServerLoginSuccessPacket serverLoginSuccessPacket) {
        playerUUID = serverLoginSuccessPacket.getUuid();
        session = ProxyLoginSession.open(playerUUID, this);
        logger.debug("[F][session] opened frontend session uuid={}", playerUUID);
    }

    private boolean isAmbassadorLoginOwner(ChannelHandlerContext ctx) {
        return ctx.pipeline().context(ForgeConstants.SERVER_SUCCESS_LISTENER) != null;
    }
}
