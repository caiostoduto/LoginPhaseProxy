package com.caiostoduto.loginPhaseProxy;

import com.caiostoduto.loginPhaseProxy.initializer.VelocityChannelInitializer;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import static com.caiostoduto.loginPhaseProxy.Constants.logger;

@Plugin(
        id = BuildConstants.ID,
        name = "LoginPhaseProxy",
        version = BuildConstants.VERSION,
        description = BuildConstants.DESCRIPTION,
        url = "https://github.com/caiostoduto/LoginPhaseProxy",
        authors = {"Caio Stoduto"})
public class LoginPhaseProxy {

    private final ProxyServer proxy;

    @Inject
    public LoginPhaseProxy(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        Constants.logger = logger;

//        Configurator.setLevel("loginphaseproxy", Level.DEBUG);
        logger.debug("Logger level set to DEBUG");

        logger.info("Plugin initialized.");
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Injecting Velocity proxy");
        VelocityChannelInitializer.inject(this.proxy);
    }
}
