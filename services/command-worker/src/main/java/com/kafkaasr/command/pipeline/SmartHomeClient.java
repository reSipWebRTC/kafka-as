package com.kafkaasr.command.pipeline;

public interface SmartHomeClient {

    SmartHomeApiResponse executeCommand(String sessionId, String userId, String text, String traceId);

    SmartHomeApiResponse submitConfirm(String confirmToken, boolean accept, String traceId);
}
