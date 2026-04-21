package com.kafkaasr.orchestrator.api;

import com.kafkaasr.orchestrator.service.SessionLifecycleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/sessions")
@Validated
public class SessionController {

    private final SessionLifecycleService sessionLifecycleService;

    public SessionController(SessionLifecycleService sessionLifecycleService) {
        this.sessionLifecycleService = sessionLifecycleService;
    }

    @PostMapping(":start")
    public Mono<SessionStartResponse> start(@Valid @RequestBody SessionStartRequest request) {
        return sessionLifecycleService.startSession(request);
    }

    @PostMapping("/{sessionId}:stop")
    public Mono<SessionStopResponse> stop(
            @PathVariable @NotBlank String sessionId,
            @RequestBody(required = false) SessionStopRequest request) {
        return sessionLifecycleService.stopSession(sessionId, request == null ? SessionStopRequest.empty() : request);
    }
}
