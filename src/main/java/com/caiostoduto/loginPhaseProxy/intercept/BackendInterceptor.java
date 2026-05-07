package com.caiostoduto.loginPhaseProxy.intercept;

import com.caiostoduto.loginPhaseProxy.utils.LoginPluginPacketCopies;
import com.caiostoduto.loginPhaseProxy.utils.ProxyLoginSession;
import com.velocitypowered.proxy.connection.PlayerDataForwarding;
import com.velocitypowered.proxy.protocol.packet.*;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.util.UUID;

import static com.caiostoduto.loginPhaseProxy.Constants.logger;

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

    // -------------------------------------------------------------------------
    // [S -> V]  inbound (backend server → Velocity)
    // -------------------------------------------------------------------------

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        logger.debug("[B][S->V] {}", msg.getClass().getName());

        switch (msg) {
            case LoginPluginMessagePacket loginPluginMessagePacket -> {
                if (loginPluginMessagePacket.getChannel().equals(PlayerDataForwarding.CHANNEL))
                    break;

                // Relay LoginPluginMessage packets to the client via the frontendInterceptor
                if (session == null || session.frontendInterceptor == null) {
                    logger.warn("[B] received LoginPluginMessage but session/frontend is null — dropping");
                    ReferenceCountUtil.release(msg);
                    return;
                }

                // Send to the player via frontendInterceptor
                session.trackLoginPluginMessage(loginPluginMessagePacket);
                session.frontendInterceptor.writeCopiedPacket(loginPluginMessagePacket);
                ReferenceCountUtil.release(msg); // Release the original
                return;
            }
            case ServerLoginSuccessPacket ignored -> {
                completeBackendLogin(ctx);
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
        logger.debug("[B][V->S] {}", msg.getClass().getName());

        switch (msg) {
            case ServerLoginPacket ignored -> {
                // Velocity is sending the login handshake to the backend — link the session now
                linkProxyLoginSession((ServerLoginPacket) msg);
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
    protected void writeCopiedPacket(LoginPluginResponsePacket packet) {
        logger.debug("[F->B][write] {}", packet);
        LoginPluginResponsePacket copiedPacket = LoginPluginPacketCopies.copy(packet);

        ctx.pipeline().writeAndFlush(copiedPacket).addListener(future -> {
            if (!future.isSuccess()) {
                ReferenceCountUtil.release(copiedPacket);
                logger.warn("[B] failed to write LoginPluginResponsePacket to backend", future.cause());
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

        if (currentSession == null || currentSession.frontendInterceptor == null) {
            logger.warn("[B] backend login completed but frontend session is missing; cannot flush");
        } else {
            if (currentSession.hasOutstandingLoginPluginMessageIds()) {
                logger.warn("[B] backend login completed with unanswered LoginPluginMessage ids; flushing anyway");
            }
            currentSession.frontendInterceptor.flushBuffer();
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
            logger.warn("[B] no session found for {}, frotnend may have not registered yet", playerUUID);
        }
    }
}