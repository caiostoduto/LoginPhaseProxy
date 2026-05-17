package com.caiostoduto.loginPhaseProxy.protocol;

import com.velocitypowered.proxy.connection.PlayerDataForwarding;
import com.velocitypowered.proxy.protocol.packet.LoginPluginMessagePacket;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginPluginOwnershipTest {

    @Test
    void treatsForgeLoginWrapperAsAmbassadorOwned() {
        LoginPluginMessagePacket packet = message("fml:loginwrapper");

        try {
            assertTrue(LoginPluginOwnership.isAmbassadorOwned(packet));
            assertFalse(LoginPluginOwnership.isVelocityModernForwarding(packet));
        } finally {
            ReferenceCountUtil.release(packet);
        }
    }

    @Test
    void treatsVelocityForwardingChannelAsVelocityOwned() {
        LoginPluginMessagePacket packet = message(PlayerDataForwarding.CHANNEL);

        try {
            assertTrue(LoginPluginOwnership.isVelocityModernForwarding(packet));
            assertFalse(LoginPluginOwnership.isAmbassadorOwned(packet));
        } finally {
            ReferenceCountUtil.release(packet);
        }
    }

    @Test
    void leavesOtherLoginPluginChannelsForLoginPhaseProxyRelay() {
        LoginPluginMessagePacket packet = message("automodpack:handshake");

        try {
            assertFalse(LoginPluginOwnership.isAmbassadorOwned(packet));
            assertFalse(LoginPluginOwnership.isVelocityModernForwarding(packet));
        } finally {
            ReferenceCountUtil.release(packet);
        }
    }

    private static LoginPluginMessagePacket message(String channel) {
        return new LoginPluginMessagePacket(1, channel, Unpooled.EMPTY_BUFFER);
    }
}
