package com.kafkaasr.control.api;

import com.kafkaasr.control.service.TenantPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/tenants")
@Validated
public class TenantPolicyController {

    private final TenantPolicyService tenantPolicyService;

    public TenantPolicyController(TenantPolicyService tenantPolicyService) {
        this.tenantPolicyService = tenantPolicyService;
    }

    @PutMapping("/{tenantId}/policy")
    public Mono<TenantPolicyResponse> upsertPolicy(
            @PathVariable @NotBlank String tenantId,
            @Valid @RequestBody TenantPolicyUpsertRequest request) {
        return tenantPolicyService.upsertTenantPolicy(tenantId, request);
    }

    @GetMapping("/{tenantId}/policy")
    public Mono<TenantPolicyResponse> getPolicy(@PathVariable @NotBlank String tenantId) {
        return tenantPolicyService.getTenantPolicy(tenantId);
    }
}
