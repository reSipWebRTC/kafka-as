package com.kafkaasr.command.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kafkaasr.command.events.AsrFinalEvent;
import com.kafkaasr.command.events.AsrFinalPayload;
import com.kafkaasr.command.events.CommandConfirmRequestEvent;
import com.kafkaasr.command.events.CommandConfirmRequestPayload;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.events.CommandResultEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CommandPipelineServiceTests {

    @Test
    void mapsAsrFinalToCommandResult() {
        CommandKafkaProperties properties = new CommandKafkaProperties();
        properties.setProducerId("command-worker");

        SmartHomeClient client = new StubSmartHomeClient(
                new SmartHomeApiResponse(
                        "trc-upstream-1",
                        "OK",
                        "success",
                        false,
                        "ok",
                        "Turned on the light",
                        null,
                        null,
                        "CONTROL",
                        "switch.on"),
                null);

        CommandPipelineService service = new CommandPipelineService(
                client,
                properties,
                Clock.fixed(Instant.parse("2026-04-25T00:00:00Z"), ZoneOffset.UTC));

        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "user-a",
                null,
                "asr-worker",
                7L,
                1713744000000L,
                "sess-1:asr.final:7",
                new AsrFinalPayload("Turn on the light", "en-US", 0.9d, true));

        CommandResultEvent output = service.fromAsrFinal(input, input.userId());

        assertTrue(output.eventId().startsWith("evt_"));
        assertEquals("command.result", output.eventType());
        assertEquals("v1", output.eventVersion());
        assertEquals("trc-upstream-1", output.traceId());
        assertEquals("sess-1", output.sessionId());
        assertEquals("tenant-a", output.tenantId());
        assertEquals("user-a", output.userId());
        assertEquals("command-worker", output.producer());
        assertEquals(7L, output.seq());
        assertEquals(Instant.parse("2026-04-25T00:00:00Z").toEpochMilli(), output.ts());
        assertEquals("sess-1:command.result:asr.final:7", output.idempotencyKey());
        assertEquals("ok", output.payload().status());
        assertEquals("OK", output.payload().code());
        assertEquals("Turned on the light", output.payload().replyText());
        assertEquals("Turned on the light", output.payload().ttsText());
        assertEquals("CONTROL", output.payload().intent());
        assertEquals("switch.on", output.payload().subIntent());
    }

    @Test
    void mapsConfirmRequestToCommandResult() {
        CommandKafkaProperties properties = new CommandKafkaProperties();
        properties.setProducerId("command-worker");

        SmartHomeClient client = new StubSmartHomeClient(
                null,
                new SmartHomeApiResponse(
                        "trc-upstream-2",
                        "OK",
                        "success",
                        false,
                        "ok",
                        "Confirmed and executed",
                        null,
                        null,
                        "CONTROL",
                        "unlock.confirm"));

        CommandPipelineService service = new CommandPipelineService(
                client,
                properties,
                Clock.fixed(Instant.parse("2026-04-25T00:00:00Z"), ZoneOffset.UTC));

        CommandConfirmRequestEvent input = new CommandConfirmRequestEvent(
                "evt-in-2",
                "command.confirm.request",
                "v1",
                "trc-2",
                "sess-2",
                "tenant-home",
                "user-home",
                null,
                "speech-gateway",
                10L,
                1713744001000L,
                "sess-2:command.confirm.request:10",
                new CommandConfirmRequestPayload("cfm-1", true));

        CommandResultEvent output = service.fromCommandConfirmRequest(input);
        assertEquals("sess-2:command.result:command.confirm.request:10", output.idempotencyKey());
        assertEquals("Confirmed and executed", output.payload().replyText());
        assertEquals("OK", output.payload().code());
    }

    @Test
    void rejectsUnexpectedAsrEventType() {
        CommandPipelineService service = new CommandPipelineService(
                new StubSmartHomeClient(
                        new SmartHomeApiResponse("trc", "OK", "success", false, "ok", "x", null, null, null, null),
                        null),
                new CommandKafkaProperties(),
                Clock.fixed(Instant.parse("2026-04-25T00:00:00Z"), ZoneOffset.UTC));

        AsrFinalEvent invalid = new AsrFinalEvent(
                "evt-in-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "user-a",
                null,
                "translation-worker",
                1L,
                1713744000000L,
                "sess-1:translation.result:1",
                new AsrFinalPayload("x", "en-US", 0.9d, true));

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> service.fromAsrFinal(invalid, "user-a"));
        assertTrue(exception.getMessage().contains("Unsupported asr.final eventType"));
    }

    private static final class StubSmartHomeClient implements SmartHomeClient {

        private final SmartHomeApiResponse commandResponse;
        private final SmartHomeApiResponse confirmResponse;

        private StubSmartHomeClient(
                SmartHomeApiResponse commandResponse,
                SmartHomeApiResponse confirmResponse) {
            this.commandResponse = commandResponse;
            this.confirmResponse = confirmResponse;
        }

        @Override
        public SmartHomeApiResponse executeCommand(String sessionId, String userId, String text, String traceId) {
            return commandResponse;
        }

        @Override
        public SmartHomeApiResponse submitConfirm(String confirmToken, boolean accept, String traceId) {
            return confirmResponse;
        }
    }
}
