package com.kafkaasr.gateway.http;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class GatewayHealthController {

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of(
                "service", "speech-gateway",
                "status", "ok");
    }
}

