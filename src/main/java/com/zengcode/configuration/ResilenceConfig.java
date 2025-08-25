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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class ResilenceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilenceConfig.class);

    @Bean
    public CircuitBreakerConfigCustomizer downstreamACustomizer() {
        return CircuitBreakerConfigCustomizer.of("downstreamA", builder -> builder
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)   // <<< จุดชี้เป็นชี้ตายของเทสต์
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
        );
    }

    // ---------------- Retry
    @Bean
    public RetryConfigCustomizer downstreamARetryCustomizer() {
        return RetryConfigCustomizer.of("downstreamA", builder -> builder
                // max-attempts: 3 (รวมครั้งแรก)
                .maxAttempts(3)
                // wait-duration + exponential backoff + randomized (jitter)
                // base = 200ms, multiplier = 2x → 200ms, 400ms, ...
                .waitDuration(Duration.ofMillis(200))
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        200L,      // initial interval millis
                        2.0d       // multiplier
                ))
                // รีทรายเฉพาะ error ชั่วคราวที่ “ควรลองใหม่”
                .retryExceptions(
                        IOException.class,                 // network
                        HttpServerErrorException.class     // 5xx จาก RestClient
                )
                // ไม่รีทราย 4xx (ผิดฝั่ง client)
                .ignoreExceptions(HttpClientErrorException.class)
        );
    }

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