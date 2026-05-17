package com.caiostoduto.loginPhaseProxy.utils;

import com.velocitypowered.proxy.protocol.packet.LoginPluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginPluginPacketCopiesTest {

    @Test
    void copiesLoginPluginMessageMetadataAndContent() {
        LoginPluginMessagePacket original = new LoginPluginMessagePacket(
                42,
                "automodpack:data",
                buffer(1, 2, 3, 4)
        );
        LoginPluginMessagePacket copy = LoginPluginPacketCopies.copy(original);

        try {
            assertEquals(original.getId(), copy.getId());
            assertEquals(original.getChannel(), copy.getChannel());
            assertNotSame(original.content(), copy.content());
            assertArrayEquals(bytes(original.content()), bytes(copy.content()));

            original.content().setByte(0, 99);
            assertEquals(1, copy.content().getByte(0));
        } finally {
            ReferenceCountUtil.release(original);
            ReferenceCountUtil.release(copy);
        }
    }

    @Test
    void copiesLoginPluginResponseMetadataAndContent() {
        LoginPluginResponsePacket original = new LoginPluginResponsePacket(
                -100,
                true,
                buffer(7, 8, 9)
        );
        LoginPluginResponsePacket copy = LoginPluginPacketCopies.copy(original);

        try {
            assertEquals(original.getId(), copy.getId());
            assertTrue(copy.isSuccess());
            assertNotSame(original.content(), copy.content());
            assertArrayEquals(bytes(original.content()), bytes(copy.content()));

            original.content().setByte(1, 77);
            assertEquals(8, copy.content().getByte(1));
        } finally {
            ReferenceCountUtil.release(original);
            ReferenceCountUtil.release(copy);
        }
    }

    private static ByteBuf buffer(int... values) {
        ByteBuf buf = Unpooled.buffer(values.length);
        for (int value : values) {
            buf.writeByte(value);
        }
        return buf;
    }

    private static byte[] bytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }
}
