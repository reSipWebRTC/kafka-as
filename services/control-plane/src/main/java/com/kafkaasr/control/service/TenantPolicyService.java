package com.kafkaasr.control.service;

import com.kafkaasr.control.api.TenantPolicyResponse;
import com.kafkaasr.control.api.TenantPolicyRollbackRequest;
import com.kafkaasr.control.api.TenantPolicyUpsertRequest;
import com.kafkaasr.control.events.ControlKafkaProperties;
import com.kafkaasr.control.events.TenantPolicyChangedEvent;
import com.kafkaasr.control.events.TenantPolicyChangedPayload;
import com.kafkaasr.control.events.TenantPolicyChangedPublisher;
import com.kafkaasr.control.policy.TenantPolicyRepository;
import com.kafkaasr.control.policy.TenantPolicyState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class TenantPolicyService {

    private static final int DEFAULT_GRAY_TRAFFIC_PERCENT = 0;
    private static final boolean DEFAULT_FALLBACK_FAIL_OPEN = false;
    private static final long DEFAULT_FALLBACK_CACHE_TTL_MS = 30000L;
    private static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_BACKOFF_MS = 200L;
    private static final String DEFAULT_DLQ_TOPIC_SUFFIX = ".dlq";
    private static final String EVENT_TYPE = "tenant.policy.changed";
    private static final String EVENT_VERSION = "v1";
    private static final String SYNTHETIC_SESSION_PREFIX = "tenant-policy::";
    private static final String OPERATION_CREATED = "CREATED";
    private static final String OPERATION_UPDATED = "UPDATED";
    private static final String OPERATION_ROLLED_BACK = "ROLLED_BACK";
    private static final String OPERATION_ROLLED_BACK_TO_VERSION = "ROLLED_BACK_TO_VERSION";

    private final TenantPolicyRepository tenantPolicyRepository;
    private final TenantPolicyChangedPublisher tenantPolicyChangedPublisher;
    private final ControlKafkaProperties kafkaProperties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    @Autowired
    public TenantPolicyService(
            TenantPolicyRepository tenantPolicyRepository,
            TenantPolicyChangedPublisher tenantPolicyChangedPublisher,
            ControlKafkaProperties kafkaProperties,
            MeterRegistry meterRegistry) {
        this(tenantPolicyRepository, tenantPolicyChangedPublisher, kafkaProperties, Clock.systemUTC(), meterRegistry);
    }

    TenantPolicyService(
            TenantPolicyRepository tenantPolicyRepository,
            TenantPolicyChangedPublisher tenantPolicyChangedPublisher,
            ControlKafkaProperties kafkaProperties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.tenantPolicyRepository = tenantPolicyRepository;
        this.tenantPolicyChangedPublisher = tenantPolicyChangedPublisher;
        this.kafkaProperties = kafkaProperties;
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
            boolean grayEnabled = Boolean.TRUE.equals(request.grayEnabled());
            int grayTrafficPercent = normalizeGrayTrafficPercent(grayEnabled, request.grayTrafficPercent());
            boolean fallbackFailOpen = request.controlPlaneFallbackFailOpen() == null
                    ? DEFAULT_FALLBACK_FAIL_OPEN
                    : request.controlPlaneFallbackFailOpen();
            long fallbackCacheTtlMs = request.controlPlaneFallbackCacheTtlMs() == null
                    ? DEFAULT_FALLBACK_CACHE_TTL_MS
                    : request.controlPlaneFallbackCacheTtlMs();
            int retryMaxAttempts = request.retryMaxAttempts() == null
                    ? DEFAULT_RETRY_MAX_ATTEMPTS
                    : request.retryMaxAttempts();
            long retryBackoffMs = request.retryBackoffMs() == null
                    ? DEFAULT_RETRY_BACKOFF_MS
                    : request.retryBackoffMs();
            String dlqTopicSuffix = normalizeDlqTopicSuffix(request.dlqTopicSuffix());

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
                        grayEnabled,
                        grayTrafficPercent,
                        fallbackFailOpen,
                        fallbackCacheTtlMs,
                        retryMaxAttempts,
                        retryBackoffMs,
                        dlqTopicSuffix,
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
                            grayEnabled,
                            grayTrafficPercent,
                            fallbackFailOpen,
                            fallbackCacheTtlMs,
                            retryMaxAttempts,
                            retryBackoffMs,
                            dlqTopicSuffix,
                            existing.version() + 1,
                            now);
                    tenantPolicyRepository.appendHistory(existing);
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
                        grayEnabled,
                        grayTrafficPercent,
                        fallbackFailOpen,
                        fallbackCacheTtlMs,
                        retryMaxAttempts,
                        retryBackoffMs,
                        dlqTopicSuffix,
                        current.version() + 1,
                        now);
                tenantPolicyRepository.appendHistory(current);
                tenantPolicyRepository.save(target);
            }

            meterRegistry.counter(
                            "controlplane.tenant.policy.upsert.total",
                            "result",
                            created ? "created" : "updated",
                            "code",
                            "OK")
                    .increment();
            TenantPolicyResponse response = toResponse(target, created);
            String operation = created ? OPERATION_CREATED : OPERATION_UPDATED;
            return publishPolicyChangedEvent(toPolicyChangedEvent(target, operation, null, null, List.of()))
                    .thenReturn(response);
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

    public Mono<TenantPolicyResponse> rollbackTenantPolicy(String tenantId) {
        return rollbackTenantPolicy(tenantId, null);
    }

    public Mono<TenantPolicyResponse> rollbackTenantPolicy(String tenantId, TenantPolicyRollbackRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Long requestedTargetVersion = request == null ? null : request.targetVersion();
        String operation = requestedTargetVersion == null ? OPERATION_ROLLED_BACK : OPERATION_ROLLED_BACK_TO_VERSION;
        try {
            validateTenantId(tenantId);

            long now = nowMs();
            TenantPolicyState current = tenantPolicyRepository.findByTenantId(tenantId);
            if (current == null) {
                throw ControlPlaneException.tenantPolicyNotFound(tenantId);
            }

            List<String> distributionRegions = normalizeDistributionRegions(
                    request == null ? null : request.distributionRegions());
            TenantPolicyState previous = resolveRollbackTarget(tenantId, current, requestedTargetVersion);

            TenantPolicyState target = previous.withUpdated(
                    previous.sourceLang(),
                    previous.targetLang(),
                    previous.asrModel(),
                    previous.translationModel(),
                    previous.ttsVoice(),
                    previous.maxConcurrentSessions(),
                    previous.rateLimitPerMinute(),
                    previous.enabled(),
                    previous.grayEnabled(),
                    previous.grayTrafficPercent(),
                    previous.controlPlaneFallbackFailOpen(),
                    previous.controlPlaneFallbackCacheTtlMs(),
                    previous.retryMaxAttempts(),
                    previous.retryBackoffMs(),
                    previous.dlqTopicSuffix(),
                    current.version() + 1,
                    now);
            tenantPolicyRepository.appendHistory(current);
            tenantPolicyRepository.save(target);

            meterRegistry.counter(
                            "controlplane.tenant.policy.rollback.total",
                            "result",
                            "rolled_back",
                            "code",
                            "OK",
                            "operation",
                            operation,
                            "distributionRegionsCount",
                            Integer.toString(distributionRegions.size()))
                    .increment();
            TenantPolicyResponse response = toResponse(target, false);
            return publishPolicyChangedEvent(toPolicyChangedEvent(
                            target,
                            operation,
                            current.version(),
                            previous.version(),
                            distributionRegions))
                    .thenReturn(response);
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "controlplane.tenant.policy.rollback.total",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception),
                            "operation",
                            operation)
                    .increment();
            return Mono.error(exception);
        } finally {
            sample.stop(meterRegistry.timer("controlplane.tenant.policy.rollback.duration"));
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
                state.grayEnabled(),
                state.grayTrafficPercent(),
                state.controlPlaneFallbackFailOpen(),
                state.controlPlaneFallbackCacheTtlMs(),
                state.retryMaxAttempts(),
                state.retryBackoffMs(),
                state.dlqTopicSuffix(),
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

    private int normalizeGrayTrafficPercent(boolean grayEnabled, Integer grayTrafficPercent) {
        int normalized = grayTrafficPercent == null ? DEFAULT_GRAY_TRAFFIC_PERCENT : grayTrafficPercent;
        if (!grayEnabled) {
            return 0;
        }
        if (normalized <= 0) {
            throw ControlPlaneException.invalidMessage("grayTrafficPercent must be > 0 when grayEnabled=true", "");
        }
        return normalized;
    }

    private String normalizeDlqTopicSuffix(String dlqTopicSuffix) {
        if (dlqTopicSuffix == null || dlqTopicSuffix.isBlank()) {
            return DEFAULT_DLQ_TOPIC_SUFFIX;
        }
        return dlqTopicSuffix;
    }

    private List<String> normalizeDistributionRegions(List<String> distributionRegions) {
        if (distributionRegions == null || distributionRegions.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String region : distributionRegions) {
            if (region == null || region.isBlank()) {
                throw ControlPlaneException.invalidMessage("distributionRegions must not contain blank values", "");
            }
            normalized.add(region.trim());
        }
        return List.copyOf(normalized);
    }

    private TenantPolicyState resolveRollbackTarget(String tenantId, TenantPolicyState current, Long requestedTargetVersion) {
        if (requestedTargetVersion == null) {
            TenantPolicyState previous = tenantPolicyRepository.findLatestHistory(tenantId);
            if (previous == null) {
                throw ControlPlaneException.tenantPolicyRollbackNotAvailable(tenantId);
            }
            return previous;
        }

        if (requestedTargetVersion >= current.version()) {
            throw ControlPlaneException.tenantPolicyRollbackVersionInvalid(
                    tenantId, requestedTargetVersion, current.version());
        }

        TenantPolicyState target = tenantPolicyRepository.findHistoryByVersion(tenantId, requestedTargetVersion);
        if (target == null) {
            throw ControlPlaneException.tenantPolicyVersionNotFound(tenantId, requestedTargetVersion);
        }
        return target;
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

    private Mono<Void> publishPolicyChangedEvent(TenantPolicyChangedEvent event) {
        return tenantPolicyChangedPublisher.publish(event)
                .doOnSuccess(ignored -> meterRegistry.counter(
                                "controlplane.tenant.policy.distribution.publish.total",
                                "result",
                                "success",
                                "operation",
                                event.payload().operation())
                        .increment())
                .doOnError(ignored -> meterRegistry.counter(
                                "controlplane.tenant.policy.distribution.publish.total",
                                "result",
                                "error",
                                "operation",
                                event.payload().operation())
                        .increment())
                .onErrorResume(ignored -> Mono.empty());
    }

    private TenantPolicyChangedEvent toPolicyChangedEvent(
            TenantPolicyState state,
            String operation,
            Long sourcePolicyVersion,
            Long targetPolicyVersion,
            List<String> distributionRegions) {
        return new TenantPolicyChangedEvent(
                prefixedId("evt"),
                EVENT_TYPE,
                EVENT_VERSION,
                prefixedId("trc"),
                SYNTHETIC_SESSION_PREFIX + state.tenantId(),
                state.tenantId(),
                null,
                kafkaProperties.getProducerId(),
                state.version(),
                nowMs(),
                state.tenantId() + ":" + EVENT_TYPE + ":" + state.version(),
                new TenantPolicyChangedPayload(
                        state.tenantId(),
                        state.version(),
                        state.updatedAtMs(),
                        operation,
                        sourcePolicyVersion,
                        targetPolicyVersion,
                        distributionRegions == null || distributionRegions.isEmpty() ? null : distributionRegions,
                        null));
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
