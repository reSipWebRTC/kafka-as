package com.kafkaasr.orchestrator.events;

import com.kafkaasr.orchestrator.session.SessionState;

public interface SessionCompensationPublisher {

    void publishTimeoutClose(String timeoutType, String outcome, SessionState state, Throwable failure);
}
