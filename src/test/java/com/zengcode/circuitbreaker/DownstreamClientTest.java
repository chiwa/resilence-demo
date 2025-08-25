package com.zengcode.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class DownstreamClientTest {

    @Autowired
    DownstreamClient client;

    @Autowired
    CircuitBreakerRegistry cbRegistry;

    static MockWebServer server;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) throws IOException {
        server = new MockWebServer();
        server.start();
        reg.add("client.base-url", () -> server.url("/").toString());
    }

    @AfterAll
    static void down() throws IOException { server.shutdown(); }

    static void enqueue(int code, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody(body));
    }

    @Test
    void opens_then_shortCircuits_to_fallback() {
        CircuitBreaker cb = cbRegistry.circuitBreaker("downstreamA");

        // บังคับ OPEN → short-circuit แน่นอน
        cb.transitionToOpenState();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        int before = server.getRequestCount();

        // ต่อให้ปลายทางเป็น 200 ก็ไม่ยิง เพราะ OPEN แล้วจะเข้า fallback ทันที
        enqueue(200, "OK");
        String r = client.getData();

        int after = server.getRequestCount();
        assertEquals("FALLBACK", r);
        assertEquals(before, after, "OPEN แล้ว ไม่ควรยิงปลายทางเพิ่ม (short-circuit)");
    }

    @Test
    void halfOpen_then_close_when_recovered() {
        CircuitBreaker cb = cbRegistry.circuitBreaker("downstreamA");

        // บังคับเข้า OPEN → HALF_OPEN
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        int n = cb.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState(); // ค่า N จริง
        int before = server.getRequestCount();

        for (int i = 0; i < n; i++) {
            enqueue(200, "OK");
            assertEquals("OK", client.getData());
        }

        int after = server.getRequestCount();
        assertEquals(before + n, after, "ควรยิงปลายทางจริงครบ N ครั้ง");

        // ให้เวลาสั้น ๆ ให้ state machine ตัดสินใจ
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(1))
                .until(() -> cb.getState() == CircuitBreaker.State.CLOSED);
    }
}