package com.zengcode.circuitbreaker;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DownstreamClientBulkheadTest {

    private static MockWebServer server;

    // static init: รันทันทีที่โหลดคลาส (ก่อน DynamicPropertySource)
    static {
        try {
            server = new MockWebServer();
            server.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start MockWebServer", e);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (server != null) server.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        // ตรงนี้ server พร้อมแล้วแน่นอน
        reg.add("client.base-url", () -> server.url("/").toString());
    }

    // --- ส่วนที่เหลือเหมือนเดิม ---
    @Autowired DownstreamClient client;
    @Autowired io.github.resilience4j.bulkhead.BulkheadRegistry bulkheadRegistry;
    @Autowired io.github.resilience4j.ratelimiter.RateLimiterRegistry rateLimiterRegistry;

    @Test
    void sanity_bulkhead_config() {
        var bh = bulkheadRegistry.bulkhead("downstreamA");
        assertEquals(2, bh.getBulkheadConfig().getMaxConcurrentCalls());
        assertEquals(Duration.ZERO, bh.getBulkheadConfig().getMaxWaitDuration());
    }

    @Test
    void sanity() {
        var bh = bulkheadRegistry.bulkhead("downstreamA");
        assertEquals(2, bh.getBulkheadConfig().getMaxConcurrentCalls());

        var rl = rateLimiterRegistry.rateLimiter("downstreamA");
        assertEquals(2, rl.getRateLimiterConfig().getLimitForPeriod());
        assertEquals(Duration.ofSeconds(1), rl.getRateLimiterConfig().getLimitRefreshPeriod());
        assertEquals(Duration.ZERO, rl.getRateLimiterConfig().getTimeoutDuration());
    }

    @Test
    void bulkhead_blocks_when_full() throws Exception {
        server.enqueue(new MockResponse().setBody("OK1").setBodyDelay(500, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setBody("OK2").setBodyDelay(500, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setBody("OK3").setBodyDelay(500, TimeUnit.MILLISECONDS));

        var pool = Executors.newFixedThreadPool(3);
        var futures = IntStream.range(0, 3).mapToObj(i -> pool.submit(() -> {
            try { return client.getDataWithOutRetry(); }
            catch (Exception ex) { return "ERR:" + ex.getClass().getSimpleName(); }
        })).toList();

        var results = new ArrayList<String>();
        for (var f : futures) results.add(f.get());

        long oks = results.stream().filter(s -> s.startsWith("OK")).count();
        long blocked = results.stream().filter(s -> s.startsWith("ERR:")).count();

        assertEquals(2, oks);
        assertEquals(1, blocked);
    }
}