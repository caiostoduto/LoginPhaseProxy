package com.caiostoduto.loginPhaseProxy.intercept;

import com.caiostoduto.loginPhaseProxy.utils.StealthPipeline;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftCompressDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftCompressorAndLengthEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import com.velocitypowered.proxy.protocol.packet.LoginAcknowledgedPacket;
import com.velocitypowered.proxy.protocol.packet.SetCompressionPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;

import static com.caiostoduto.loginPhaseProxy.Constants.logger;

final class FrontendLoginGate {
    private final Queue<PendingWrite> pendingWrites = new ArrayDeque<>();
    private final StealthPipeline stealthPipeline = new StealthPipeline();
    private final FrontendInterceptor owner;

    private ProtocolVersion clientProtocolVersion;
    private ChannelHandlerContext ctx;
    private boolean pipelinePrepared;
    private boolean skipCleanupFlush;

    private record PendingWrite(Object message, ChannelPromise promise) {}

    private enum LoginPhase {
        EARLY_LOGIN,
        WAITING_ACK,
        ACKNOWLEDGED
    }

    LoginPhase currentPhase = LoginPhase.EARLY_LOGIN;

    FrontendLoginGate(FrontendInterceptor owner) {
        this.owner = owner;
    }

    void attach(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    void setClientProtocolVersion(ProtocolVersion clientProtocolVersion) {
        this.clientProtocolVersion = clientProtocolVersion;
    }

    ProtocolVersion getClientProtocolVersion() {
        return clientProtocolVersion;
    }

    boolean waitingLoginAcknowledgedPacket() {
        return currentPhase == LoginPhase.WAITING_ACK;
    }

    boolean clientAcknowledged() { return currentPhase == LoginPhase.ACKNOWLEDGED; }

    void buffer(Object msg, ChannelPromise promise) {
        pendingWrites.add(new PendingWrite(msg, promise));
    }

    void flushAfterBackendLogin() {
        if (ctx == null) {
            discardPendingPackets(new IllegalStateException("Frontend channel context is not attached"));
            return;
        }

        logger.debug("[F][V->C][flush] pending={}", pendingWrites.size());

        if (!ctx.channel().isActive()) {
            discardPendingPackets(new ClosedChannelException());
            return;
        }

        if (getClientProtocolVersion() != null && getClientProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
            // Remove FrontendInterceptor from pipeline
            removeOwner();
        } else {
            currentPhase = LoginPhase.WAITING_ACK;
        }

        ctx.executor().execute(() -> {
            writePendingPackets();
            ctx.flush();
        });
    }

    void finishFrontendLogin(ChannelHandlerContext readCtx) {
        currentPhase = LoginPhase.ACKNOWLEDGED;

        // Flip decoder state SYNCHRONOUSLY so the next packet in this same read
        // burst decodes under CONFIG instead of LOGIN.
        MinecraftConnection mc = readCtx.pipeline().get(MinecraftConnection.class);
        if (mc != null && mc.getState() == StateRegistry.LOGIN) {
            mc.setState(StateRegistry.CONFIG);
            logger.debug("[F][pipeline] restored MinecraftConnection state=CONFIG");
        }

        // Remove FrontendInterceptor from pipeline
        removeOwner();

        // Defer only the pipeline surgery + buffer flush (the not channel-safe part).
        readCtx.executor().execute(() -> {
            writePendingPackets();
            readCtx.flush();
        });
    }

    void preparePipeline(ChannelHandlerContext writeCtx) {
        if (pipelinePrepared) {
            return;
        }
        pipelinePrepared = true;

        writeCtx.executor().execute(() -> {
            writeCtx.fireChannelRead(new LoginAcknowledgedPacket());

            stealthPipeline.removeIfPresent(writeCtx, MinecraftCompressorAndLengthEncoder.class);
            stealthPipeline.removeIfPresent(writeCtx, MinecraftCompressDecoder.class);

            if (writeCtx.pipeline().get(MinecraftVarintLengthEncoder.class) == null) {
                ChannelHandlerContext decoder = writeCtx.pipeline().context(MinecraftDecoder.class);
                if (decoder != null) {
                    writeCtx.pipeline().addBefore(decoder.name(), Connections.FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE);
                }
            }

            MinecraftConnection minecraftConnection = writeCtx.pipeline().get(MinecraftConnection.class);
            if (minecraftConnection != null) {
                minecraftConnection.setState(StateRegistry.LOGIN);
            }
        });
    }

    void cleanup() {
        writePendingPackets();
    }

    private void removeOwner() {
        if (ctx != null && ctx.pipeline().context(owner) != null) {
            ctx.pipeline().remove(owner);
        }
    }

    private void restorePipeline() {
        if (ctx.pipeline().get(MinecraftVarintLengthEncoder.class) != null) {
            ctx.pipeline().remove(MinecraftVarintLengthEncoder.class);
        }

        stealthPipeline.restoreHandlers(ctx);
    }

    private void writePendingPackets() {
        if (ctx == null) {
            discardPendingPackets(new IllegalStateException("Frontend channel context is not attached"));
            return;
        }

        if (!ctx.channel().isActive()) {
            discardPendingPackets(new ClosedChannelException());
            return;
        }

        PendingWrite pendingWrite;
        while ((pendingWrite = pendingWrites.poll()) != null) {
            ctx.write(pendingWrite.message(), pendingWrite.promise());
            if (pendingWrite.message() instanceof SetCompressionPacket) {
                ctx.flush();
                restorePipeline();
            }
        }
    }

    private void discardPendingPackets(Throwable cause) {
        PendingWrite pendingWrite;
        while ((pendingWrite = pendingWrites.poll()) != null) {
            ReferenceCountUtil.release(pendingWrite.message());
            pendingWrite.promise().tryFailure(cause);
        }
    }
}
