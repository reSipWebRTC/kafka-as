package com.kafkaasr.orchestrator.http;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "session-orchestrator",
                "ts", Instant.now().toEpochMilli());
    }
}
