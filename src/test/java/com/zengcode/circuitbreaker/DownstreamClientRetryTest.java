package com.zengcode.circuitbreaker;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
class DownstreamClientRetryTest {
    @Autowired
    DownstreamClient client;
    static MockWebServer server;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) throws IOException {
        server = new MockWebServer(); server.start();
        reg.add("client.base-url", () -> server.url("/").toString());
    }
    @AfterAll
    static void down() throws IOException { server.shutdown(); }

    static void enqueue(int code, String body) {
        server.enqueue(new MockResponse().setResponseCode(code).setBody(body));
    }

    @Test
    void retry_three_times_then_success() {
        // Fail 2 ครั้งแรก → สำเร็จครั้งที่ 3
        enqueue(500, "err1");
        enqueue(500, "err2");
        enqueue(200, "OK");

        String r = client.getData();
        assertEquals("OK", r);
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void retry_all_fail_then_fallback() {
        enqueue(500, "err1");
        enqueue(500, "err2");
        enqueue(500, "err3");

        String r = client.getData();
        assertEquals("FALLBACK", r);
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void retry_one_fail_then_fallback() {
        enqueue(500, "err1");
        enqueue(200, "OK");

        String r = client.getData();
        assertEquals("OK", r);
        assertEquals(2, server.getRequestCount());
    }
}