package com.caiostoduto.loginPhaseProxy.utils;

import com.caiostoduto.loginPhaseProxy.intercept.BackendInterceptor;
import com.caiostoduto.loginPhaseProxy.intercept.FrontendInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyLoginSessionTest {
    private ProxyLoginSession session;

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void linkReturnsNullWhenFrontendSessionWasNotOpened() {
        assertNull(ProxyLoginSession.link(UUID.randomUUID(), new BackendInterceptor()));
    }

    @Test
    void openAndLinkConnectFrontendAndBackendBridges() {
        UUID playerUuid = UUID.randomUUID();
        FrontendInterceptor frontend = new FrontendInterceptor();
        BackendInterceptor backend = new BackendInterceptor();

        session = ProxyLoginSession.open(playerUuid, frontend);
        ProxyLoginSession linked = ProxyLoginSession.link(playerUuid, backend);

        assertSame(session, linked);
        assertSame(frontend, session.frontendBridge);
        assertSame(backend, session.backendBridge);
        assertTrue(session.isBackendLinked());
    }

    @Test
    void repeatedOpenReusesSessionAndReplacesFrontendBridge() {
        UUID playerUuid = UUID.randomUUID();
        FrontendInterceptor firstFrontend = new FrontendInterceptor();
        FrontendInterceptor secondFrontend = new FrontendInterceptor();

        session = ProxyLoginSession.open(playerUuid, firstFrontend);
        ProxyLoginSession reopened = ProxyLoginSession.open(playerUuid, secondFrontend);

        assertSame(session, reopened);
        assertSame(secondFrontend, session.frontendBridge);
    }

    @Test
    void tracksAndConsumesOutstandingLoginPluginMessageIds() {
        session = ProxyLoginSession.open(UUID.randomUUID(), new FrontendInterceptor());

        session.trackLoginPluginMessageId(10);
        session.trackLoginPluginMessageId(11);

        assertTrue(session.hasOutstandingLoginPluginMessageIds());
        assertTrue(session.outstandingLoginPluginMessageIds().contains(10));
        assertTrue(session.consumeLoginPluginMessageId(10));
        assertFalse(session.consumeLoginPluginMessageId(10));
        assertTrue(session.consumeLoginPluginMessageId(11));
        assertFalse(session.hasOutstandingLoginPluginMessageIds());
    }

    @Test
    void closeClearsSessionStateAndRemovesRegistryEntry() {
        UUID playerUuid = UUID.randomUUID();
        session = ProxyLoginSession.open(playerUuid, new FrontendInterceptor());
        ProxyLoginSession.link(playerUuid, new BackendInterceptor());
        session.trackLoginPluginMessageId(20);

        session.close();

        assertNull(session.frontendBridge);
        assertNull(session.backendBridge);
        assertFalse(session.hasOutstandingLoginPluginMessageIds());
        assertNull(ProxyLoginSession.link(playerUuid, new BackendInterceptor()));
        session = null;
    }
}
