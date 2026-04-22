package com.kafkaasr.orchestrator.policy;

import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

@Component
public class ControlPlaneTenantPolicyClient implements TenantPolicyClient {

    private final WebClient webClient;
    private final ControlPlaneClientProperties properties;

    public ControlPlaneTenantPolicyClient(WebClient.Builder webClientBuilder, ControlPlaneClientProperties properties) {
        this.webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .build();
        this.properties = properties;
    }

    @Override
    public Mono<TenantPolicy> getTenantPolicy(String tenantId) {
        return webClient.get()
                .uri("/api/v1/tenants/{tenantId}/policy", tenantId)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(ControlPlaneTenantPolicyResponse.class)
                                .map(ControlPlaneTenantPolicyResponse::toTenantPolicy);
                    }

                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return response.bodyToMono(ControlPlaneErrorResponse.class)
                                .defaultIfEmpty(new ControlPlaneErrorResponse(
                                        "TENANT_POLICY_NOT_FOUND",
                                        "Tenant policy does not exist: " + tenantId,
                                        tenantId))
                                .flatMap(error -> Mono.error(TenantPolicyClientException.notFound(error.message())));
                    }

                    if (response.statusCode().is4xxClientError()) {
                        return response.bodyToMono(ControlPlaneErrorResponse.class)
                                .defaultIfEmpty(new ControlPlaneErrorResponse(
                                        "INVALID_MESSAGE",
                                        "Control-plane rejected tenant policy request",
                                        tenantId))
                                .flatMap(error -> Mono.error(TenantPolicyClientException.rejected(error.message())));
                    }

                    return Mono.error(TenantPolicyClientException.unavailable(
                            "Control-plane returned %s while loading policy for tenant %s"
                                    .formatted(response.statusCode(), tenantId),
                            null));
                })
                .timeout(properties.getRequestTimeout())
                .onErrorMap(TimeoutException.class, exception -> TenantPolicyClientException.unavailable(
                        "Timed out loading tenant policy for " + tenantId,
                        exception))
                .onErrorMap(WebClientRequestException.class, exception -> TenantPolicyClientException.unavailable(
                        "Control-plane request failed for tenant " + tenantId,
                        exception));
    }
}
