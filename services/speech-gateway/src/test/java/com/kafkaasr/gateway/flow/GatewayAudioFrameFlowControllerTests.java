package com.kafkaasr.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class GatewayAudioFrameFlowControllerTests {

    @Test
    void enforcesRateLimitWithinSameSecondWindow() {
        GatewayFlowControlProperties properties = new GatewayFlowControlProperties();
        properties.setAudioFrameRateLimitPerSecond(2);
        properties.setAudioFrameMaxInflight(4);

        GatewayAudioFrameFlowController controller = new GatewayAudioFrameFlowController(
                properties,
                Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC));

        assertEquals(GatewayAudioFrameFlowController.FlowDecision.ALLOWED, controller.acquire("sess-1"));
        assertEquals(GatewayAudioFrameFlowController.FlowDecision.ALLOWED, controller.acquire("sess-1"));
        assertEquals(GatewayAudioFrameFlowController.FlowDecision.RATE_LIMITED, controller.acquire("sess-1"));
    }

    @Test
    void enforcesInFlightBackpressureAndRecoversAfterRelease() {
        GatewayFlowControlProperties properties = new GatewayFlowControlProperties();
        properties.setAudioFrameRateLimitPerSecond(100);
        properties.setAudioFrameMaxInflight(1);

        GatewayAudioFrameFlowController controller = new GatewayAudioFrameFlowController(
                properties,
                Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC));

        assertEquals(GatewayAudioFrameFlowController.FlowDecision.ALLOWED, controller.acquire("sess-2"));
        assertEquals(GatewayAudioFrameFlowController.FlowDecision.BACKPRESSURE_DROP, controller.acquire("sess-2"));

        controller.release("sess-2");
        assertEquals(GatewayAudioFrameFlowController.FlowDecision.ALLOWED, controller.acquire("sess-2"));
    }

    @Test
    void resetsSessionStateForRateAndInflight() {
        GatewayFlowControlProperties properties = new GatewayFlowControlProperties();
        properties.setAudioFrameRateLimitPerSecond(1);
        properties.setAudioFrameMaxInflight(1);

        GatewayAudioFrameFlowController controller = new GatewayAudioFrameFlowController(
                properties,
                Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC));

        assertEquals(GatewayAudioFrameFlowController.FlowDecision.ALLOWED, controller.acquire("sess-3"));
        assertEquals(GatewayAudioFrameFlowController.FlowDecision.RATE_LIMITED, controller.acquire("sess-3"));

        controller.reset("sess-3");
        assertEquals(GatewayAudioFrameFlowController.FlowDecision.ALLOWED, controller.acquire("sess-3"));
    }
}
