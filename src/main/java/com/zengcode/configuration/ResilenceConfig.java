package com.zengcode.configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.ratelimiter.*;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class ResilenceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilenceConfig.class);

    // ---- CircuitBreaker & Retry (เหมือนเดิม)
    @Bean
    public CircuitBreakerConfigCustomizer downstreamACircuitBreaker() {
        return CircuitBreakerConfigCustomizer.of("downstreamA", b -> b
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
        );
    }

    @Bean
    public RetryConfigCustomizer downstreamARetryCustomizer() {
        return RetryConfigCustomizer.of("downstreamA", b -> b
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(200L, 2.0d))
                // Retry เฉพาะ IO และ 5xx เท่านั้น
                .retryOnException(ex ->
                        ex instanceof java.io.IOException ||
                                ex instanceof org.springframework.web.client.HttpServerErrorException
                )
                .failAfterMaxAttempts(true)
        );
    }

    // ---- พรี-สร้าง BulkheadRegistry พร้อม instance "downstreamA"
    @Bean
    @Primary
    public BulkheadRegistry bulkheadRegistry() {
        var cfg = BulkheadConfig.custom()
                .maxConcurrentCalls(2)
                .maxWaitDuration(Duration.ZERO)
                .build();

        var registry = BulkheadRegistry.ofDefaults();
        registry.bulkhead("downstreamA", cfg);
        return registry;
    }

    // ---- พรี-สร้าง RateLimiterRegistry พร้อม instance "downstreamA"
    @Bean
    @Primary
    public RateLimiterRegistry rateLimiterRegistry() {
        var cfg = RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        var registry = RateLimiterRegistry.ofDefaults();
        registry.rateLimiter("downstreamA", cfg);
        return registry;
    }

    // ---- Logs (optional)
    @Bean
    public RegistryEventConsumer<CircuitBreaker> cbEventLogger() {
        return new RegistryEventConsumer<>() {
            @Override public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> e) {
                var cb = e.getAddedEntry();
                cb.getEventPublisher()
                        .onStateTransition(ev -> log.info("CB {} -> {}", ev.getCircuitBreakerName(), ev.getStateTransition()))
                        .onCallNotPermitted(ev -> log.warn("CB {} SHORT-CIRCUIT", ev.getCircuitBreakerName()))
                        .onError(ev -> log.warn("CB {} ERROR: {} ({} ms)", ev.getCircuitBreakerName(),
                                ev.getThrowable(), ev.getElapsedDuration().toMillis()))
                        .onSuccess(ev -> log.info("CB {} SUCCESS ({} ms)", ev.getCircuitBreakerName(),
                                ev.getElapsedDuration().toMillis()));
            }
            @Override public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> e) {}
            @Override public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> e) {}
        };
    }
}