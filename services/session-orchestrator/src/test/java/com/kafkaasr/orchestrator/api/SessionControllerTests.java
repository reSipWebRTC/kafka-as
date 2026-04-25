package com.kafkaasr.orchestrator.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kafkaasr.orchestrator.service.SessionControlException;
import com.kafkaasr.orchestrator.service.SessionLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = SessionController.class)
@Import(SessionExceptionHandler.class)
class SessionControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private SessionLifecycleService sessionLifecycleService;

    @Test
    void getSessionReturnsAggregatedState() {
        SessionStatusResponse response = new SessionStatusResponse(
                "sess-1",
                "tenant-a",
                "trc-1",
                "zh-CN",
                "en-US",
                "TRANSLATING",
                1L,
                1710000000000L,
                1710000000200L,
                "",
                1710000000010L,
                1710000000100L,
                1710000000200L,
                0L,
                0L,
                100L,
                200L,
                null,
                null);
        when(sessionLifecycleService.getSession(eq("sess-1")))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/sessions/sess-1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo("sess-1")
                .jsonPath("$.tenantId").isEqualTo("tenant-a")
                .jsonPath("$.status").isEqualTo("TRANSLATING")
                .jsonPath("$.asrFinalLatencyMs").isEqualTo(100)
                .jsonPath("$.translationLatencyMs").isEqualTo(200);
    }

    @Test
    void getSessionMissingReturnsNotFound() {
        when(sessionLifecycleService.getSession(eq("missing")))
                .thenReturn(Mono.error(SessionControlException.sessionNotFound("missing")));

        webTestClient.get()
                .uri("/api/v1/sessions/missing")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SESSION_NOT_FOUND")
                .jsonPath("$.sessionId").isEqualTo("missing");
    }
}
