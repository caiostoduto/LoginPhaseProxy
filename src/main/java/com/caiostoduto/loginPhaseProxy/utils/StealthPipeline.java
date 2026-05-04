package com.caiostoduto.loginPhaseProxy.utils;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

import java.lang.reflect.Field;

public class StealthPipeline {

    private static final Field PREV_FIELD;
    private static final Field NEXT_FIELD;

    static {
        try {
            // Grab a live context to find the concrete class at runtime
            Class<?> ctxClass = Class.forName("io.netty.channel.AbstractChannelHandlerContext");

            PREV_FIELD = ctxClass.getDeclaredField("prev");
            NEXT_FIELD = ctxClass.getDeclaredField("next");
            PREV_FIELD.setAccessible(true);
            NEXT_FIELD.setAccessible(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Returns the ctx node, caller must store this for restore
    public static ChannelHandlerContext stealthRemove(ChannelPipeline pipeline, String handlerName) {
        ChannelHandlerContext ctx = pipeline.context(handlerName);
        if (ctx == null) throw new IllegalArgumentException("Handler not found: " + handlerName);

        try {
            Object prev = PREV_FIELD.get(ctx);
            Object next = NEXT_FIELD.get(ctx);

            NEXT_FIELD.set(prev, next);
            PREV_FIELD.set(next, prev);

            // Deliberately leave prev/next on ctx pointing to old neighbors
            //  so restore knows where to re-insert
        } catch (Exception e) {
            throw new RuntimeException("Failed to stealth remove handler: " + handlerName, e);
        }

        return ctx;
    }

    public static void stealthRestoreAtIndex(ChannelPipeline pipeline, Object savedCtx, int index) {
        try {
            // Walk from head node
            Field headField = pipeline.getClass().getDeclaredField("head");
            headField.setAccessible(true);
            Object current = headField.get(pipeline);

            // Walk `index` steps forward (index 0 = insert after head)
            for (int i = 0; i < index; i++) {
                Object next = NEXT_FIELD.get(current);
                if (next == null) throw new IndexOutOfBoundsException("Index " + index + " out of pipeline bounds");
                current = next;
            }

            // current is now the anchor (node at index-1), current.next is the node at index
            Object anchorNext = NEXT_FIELD.get(current);

            // anchor <-> savedCtx <-> anchorNext
            NEXT_FIELD.set(current, savedCtx);
            PREV_FIELD.set(savedCtx, current);
            NEXT_FIELD.set(savedCtx, anchorNext);
            PREV_FIELD.set(anchorNext, savedCtx);

        } catch (Exception e) {
            throw new RuntimeException("Failed to stealth restore handler at index " + index, e);
        }
    }
}
