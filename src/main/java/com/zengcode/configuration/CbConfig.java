package com.zengcode.configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CbConfig {

    private static final Logger log = LoggerFactory.getLogger(CbConfig.class);

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