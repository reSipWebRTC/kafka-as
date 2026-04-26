package com.kafkaasr.orchestrator.compensation;

public interface CompensationSagaStateRepository {

    boolean begin(String eventId, String actionType);

    void markSucceeded(String eventId, String actionType, String code);

    void markFailed(String eventId, String actionType, String code);
}
