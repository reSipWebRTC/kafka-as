package com.kafkaasr.command.pipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "command.smarthome.enabled", havingValue = "false")
public class PlaceholderSmartHomeClient implements SmartHomeClient {

    @Override
    public SmartHomeApiResponse executeCommand(String sessionId, String userId, String text, String traceId) {
        return new SmartHomeApiResponse(
                traceId,
                "OK",
                "success",
                false,
                "ok",
                "Command handled by placeholder smart-home engine",
                null,
                null,
                "CONTROL",
                "placeholder");
    }

    @Override
    public SmartHomeApiResponse submitConfirm(String confirmToken, boolean accept, String traceId) {
        String status = accept ? "ok" : "cancelled";
        String reply = accept
                ? "Confirmation accepted by placeholder smart-home engine"
                : "Confirmation rejected by placeholder smart-home engine";
        return new SmartHomeApiResponse(
                traceId,
                "OK",
                "success",
                false,
                status,
                reply,
                null,
                null,
                "CONTROL",
                "placeholder");
    }
}
