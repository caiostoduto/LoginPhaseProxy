package com.caiostoduto.loginPhaseProxy.initializer;

import com.caiostoduto.loginPhaseProxy.BuildConstants;
import com.caiostoduto.loginPhaseProxy.intercept.FrontendInterceptor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import com.velocitypowered.proxy.network.Connections;

import static com.caiostoduto.loginPhaseProxy.initializer.VelocityChannelInitializer.method;

public class FrontendChannelInitializer extends ChannelInitializer<Channel> {

    private final ChannelInitializer<Channel> initializer;

    public FrontendChannelInitializer(ChannelInitializer<Channel> initializer) {
        this.initializer = initializer;
    }

    @Override
    protected void initChannel(Channel ch) {
        try {
            method(initializer.getClass(), "initChannel", Channel.class).invoke(initializer, ch);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke initChannel", e);
        }

        // Add our handlers before any of Velocity's handlers, so we can sniff the connection before Velocity does
        //  and decide what to do with it.
        ch.pipeline().addBefore(Connections.HANDLER, BuildConstants.ID, new FrontendInterceptor());
    }
}
