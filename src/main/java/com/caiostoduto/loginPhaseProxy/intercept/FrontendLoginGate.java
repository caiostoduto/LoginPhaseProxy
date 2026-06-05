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

import java.util.ArrayDeque;
import java.util.Queue;

import static com.caiostoduto.loginPhaseProxy.Constants.logger;

final class FrontendLoginGate {
    private final Queue<PendingWrite> pendingWrites = new ArrayDeque<>();
    private final StealthPipeline stealthPipeline = new StealthPipeline();
    private final FrontendInterceptor owner;

    private ProtocolVersion clientProtocolVersion;
    private ChannelHandlerContext ctx;
    private boolean waitingLoginAcknowledgedPacket;
    private boolean pipelinePrepared;

    private record PendingWrite(Object message, ChannelPromise promise) {}

    FrontendLoginGate(FrontendInterceptor owner) {
        this.owner = owner;
    }

    void attach(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    void setClientProtocolVersion(ProtocolVersion clientProtocolVersion) {
        this.clientProtocolVersion = clientProtocolVersion;
    }

    boolean waitingLoginAcknowledgedPacket() {
        return waitingLoginAcknowledgedPacket;
    }

    void buffer(Object msg, ChannelPromise promise) {
        pendingWrites.add(new PendingWrite(msg, promise));
    }

    void flushAfterBackendLogin() {
        logger.debug("[F][V->C][flush] pending={}", pendingWrites.size());

        if (ctx == null || !ctx.channel().isActive()) {
            writePendingPackets();
            return;
        }

        ctx.executor().execute(() -> {
            if (clientProtocolVersion != null && clientProtocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
                removeOwner();
            } else {
                waitingLoginAcknowledgedPacket = true;
            }

            writePendingPackets();
            ctx.flush();
        });
    }

    private volatile boolean clientAcknowledged;

    boolean clientAcknowledged() { return clientAcknowledged; }

    ProtocolVersion clientProtocolVersionOrDefault() {
        return clientProtocolVersion != null
                ? clientProtocolVersion
                : ProtocolVersion.MINECRAFT_1_20_2;
    }

    void finishFrontendLogin(ChannelHandlerContext readCtx) {
        waitingLoginAcknowledgedPacket = false;
        clientAcknowledged = true;   // only addition for the readability fix

        readCtx.executor().execute(() -> {
            MinecraftConnection minecraftConnection = readCtx.pipeline().get(MinecraftConnection.class);
            if (minecraftConnection != null) {
                minecraftConnection.setState(StateRegistry.CONFIG);
                logger.debug("[F][pipeline] restored MinecraftConnection state=CONFIG");
            }
            
            removeOwner();
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
}
