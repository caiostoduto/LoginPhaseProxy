package com.caiostoduto.loginPhaseProxy.initializer;

import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class VelocityChannelInitializer {

    // Call this as soon as we get the ProxyServer instance in the Initializer class
    @SuppressWarnings("unchecked")
    public static void inject(ProxyServer proxy) {
        // Reflect into https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/VelocityServer.java
        // That's what proxy is an instance of
        try {
            // com.velocitypowered.proxy.network.ConnectionManager
            Object cm = field(proxy, "cm").get(proxy);

            // Frontend interceptor: com.velocitypowered.proxy.network.ServerChannelInitializerHolder
            Object sci = field(cm, "serverChannelInitializer").get(cm);
            Field sci_ini = field(sci, "initializer");
            sci_ini.set(sci, new FrontendChannelInitializer((ChannelInitializer<Channel>) sci_ini.get(sci)));

            // Backend interceptor: com.velocitypowered.proxy.network.BackendChannelInitializerHolder
            Object bci = field(cm, "backendChannelInitializer").get(cm);
            Field bci_ini = field(bci, "initializer");
            bci_ini.set(bci, new BackendChannelInitializer((ChannelInitializer<Channel>) bci_ini.get(bci)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to install WrappedChannelInitializer", e);
        }
    }

    private static Field field(Object instance, String name) throws NoSuchFieldException {
        return field(instance.getClass(), name);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new NoSuchFieldException("Field '" + name + "' not found in " + type.getName());
        }
    }

    protected static Method method(Class<?> type, String name, Class<?> arg) throws NoSuchMethodException {
        try {
            Method method = type.getDeclaredMethod(name, arg);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodException("Method '" + name + "(" + arg.getName() + ")' not found in " + type.getName());
        }
    }
}
