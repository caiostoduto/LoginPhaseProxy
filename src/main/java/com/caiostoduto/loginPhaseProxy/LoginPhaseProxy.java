package com.caiostoduto.loginPhaseProxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import org.slf4j.Logger;

@Plugin(id = "loginphaseproxy", name = "LoginPhaseProxy", version = BuildConstants.VERSION, description = "abc", url = "https://github.com/caiostoduto/LoginPhaseProxy", authors = {"Caio Stoduto"})
public class LoginPhaseProxy {

    @Inject
    private Logger logger;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Plugin initialization logic goes here
    }
}
