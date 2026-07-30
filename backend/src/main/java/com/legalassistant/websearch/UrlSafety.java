package com.legalassistant.websearch;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 抓取 URL 安全校验（对齐 grok-build web_fetch SSRF 防护）。
 */
public final class UrlSafety {

    private UrlSafety() {
    }

    public static void assertSafePublicHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url empty");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid url: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("only http(s) allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("missing host");
        }
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost") || "metadata.google.internal".equals(h)) {
            throw new IllegalArgumentException("blocked host: " + host);
        }
        if ("169.254.169.254".equals(h) || "metadata".equals(h)) {
            throw new IllegalArgumentException("blocked metadata host");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isBlockedAddress(addr)) {
                    throw new IllegalArgumentException("blocked private/link-local address: " + addr.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("unresolvable host: " + host, e);
        }
    }

    public static boolean isSafePublicHttpUrl(String url) {
        try {
            assertSafePublicHttpUrl(url);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        return addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress();
    }
}
