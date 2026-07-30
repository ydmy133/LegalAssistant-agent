package com.legalassistant.websearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlSafetyTest {

    @Test
    void allowsPublicHttps() {
        assertTrue(UrlSafety.isSafePublicHttpUrl("https://www.court.gov.cn/zixun/xiangqing/450741.html"));
    }

    @Test
    void blocksLocalhost() {
        assertFalse(UrlSafety.isSafePublicHttpUrl("http://localhost:8080/secret"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafety.assertSafePublicHttpUrl("http://127.0.0.1/"));
    }

    @Test
    void blocksMetadata() {
        assertFalse(UrlSafety.isSafePublicHttpUrl("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void blocksNonHttp() {
        assertFalse(UrlSafety.isSafePublicHttpUrl("file:///etc/passwd"));
    }
}
