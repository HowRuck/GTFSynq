package org.example.gtfsynq.ingest.adapter.inbound.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.example.gtfsynq.ingest.config.HttpTimeouts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GtfsStaticFeedDownloaderTest {

    private static final byte[] ARCHIVE = "fake-gtfs-archive".getBytes(StandardCharsets.UTF_8);

    private static final String ETAG = "\"revision-1\"";

    private static final String LAST_MODIFIED = "Mon, 12 Oct 2026 10:15:00 GMT";

    private final AtomicReference<String> receivedIfNoneMatch = new AtomicReference<>();

    private final AtomicReference<String> receivedIfModifiedSince = new AtomicReference<>();

    private HttpServer server;

    private GtfsStaticFeedDownloader downloader;

    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", this::handleFeed);
        server.createContext("/plain", exchange -> {
            exchange.sendResponseHeaders(200, ARCHIVE.length);
            exchange.getResponseBody().write(ARCHIVE);
            exchange.close();
        });
        server.createContext("/empty", this::handleEmpty);
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.createContext("/not-modified", exchange -> {
            exchange.getResponseHeaders().add("ETag", ETAG);
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });
        server.start();

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(2_000))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        downloader = new GtfsStaticFeedDownloader(httpClient, new HttpTimeouts(2_000, 5_000));
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void capturesValidatorsFromFirstResponse() throws Exception {
        var download = downloader.download("gtfs-test", baseUrl + "/feed", StaticFeedValidators.none());

        var downloaded = assertInstanceOf(StaticFeedDownload.Downloaded.class, download);
        var feed = downloaded.feed();

        try {
            assertEquals(ARCHIVE.length, feed.sizeBytes());
            assertEquals(sha256(ARCHIVE), feed.sha256());
            assertEquals(ETAG, feed.validators().etag());
            assertEquals(LAST_MODIFIED, feed.validators().lastModified());
            assertNotNull(feed.file());
            assertTrue(Files.exists(feed.file()));
        } finally {
            downloader.discard(feed);
        }

        assertNull(receivedIfNoneMatch.get());
        assertNull(receivedIfModifiedSince.get());
    }

    @Test
    void sendsConditionalHeadersAndHandlesNotModified() throws Exception {
        var known = new StaticFeedValidators(ETAG, LAST_MODIFIED);

        var download = downloader.download("gtfs-test", baseUrl + "/feed", known);

        var notModified = assertInstanceOf(StaticFeedDownload.NotModified.class, download);

        assertEquals(ETAG, receivedIfNoneMatch.get());
        assertEquals(LAST_MODIFIED, receivedIfModifiedSince.get());
        assertEquals(ETAG, notModified.validators().etag());
        assertEquals(LAST_MODIFIED, notModified.validators().lastModified());
    }

    @Test
    void keepsKnownValidatorsWhenNotModifiedOmitsThem() {
        var download = downloader.download(
                "gtfs-test", baseUrl + "/not-modified", new StaticFeedValidators(null, LAST_MODIFIED));

        var notModified = assertInstanceOf(StaticFeedDownload.NotModified.class, download);

        // The response refreshed the ETag but omitted Last-Modified, so the known date stays.
        assertEquals(ETAG, notModified.validators().etag());
        assertEquals(LAST_MODIFIED, notModified.validators().lastModified());
    }

    @Test
    void dropsValidatorsTheOriginDidNotRepeatOnAFullResponse() throws Exception {
        var download = downloader.download(
                "gtfs-test",
                baseUrl + "/plain",
                new StaticFeedValidators("\"stale\"", "Tue, 13 Oct 2026 10:15:00 GMT"));

        var downloaded = assertInstanceOf(StaticFeedDownload.Downloaded.class, download);

        try {
            // The server answered 200 without validators, so there is nothing to revalidate
            // against on the next poll.
            assertTrue(downloaded.feed().validators().isEmpty());
        } finally {
            downloader.discard(downloaded.feed());
        }
    }

    @Test
    void rejectsNonSuccessResponses() {
        assertThrows(
                StaticFeedDownloadException.class,
                () -> downloader.download("gtfs-test", baseUrl + "/error", StaticFeedValidators.none()));
    }

    @Test
    void rejectsEmptyArchives() {
        assertThrows(
                StaticFeedDownloadException.class,
                () -> downloader.download("gtfs-test", baseUrl + "/empty", StaticFeedValidators.none()));
    }

    @Test
    void unconditionalDownloadRejectsAnUnexpectedNotModified() {
        assertThrows(
                StaticFeedDownloadException.class, () -> downloader.download("gtfs-test", baseUrl + "/not-modified"));
    }

    private void handleFeed(HttpExchange exchange) throws IOException {
        receivedIfNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
        receivedIfModifiedSince.set(exchange.getRequestHeaders().getFirst("If-Modified-Since"));

        if (ETAG.equals(receivedIfNoneMatch.get())) {
            exchange.getResponseHeaders().add("ETag", ETAG);
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().add("ETag", ETAG);
        exchange.getResponseHeaders().add("Last-Modified", LAST_MODIFIED);
        exchange.sendResponseHeaders(200, ARCHIVE.length);
        exchange.getResponseBody().write(ARCHIVE);
        exchange.close();
    }

    private void handleEmpty(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        exchange.close();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
