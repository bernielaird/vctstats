package dev.bernie.vct.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;

/**
 * The only class allowed to talk to vlr.gg.
 *
 * Every request goes through {@link #fetch} or {@link #fetchFresh}, which:
 *   1. return the cached copy if we've already downloaded the page ({@code fetch} only),
 *   2. wait a randomized delay before each real request (politeness),
 *   3. retry with exponential backoff on 429 / 5xx responses,
 *   4. write the raw HTML to the cache before returning it ({@code fetch} only).
 */
public class Fetcher {

    private static final int MAX_ATTEMPTS = 4;

    private final HttpClient client;
    private final String userAgent;
    private final long minDelayMs;
    private final long jitterMs;
    private final Random random = new Random();

    public Fetcher(String userAgent, long minDelayMs, long jitterMs) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.userAgent = userAgent;
        this.minDelayMs = minDelayMs;
        this.jitterMs = jitterMs;
    }

    /** Fetch a URL, using (and filling) the on-disk cache. */
    public String fetch(String url, Path cacheFile) throws IOException, InterruptedException {
        if (Files.exists(cacheFile)) {
            return Files.readString(cacheFile);
        }
        String body = fetchFresh(url);
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, body);
        return body;
    }

    /** Always downloads, never touches the cache. For pages that change, like results page 1. */
    public String fetchFresh(String url) throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Thread.sleep(minDelayMs + (long) (random.nextDouble() * jitterMs));

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                // Network hiccup: treat like a retryable error.
                lastError = e;
                backoff(attempt);
                continue;
            }

            int status = response.statusCode();
            if (status == 200) {
                return response.body();
            }
            if (status == 429 || status >= 500) {
                lastError = new IOException("HTTP " + status + " for " + url);
                backoff(attempt);
                continue;
            }
            // 404 and other 4xx: retrying won't help.
            throw new IOException("HTTP " + status + " for " + url);
        }
        throw new IOException("Gave up after " + MAX_ATTEMPTS + " attempts: " + url, lastError);
    }

    /** 5s, 15s, 45s, ... */
    private void backoff(int attempt) throws InterruptedException {
        long waitMs = (long) (5_000 * Math.pow(3, attempt));
        System.err.printf("  backing off %ds%n", waitMs / 1000);
        Thread.sleep(waitMs);
    }
}
