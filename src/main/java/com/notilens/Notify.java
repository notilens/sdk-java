package com.notilens;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class Notify {
    public static final String VERSION      = "0.4.0";
    private static final String WEBHOOK_URL = "https://hook.notilens.com/webhook/%s/send";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Shared client — reused across all sends
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Set<String> SUCCESS_EVENTS = new HashSet<>(Arrays.asList(
            "task.completed", "output.generated", "input.approved"));

    private static final Set<String> URGENT_EVENTS = new HashSet<>(Arrays.asList(
            "task.failed", "task.timeout", "task.error", "task.terminated", "output.failed"));

    private static final Set<String> WARNING_EVENTS = new HashSet<>(Arrays.asList(
            "task.retry", "task.cancelled", "task.paused", "task.waiting", "input.required", "input.rejected"));

    private static final Set<String> ACTIONABLE_EVENTS = new HashSet<>(Arrays.asList(
            "task.error", "task.failed", "task.timeout", "task.retry", "task.loop",
            "output.failed", "input.required", "input.rejected"));

    public static String getEventType(String event) {
        if (SUCCESS_EVENTS.contains(event))  return "success";
        if (URGENT_EVENTS.contains(event))   return "urgent";
        if (WARNING_EVENTS.contains(event))  return "warning";
        return "info";
    }

    public static boolean isActionableDefault(String event) {
        return ACTIONABLE_EVENTS.contains(event);
    }

    public static void send(String token, String secret, Map<String, Object> payload) {
        try {
            String url  = String.format(WEBHOOK_URL, token);
            String body = MAPPER.writeValueAsString(payload);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-NOTILENS-KEY", secret)
                    .header("User-Agent", "NotiLens-SDK/" + VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            // Fire-and-forget — never blocks the caller
            CLIENT.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }
}
