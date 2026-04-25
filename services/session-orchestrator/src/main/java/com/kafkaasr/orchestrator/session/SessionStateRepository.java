package com.kafkaasr.orchestrator.session;

import java.util.List;

public interface SessionStateRepository {

    SessionState findBySessionId(String sessionId);

    boolean createIfAbsent(SessionState state);

    void save(SessionState state);

    long countActiveSessionsByTenantId(String tenantId);

    List<SessionState> findActiveSessions();
}
