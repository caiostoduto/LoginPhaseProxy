package com.caiostoduto.loginPhaseProxy.intercept;

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
                session.frontendInterceptor.writeRawPacket(loginPluginMessagePacket);
                return;
            }
            case SetCompressionPacket setCompressionPacket -> {
                ctx.executor().execute(() -> {
                    // Removes this interceptor from the pipeline on the event loop thread.
                    ctx.pipeline().remove(this);
                    // Flushes any pending writes first to avoid dropping in-flight packets.
                    ctx.flush();

                    session.unlink();
                });

                // Backend login finished → tear down both interceptors
                if (session != null) {
                    if (session.frontendInterceptor != null) {
                        // Flushes all buffered packets to the client channel in the correct order, then clears the buffer.
                        session.frontendInterceptor.flushBuffer();
                    }
                } else {
                    logger.warn("[B] ServerLoginSuccess arrived but frontend is null — cannot flush");
                }
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
            case ServerLoginPacket serverLoginPacket -> {
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
    public void write(LoginPluginResponsePacket packet) {
        logger.debug("[B][write] {}", packet);

        ctx.pipeline().writeAndFlush(packet).addListener(future -> {
            if (!future.isSuccess()) {
                ReferenceCountUtil.release(packet);
                logger.warn("[B] failed to write LoginPluginResponsePacket to backend", future.cause());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Looks up the session created by FrontendInterceptor and links this backend to it.
     * If the session is not found (frontend hasn't registered yet), logs a warning.
     * The caller must handle the null case — session will remain null and subsequent
     * null-guards in channelRead will prevent NPEs.
     */
    private void linkProxyLoginSession(ServerLoginPacket loginPacket) {
        UUID playerUUID = loginPacket.getHolderUuid();
        session = ProxyLoginSession.link(playerUUID, this);

        if (session == null) {
            logger.warn("[B] no session found for {}, frotnend may have not registered yet", playerUUID);
        }
    }
}