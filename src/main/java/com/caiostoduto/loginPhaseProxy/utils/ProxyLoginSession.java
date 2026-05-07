package com.caiostoduto.loginPhaseProxy.utils;

import com.caiostoduto.loginPhaseProxy.intercept.BackendInterceptor;
import com.caiostoduto.loginPhaseProxy.intercept.FrontendInterceptor;
import com.velocitypowered.proxy.protocol.packet.LoginPluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

public class ProxyLoginSession {

    // ConcurrentHashMap to prevent data races from frontend/backend event loops
    private static final ConcurrentMap<UUID, ProxyLoginSession> sessions = new ConcurrentHashMap<>();

    // Volatile so cross-thread reads always see the latest write
    public volatile FrontendInterceptor frontendInterceptor;
    public volatile BackendInterceptor backendInterceptor;

    private final UUID playerUUID;
    private final AtomicReference<State> state = new AtomicReference<>(State.FRONTEND_OPEN);
    private final Set<Integer> outstandingLoginPluginMessageIds = ConcurrentHashMap.newKeySet();

    private ProxyLoginSession(UUID playerUUID) {
        this.playerUUID = Objects.requireNonNull(playerUUID, "playerUUID");
    }

    public enum State {
        FRONTEND_OPEN,
        BACKEND_LINKED,
        BACKEND_UNLINKED,
        CLOSED,
        FAILED,
    }

    /**
     * Called by FrontendInterceptor once it sees ServerLoginSuccess.
     * Creates the session and registers it so the backend can link to it.
     */
    public static ProxyLoginSession open(UUID playerUUID, FrontendInterceptor frontend) {
        Objects.requireNonNull(frontend, "frontend");

        ProxyLoginSession session = new ProxyLoginSession(playerUUID);
        session.frontendInterceptor = frontend;

        // putIfAbsent guards against the (unlikely) duplicate-UUID edge case
        ProxyLoginSession existing = sessions.putIfAbsent(playerUUID, session);
        if (existing != null) {
            // Session already existed — reuse it and just attach the frontend
            existing.frontendInterceptor = frontend;
            return existing;
        }

        return session;
    }

    /**
     * Called by BackendInterceptor once it sees the outbound ServerLoginPacket.
     * Links the backend to an existing session created by the frontend.
     *
     * @return the session, or null if the frontend has not registered one yet
     *         (caller must handle this case explicitly)
     */
    public static ProxyLoginSession link(UUID playerUUID, BackendInterceptor backend) {
        Objects.requireNonNull(playerUUID, "playerUUID");
        Objects.requireNonNull(backend, "backend");

        ProxyLoginSession session = sessions.get(playerUUID);
        if (session == null) {
            return null;
        }

        session.backendInterceptor = backend;
        session.transition(State.BACKEND_LINKED);
        return session;
    }

    /**
     * Called by BackendInterceptor once it sees the inbound ServerLoginSuccessPacket.
     * Unlinks the existing session's backend.
     */
    public void unlink() {
        this.backendInterceptor = null;
        transition(State.BACKEND_UNLINKED);
    }

    /**
     * Records a backend LoginPluginMessage id that must receive a client response.
     */
    public void trackLoginPluginMessageId(int id) {
        outstandingLoginPluginMessageIds.add(id);
    }

    public void trackLoginPluginMessage(LoginPluginMessagePacket packet) {
        trackLoginPluginMessageId(packet.getId());
    }

    /**
     * Marks a LoginPluginResponse id as handled.
     *
     * @return true if the id was outstanding and should be relayed to the backend
     */
    public boolean consumeLoginPluginMessageId(int id) {
        return outstandingLoginPluginMessageIds.remove(id);
    }

    public boolean consumeLoginPluginResponse(LoginPluginResponsePacket packet) {
        return consumeLoginPluginMessageId(packet.getId());
    }

    /**
     * Check if there are any outstanding that should be relayed to the backend
     *
     * @return true if there are any outstanding that should be relayed to the backend
     */
    public boolean hasOutstandingLoginPluginMessageIds() {
        return !outstandingLoginPluginMessageIds.isEmpty();
    }

    /**
     * Removes this session from the registry. Safe to call from any thread.
     */
    public void close() {
        sessions.remove(this.playerUUID, this);
        outstandingLoginPluginMessageIds.clear();
        this.backendInterceptor = null;
        this.frontendInterceptor = null;
        state.set(State.CLOSED);
    }

    /**
     * Check if the BackendInterceptor already linked to this session
     * @return true if the BackendInterceptor already linked to this session
     */
    public boolean isBackendLinked() {
        return state.get() == State.BACKEND_LINKED && backendInterceptor != null;
    }

    /**
     * Transition from one state to another respecting an internal logic
     */
    private void transition(State nextState) {
        while (true) {
            State current = state.get();
            if (current == State.CLOSED || current == State.FAILED) {
                return;
            }
            if (state.compareAndSet(current, nextState)) {
                return;
            }
        }
    }
}
