package com.caiostoduto.loginPhaseProxy.utils;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelPipeline;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class StealthPipeline {

    private static final Field HEAD_FIELD;
    private static final Field PREV_FIELD;
    private static final Field NEXT_FIELD;

    static {
        try {
            HEAD_FIELD = DefaultChannelPipeline.class.getDeclaredField("head");
            HEAD_FIELD.setAccessible(true);

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

    private final Queue<RemovedHandlerContext> removedHandlers = new ConcurrentLinkedQueue<>();

    private record RemovedHandlerContext(ChannelHandlerContext currentNode,
                                         List<String> previousNodes) {}

    public void removeIfPresent(ChannelHandlerContext ctx, Class<? extends ChannelHandler> handlerType) {
        ChannelHandlerContext currentNode = ctx.pipeline().context(handlerType);

        if (currentNode != null) {
            List<String> nodes = ctx.pipeline().names();
            List<String> previousNodes = nodes.subList(0, nodes.indexOf(currentNode.name()));

            try {
                ChannelHandlerContext prevNode = (ChannelHandlerContext) PREV_FIELD.get(currentNode);
                ChannelHandlerContext nextNode = (ChannelHandlerContext) NEXT_FIELD.get(currentNode);

                if (prevNode != null) {
                    NEXT_FIELD.set(prevNode, nextNode);
                }

                if (nextNode != null) {
                    PREV_FIELD.set(nextNode, prevNode);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to stealth remove handler: " + handlerType.getName(), e);
            }

            RemovedHandlerContext removedHandlerContext = new RemovedHandlerContext(currentNode, previousNodes);
            removedHandlers.add(removedHandlerContext);
        }
    }

    public void restoreHandlers(ChannelHandlerContext ctx) {
        RemovedHandlerContext removedHandler;
        while ((removedHandler = removedHandlers.poll()) != null) {
            ChannelHandlerContext currentNode = removedHandler.currentNode;
            List<String> previousNodes = removedHandler.previousNodes;

            ChannelHandlerContext prevNode = null;
            for (String previousNodeString : previousNodes.reversed()) {
                prevNode = ctx.pipeline().context(previousNodeString);
                if (prevNode != null) {
                    break;
                }
            }

            try {
                if (prevNode != null) {
                    ChannelHandlerContext nextNode = (ChannelHandlerContext) NEXT_FIELD.get(prevNode);

                    PREV_FIELD.set(currentNode, prevNode); // currentNode.prev = prevNode
                    NEXT_FIELD.set(currentNode, nextNode); // currentNode.next = nextNode
                    PREV_FIELD.set(nextNode, currentNode); // nextNode.prev = currentNode
                    NEXT_FIELD.set(prevNode, currentNode); // prevNode.next = currentNode
                } else {
                    ChannelHandlerContext nextNode = (ChannelHandlerContext) HEAD_FIELD.get(ctx.pipeline());

                    // Set currentNode as head
                    PREV_FIELD.set(currentNode, null);           // currentNode.prev = null
                    NEXT_FIELD.set(currentNode, nextNode);       // currentNode.next = next
                    HEAD_FIELD.set(ctx.pipeline(), currentNode); // head = currentNode
                }
            } catch (java.lang.IllegalAccessException e) {
                throw new RuntimeException("Failed to stealth restore handler: " + currentNode.getClass().getName(), e);
            }
        }
    }
}
