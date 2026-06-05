package com.caiostoduto.loginPhaseProxy.intercept;

import com.caiostoduto.loginPhaseProxy.utils.ProxyLoginSession;
import com.velocitypowered.proxy.protocol.packet.*;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.util.UUID;

import static com.caiostoduto.loginPhaseProxy.Constants.logger;
import static com.caiostoduto.loginPhaseProxy.protocol.LoginPluginOwnership.isAmbassadorOwned;
import static com.caiostoduto.loginPhaseProxy.protocol.LoginPluginOwnership.isVelocityModernForwarding;

public class BackendInterceptor extends ChannelDuplexHandler {

    private ProxyLoginSession session;
    private ChannelHandlerContext ctx;

    // -------------------------------------------------------------------------
    // Handler lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    // -------------------------------------------------------------------------
    // [S -> V]  inbound (backend server → Velocity)
    // -------------------------------------------------------------------------

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        logger.debug("[B][S->V] {}", msg);

        switch (msg) {
            case LoginPluginMessagePacket packet -> {
                // Velocity modern player information forwarding
                if (isVelocityModernForwarding(packet)) {
                    logger.debug("[B][S->V][pass] LoginPluginMessage id={} channel={} owner=velocity",
                            packet.getId(), packet.getChannel());
                    break; // Continue packet in pipeline
                }

                // Forge handshake [1.13, 1.20.1] (let ambassador handle)
                if (isAmbassadorOwned(packet)) {
                    logger.debug("[B][S->V][pass] LoginPluginMessage id={} channel={} owner=ambassador",
                            packet.getId(), packet.getChannel());
                    break; // Continue packet in pipeline
                }

                if (session == null || session.frontendBridge == null) {
                    logger.warn("[B][S->V][pass] LoginPluginMessage id={} channel={} reason=no-frontend-session",
                            packet.getId(), packet.getChannel());
                    break; // Continue packet in pipeline
                }

                // Send to the player via the frontend bridge.
                session.trackLoginPluginMessage(packet);
                logger.debug("[B][S->F][relay] LoginPluginMessage id={} channel={}",
                        packet.getId(), packet.getChannel());
                session.frontendBridge.writeLoginPluginMessage(packet);
                ReferenceCountUtil.release(msg); // Release the original
                return; // Drop packet
            }
            case ServerLoginSuccessPacket ignored -> {
                completeBackendLogin(ctx);
                break; // Continue packet in pipeline
            }
            default -> {}
        }

        super.channelRead(ctx, msg);
    }

    // -------------------------------------------------------------------------
    // [V -> S]  outbound (Velocity → backend server)
    // -------------------------------------------------------------------------

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        logger.debug("[B][V->S] {}", msg);

        switch (msg) {
            case ServerLoginPacket packet -> {
                // Velocity is sending the login handshake to the backend — link the session now
                linkProxyLoginSession(packet);
            }
            default -> {}
        }

        super.write(ctx, msg, promise);
    }

    // -------------------------------------------------------------------------
    // Public API used by FrontendInterceptor
    // -------------------------------------------------------------------------

    /**
     * Writes a packet directly to the backend channel.
     * Uses ctx.writeAndFlush so the full outbound pipeline (encryption, etc.) is applied.
     */
    public void writeLoginPluginResponse(LoginPluginResponsePacket packet) {
        logger.debug("[F->B][relay] LoginPluginResponse id={} success={}",
                packet.getId(), packet.isSuccess());

        LoginPluginResponsePacket copiedPacket = new LoginPluginResponsePacket(
                packet.getId(),
                packet.isSuccess(),
                packet.content().copy()
        );

        ctx.pipeline().writeAndFlush(copiedPacket).addListener(future -> {
            if (!future.isSuccess()) {
                ReferenceCountUtil.release(copiedPacket);
                logger.warn("[B][F->S][fail] LoginPluginResponse id={} reason=write-failed",
                        copiedPacket.getId(), future.cause());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Continue the flow by flushing the buffer from FrontendInterceptor and removing this from the pipeline.
     */
    private void completeBackendLogin(ChannelHandlerContext ctx) {
        ProxyLoginSession currentSession = session;

        if (currentSession == null || currentSession.frontendBridge == null) {
            logger.warn("[B][S->V][complete] backend login complete, but frontend session is missing; cannot flush");
        } else {
            if (currentSession.hasOutstandingLoginPluginMessageIds()) {
                logger.warn("[B][S->V][complete] backend login complete with unanswered LoginPluginMessage ids {}; flushing anyway",
                        currentSession.outstandingLoginPluginMessageIds());
            }
            currentSession.frontendBridge.backendLoginComplete();
        }

        ctx.executor().execute(() -> {
            if (ctx.pipeline().context(this) != null) {
                ctx.pipeline().remove(this);
            }
            ctx.flush();

            if (currentSession != null) {
                currentSession.unlink();
            }
        });
    }

    /**
     * Looks up the session created by FrontendInterceptor and links this backend to it.
     */
    private void linkProxyLoginSession(ServerLoginPacket loginPacket) {
        UUID playerUUID = loginPacket.getHolderUuid();
        session = ProxyLoginSession.link(playerUUID, this);

        if (session == null) {
            logger.warn("[B][V->S][link] no frontend session found for uuid={}", playerUUID);
        } else {
            logger.debug("[B][V->S][link] linked backend session uuid={}", playerUUID);
        }
    }
}
