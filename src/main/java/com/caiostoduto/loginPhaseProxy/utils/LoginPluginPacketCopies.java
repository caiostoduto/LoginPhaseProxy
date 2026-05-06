package com.caiostoduto.loginPhaseProxy.utils;

import com.velocitypowered.proxy.protocol.packet.LoginPluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket;

public final class LoginPluginPacketCopies {

    private LoginPluginPacketCopies() {}

    public static LoginPluginMessagePacket copy(LoginPluginMessagePacket packet) {
        return new LoginPluginMessagePacket(
                packet.getId(),
                packet.getChannel(),
                packet.content().copy()
        );
    }

    public static LoginPluginResponsePacket copy(LoginPluginResponsePacket packet) {
        return new LoginPluginResponsePacket(
                packet.getId(),
                packet.isSuccess(),
                packet.content().copy()
        );
    }
}
