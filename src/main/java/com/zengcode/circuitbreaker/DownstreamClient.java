package com.zengcode.circuitbreaker;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DownstreamClient {
    private final RestClient http;
    public DownstreamClient(RestClient http) { this.http = http; }

    @Retry(name = "downstreamA", fallbackMethod = "fallback")
    @CircuitBreaker(name = "downstreamA")
    public String getData() {
        return http.get().uri("/api/data").retrieve().body(String.class);
    }


    @Bulkhead(name = "downstreamA", type = Bulkhead.Type.SEMAPHORE)
    @RateLimiter(name = "downstreamA")
    public String getDataWithOutRetry() {
        return http.get().uri("/api/data").retrieve().body(String.class);
    }

    // ---- fallback overloads ที่ “ยอมรับ” ให้คืนค่าได้ ----
    @SuppressWarnings("unused")
    private String fallback(org.springframework.web.client.HttpServerErrorException ex) {
        return "FALLBACK";
    }

    @SuppressWarnings("unused")
    private String fallback(java.io.IOException ex) {
        return "FALLBACK";
    }

    @SuppressWarnings("unused")
    private String fallback(io.github.resilience4j.circuitbreaker.CallNotPermittedException ex) {
        return "FALLBACK";
    }

}