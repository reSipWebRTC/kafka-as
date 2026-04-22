package com.kafkaasr.control.service;

import com.kafkaasr.control.api.TenantPolicyResponse;
import com.kafkaasr.control.api.TenantPolicyUpsertRequest;
import com.kafkaasr.control.policy.TenantPolicyRepository;
import com.kafkaasr.control.policy.TenantPolicyState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class TenantPolicyService {

    private final TenantPolicyRepository tenantPolicyRepository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    @Autowired
    public TenantPolicyService(
            TenantPolicyRepository tenantPolicyRepository,
            MeterRegistry meterRegistry) {
        this(tenantPolicyRepository, Clock.systemUTC(), meterRegistry);
    }

    TenantPolicyService(
            TenantPolicyRepository tenantPolicyRepository,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.tenantPolicyRepository = tenantPolicyRepository;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public Mono<TenantPolicyResponse> upsertTenantPolicy(String tenantId, TenantPolicyUpsertRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            validateTenantId(tenantId);

            long now = nowMs();
            TenantPolicyState current = tenantPolicyRepository.findByTenantId(tenantId);
            boolean created = current == null;

            TenantPolicyState target;
            if (current == null) {
                target = new TenantPolicyState(
                        tenantId,
                        request.sourceLang(),
                        request.targetLang(),
                        request.asrModel(),
                        request.translationModel(),
                        request.ttsVoice(),
                        request.maxConcurrentSessions(),
                        request.rateLimitPerMinute(),
                        request.enabled(),
                        1L,
                        now);
                if (!tenantPolicyRepository.createIfAbsent(target)) {
                    TenantPolicyState existing = tenantPolicyRepository.findByTenantId(tenantId);
                    if (existing == null) {
                        throw new IllegalStateException("Tenant policy lost during concurrent create for " + tenantId);
                    }
                    target = existing.withUpdated(
                            request.sourceLang(),
                            request.targetLang(),
                            request.asrModel(),
                            request.translationModel(),
                            request.ttsVoice(),
                            request.maxConcurrentSessions(),
                            request.rateLimitPerMinute(),
                            request.enabled(),
                            existing.version() + 1,
                            now);
                    tenantPolicyRepository.save(target);
                    created = false;
                }
            } else {
                target = current.withUpdated(
                        request.sourceLang(),
                        request.targetLang(),
                        request.asrModel(),
                        request.translationModel(),
                        request.ttsVoice(),
                        request.maxConcurrentSessions(),
                        request.rateLimitPerMinute(),
                        request.enabled(),
                        current.version() + 1,
                        now);
                tenantPolicyRepository.save(target);
            }

            meterRegistry.counter(
                            "controlplane.tenant.policy.upsert.total",
                            "result",
                            created ? "created" : "updated",
                            "code",
                            "OK")
                    .increment();
            return Mono.just(toResponse(target, created));
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "controlplane.tenant.policy.upsert.total",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            return Mono.error(exception);
        } finally {
            sample.stop(meterRegistry.timer("controlplane.tenant.policy.upsert.duration"));
        }
    }

    public Mono<TenantPolicyResponse> getTenantPolicy(String tenantId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            validateTenantId(tenantId);

            TenantPolicyState state = tenantPolicyRepository.findByTenantId(tenantId);
            if (state == null) {
                throw ControlPlaneException.tenantPolicyNotFound(tenantId);
            }

            meterRegistry.counter(
                            "controlplane.tenant.policy.get.total",
                            "result",
                            "found",
                            "code",
                            "OK")
                    .increment();
            return Mono.just(toResponse(state, false));
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "controlplane.tenant.policy.get.total",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            return Mono.error(exception);
        } finally {
            sample.stop(meterRegistry.timer("controlplane.tenant.policy.get.duration"));
        }
    }

    private TenantPolicyResponse toResponse(TenantPolicyState state, boolean created) {
        return new TenantPolicyResponse(
                state.tenantId(),
                state.sourceLang(),
                state.targetLang(),
                state.asrModel(),
                state.translationModel(),
                state.ttsVoice(),
                state.maxConcurrentSessions(),
                state.rateLimitPerMinute(),
                state.enabled(),
                state.version(),
                state.updatedAtMs(),
                created);
    }

    private void validateTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw ControlPlaneException.invalidMessage("tenantId must not be blank", "");
        }
    }

    private long nowMs() {
        return Instant.now(clock).toEpochMilli();
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof ControlPlaneException exception) {
            return exception.code();
        }
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_MESSAGE";
        }
        return "INTERNAL_ERROR";
    }
}
