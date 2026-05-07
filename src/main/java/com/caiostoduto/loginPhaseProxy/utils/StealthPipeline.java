package com.caiostoduto.loginPhaseProxy.utils;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelPipeline;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class StealthPipeline {

    // Java Reflection references
    private static final Field HEAD_FIELD; // io.netty.channel.DefaultChannelPipeline.head
    private static final Field PREV_FIELD; // io.netty.channel.AbstractChannelHandlerContext.prev;
    private static final Field NEXT_FIELD; // io.netty.channel.AbstractChannelHandlerContext.next;

    static {
        try {
            // Grab a live context to find the concrete class at runtime
            Class<?> ctxClass = Class.forName("io.netty.channel.AbstractChannelHandlerContext");

            HEAD_FIELD = DefaultChannelPipeline.class.getDeclaredField("head");
            HEAD_FIELD.setAccessible(true);

            PREV_FIELD = ctxClass.getDeclaredField("prev");
            PREV_FIELD.setAccessible(true);

            NEXT_FIELD = ctxClass.getDeclaredField("next");
            NEXT_FIELD.setAccessible(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Save all handlers stealthly removed so that they can be added again later via restoreHandlers
    private final Queue<RemovedHandlerContext> removedHandlers = new ConcurrentLinkedQueue<>();
    // Store the ChannelHandlerContext and also a List<String> of all previous nodes on the ctx.pipeline().names() list
    private record RemovedHandlerContext(ChannelHandlerContext currentNode, List<String> previousNodes) {}

    /**
     * Remove a handler from the Pipeline stealthily without triggering handlerRemoved for example.
     */
    public void removeIfPresent(ChannelHandlerContext ctx, Class<? extends ChannelHandler> handlerType) {
        ChannelHandlerContext currentNode = ctx.pipeline().context(handlerType);

        if (currentNode != null) {
            List<String> nodes = ctx.pipeline().names();
            // List<String> of all previous nodes on the ctx.pipeline().names() list
            List<String> previousNodes = nodes.subList(0, nodes.indexOf(currentNode.name()));

            // Remove the currentNode reference from the previous and next handlers, as the pipeline is a doubly linked
            //  list
            try {
                ChannelHandlerContext prevNode = (ChannelHandlerContext) PREV_FIELD.get(currentNode);
                ChannelHandlerContext nextNode = (ChannelHandlerContext) NEXT_FIELD.get(currentNode);

                if (prevNode != null) {
                    NEXT_FIELD.set(prevNode, nextNode); // prevNode.next = nextNode
                }

                if (nextNode != null) {
                    PREV_FIELD.set(nextNode, prevNode); // nextNode.prev = prevNode
                }

                // Add the removed handler to the recoverable list of handlers
                RemovedHandlerContext removedHandlerContext = new RemovedHandlerContext(currentNode, previousNodes);
                removedHandlers.add(removedHandlerContext);
            } catch (Exception e) {
                throw new RuntimeException("Failed to stealth remove handler: " + handlerType.getName(), e);
            }
        }
    }

    /**
     * Restore all handlers that were removed by using the function removeIfPresent using the list of previous nodes as
     * reference.
     */
    public void restoreHandlers(ChannelHandlerContext ctx) {
        RemovedHandlerContext removedHandler;
        while ((removedHandler = removedHandlers.poll()) != null) {
            ChannelHandlerContext currentNode = removedHandler.currentNode;
            // List<String> of all previous nodes on the ctx.pipeline().names() list
            List<String> previousNodes = removedHandler.previousNodes;

            // Find the closest previous node from the currentNode
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
