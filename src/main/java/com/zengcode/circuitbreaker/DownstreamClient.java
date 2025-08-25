package com.zengcode.circuitbreaker;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DownstreamClient {
    private final RestClient http;
    public DownstreamClient(RestClient http) { this.http = http; }

    @CircuitBreaker(name = "downstreamA", fallbackMethod = "fallback")
    public String getData() {
        return http.get().uri("/api/data").retrieve().body(String.class);
    }

    @SuppressWarnings("unused")
    private String fallback(Throwable ex) {
        System.out.println("fallback ex=" + ex.getClass() + " : " + ex.getMessage());
        return "FALLBACK";
    }
}