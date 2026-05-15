package com.caiostoduto.loginPhaseProxy.protocol;

import com.velocitypowered.proxy.connection.PlayerDataForwarding;
import com.velocitypowered.proxy.protocol.packet.LoginPluginMessagePacket;

public final class LoginPluginOwnership {
    public static final String FORGE_LOGIN_WRAPPER_CHANNEL = "fml:loginwrapper";

    private LoginPluginOwnership() {}

    public static boolean isVelocityModernForwarding(LoginPluginMessagePacket packet) {
        return PlayerDataForwarding.CHANNEL.equals(packet.getChannel());
    }

    public static boolean isAmbassadorOwned(LoginPluginMessagePacket packet) {
        return FORGE_LOGIN_WRAPPER_CHANNEL.equals(packet.getChannel());
    }
}
