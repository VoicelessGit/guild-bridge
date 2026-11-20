package com.guildbridge.mod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Posts webhook JSON off the render thread, one at a time so Discord keeps message order.
 * The queue is bounded: if Discord is down, old messages are dropped instead of eating memory.
 */
final class WebhookSender {
    private static final int MAX_QUEUED = 100;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final URI url;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED),
            r -> {
                Thread t = new Thread(r, "guildbridge-webhook");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    WebhookSender(String url) {
        this.url = URI.create(url);
    }

    void send(String json) {
        worker.execute(() -> post(json));
    }

    private void post(String json) {
        HttpRequest req = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429) {
                // TODO roadmap step 5: read retry_after from the body, sleep, retry once.
                GuildBridgeClient.LOG.warn("Discord rate limited the webhook: {}", res.body());
            } else if (res.statusCode() >= 300) {
                GuildBridgeClient.LOG.warn("Webhook returned {}: {}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            GuildBridgeClient.LOG.warn("Webhook post failed: {}", e.toString());
        }
    }
}
