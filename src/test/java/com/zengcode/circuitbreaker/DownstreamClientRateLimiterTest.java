package com.zengcode.circuitbreaker;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
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
class DownstreamClientRateLimiterTest {

    private static MockWebServer server;

    @BeforeAll
    static void setup() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterAll
    static void shutdown() throws Exception {
        server.shutdown();
    }

    // ชี้ base-url ของ RestClient ไปยัง MockWebServer “ก่อน” โหลด ApplicationContext
    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("client.base-url", () -> server.url("/").toString());
    }

    @Autowired DownstreamClient client;
    @Autowired RateLimiterRegistry rateLimiterRegistry;

    @Test
    void sanity_rateLimiter_config() {
        var rl = rateLimiterRegistry.rateLimiter("downstreamA");
        assertEquals(2, rl.getRateLimiterConfig().getLimitForPeriod());       // 2 calls
        assertEquals(Duration.ofSeconds(1), rl.getRateLimiterConfig().getLimitRefreshPeriod());
        assertEquals(Duration.ZERO, rl.getRateLimiterConfig().getTimeoutDuration());
    }

    @Test
    void rateLimiter_blocks_after_limit() throws Exception {
        // เตรียม response ปลอม 3 อัน (แต่ควรอนุญาตได้แค่ 2 ในช่วง 1s)
        server.enqueue(new MockResponse().setBody("OK1").setBodyDelay(500, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setBody("OK2").setBodyDelay(500, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setBody("OK3"));

        var pool = Executors.newFixedThreadPool(3);
        var futures = IntStream.range(0, 3).mapToObj(i -> pool.submit(() -> {
            try {
                return client.getDataWithOutRetry(); // โดน RateLimiter ครอบ
            } catch (Exception ex) {
                return "ERR:" + ex.getClass().getSimpleName();
            }
        })).toList();

        var results = new ArrayList<String>();
        for (var f : futures) results.add(f.get());

        long oks = results.stream().filter(s -> s.startsWith("OK")).count();
        long blocked = results.stream().filter(s -> s.startsWith("ERR:")).count();

        assertEquals(2, oks);       // ได้ 2 ผ่านตาม limit
        assertEquals(1, blocked);   // อีก 1 โดน block (RequestNotPermitted)
    }
}