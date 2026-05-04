package com.caiostoduto.loginPhaseProxy.utils;

import com.caiostoduto.loginPhaseProxy.intercept.BackendInterceptor;
import com.caiostoduto.loginPhaseProxy.intercept.FrontendInterceptor;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ProxyLoginSession {

    // ConcurrentHashMap to prevent data races from frontend/backend event loops
    private static final ConcurrentMap<UUID, ProxyLoginSession> sessions = new ConcurrentHashMap<>();

    // Volatile so cross-thread reads always see the latest write
    public volatile FrontendInterceptor frontendInterceptor;
    public volatile BackendInterceptor backendInterceptor;

    private ProxyLoginSession() {}

    /**
     * Called by FrontendInterceptor once it sees ServerLoginSuccess.
     * Creates the session and registers it so the backend can link to it.
     */
    public static ProxyLoginSession open(UUID playerUUID, FrontendInterceptor frontend) {
        ProxyLoginSession session = new ProxyLoginSession();
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
        ProxyLoginSession session = sessions.get(playerUUID);
        if (session == null) {
            return null;
        }

        session.backendInterceptor = backend;
        return session;
    }

    /**
     * Called by BackendInterceptor once it sees the inbound ServerLoginSuccessPacket.
     * Unlinks the existing session's backend.
     */
    public void unlink() {
        this.backendInterceptor = null;
    }

    /**
     * Removes the session from the registry. Safe to call from any thread.
     */
    public void close(UUID playerUUID) {
        sessions.remove(playerUUID);
    }
}