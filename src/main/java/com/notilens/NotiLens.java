package com.notilens;

import java.io.File;
import java.util.*;

/**
 * NotiLens SDK client.
 *
 * <pre>{@code
 * NotiLens nl = NotiLens.init("my-agent");
 * String taskId = nl.taskStart();
 * nl.taskProgress("Processing...", taskId);
 * nl.taskComplete("Done!", taskId);
 * }</pre>
 */
public class NotiLens {

    private final String agent;
    private final String token;
    private final String secret;
    private final Map<String, Object> metrics = new HashMap<>();

    private NotiLens(String agent, String token, String secret) {
        this.agent  = agent;
        this.token  = token;
        this.secret = secret;
    }

    /**
     * Create a NotiLens client.
     * Credentials resolved: token/secret args → env vars → saved CLI config.
     */
    public static NotiLens init(String agent) {
        return init(agent, null, null);
    }

    public static NotiLens init(String agent, String token, String secret) {
        String t = token  != null ? token  : "";
        String s = secret != null ? secret : "";

        if (t.isEmpty()) t = envOr("NOTILENS_TOKEN", "");
        if (s.isEmpty()) s = envOr("NOTILENS_SECRET", "");

        if (t.isEmpty() || s.isEmpty()) {
            Map<String, String> conf = Config.getAgent(agent);
            if (conf != null) {
                if (t.isEmpty()) t = conf.getOrDefault("token", "");
                if (s.isEmpty()) s = conf.getOrDefault("secret", "");
            }
        }

        if (t.isEmpty() || s.isEmpty()) {
            throw new IllegalStateException(
                "NotiLens: token and secret are required. Pass them directly, " +
                "set NOTILENS_TOKEN/NOTILENS_SECRET env vars, or run: " +
                "notilens init --agent " + agent + " --token TOKEN --secret SECRET"
            );
        }
        return new NotiLens(agent, t, s);
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    /** Set a metric. Numeric values accumulate; strings are replaced. */
    public NotiLens metric(String key, Object value) {
        if (value instanceof Number) {
            double fv = ((Number) value).doubleValue();
            Object existing = metrics.get(key);
            if (existing instanceof Number) {
                metrics.put(key, ((Number) existing).doubleValue() + fv);
                return this;
            }
        }
        metrics.put(key, value);
        return this;
    }

    /** Reset one metric by key, or all metrics if key is null. */
    public NotiLens resetMetrics(String key) {
        if (key != null) metrics.remove(key);
        else metrics.clear();
        return this;
    }

    public NotiLens resetMetrics() {
        return resetMetrics(null);
    }

    // ── Task lifecycle ────────────────────────────────────────────────────────

    public String taskStart() {
        return taskStart(null);
    }

    public String taskStart(String taskId) {
        String id = (taskId == null || taskId.isEmpty())
                ? "task_" + State.nowMs() : taskId;
        File sf = State.getFile(agent, id);
        Map<String, Object> s = new HashMap<>();
        s.put("agent",       agent);
        s.put("task",        id);
        s.put("start_time",  State.nowMs());
        s.put("retry_count", 0);
        s.put("loop_count",  0);
        State.write(sf, s);
        send("task.started", "Task started", id, null);
        return id;
    }

    public void taskProgress(String message, String taskId) {
        File sf = State.getFile(agent, taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        send("task.progress", message, taskId, null);
    }

    public void taskLoop(String message, String taskId) {
        File sf    = State.getFile(agent, taskId);
        long count = State.toLong(State.read(sf).get("loop_count")) + 1;
        Map<String, Object> u = new HashMap<>();
        u.put("duration_ms", State.calcDuration(sf));
        u.put("loop_count",  count);
        State.update(sf, u);
        send("task.loop", message, taskId, null);
    }

    public void taskRetry(String taskId) {
        File sf    = State.getFile(agent, taskId);
        long count = State.toLong(State.read(sf).get("retry_count")) + 1;
        Map<String, Object> u = new HashMap<>();
        u.put("duration_ms",  State.calcDuration(sf));
        u.put("retry_count",  count);
        State.update(sf, u);
        send("task.retry", "Retrying task", taskId, null);
    }

    public void taskError(String message, String taskId) {
        File sf = State.getFile(agent, taskId);
        Map<String, Object> u = new HashMap<>();
        u.put("duration_ms", State.calcDuration(sf));
        u.put("last_error",  message);
        State.update(sf, u);
        send("task.error", message, taskId, null);
    }

    public void taskComplete(String message, String taskId) {
        File sf = State.getFile(agent, taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        send("task.completed", message, taskId, null);
        State.delete(sf);
    }

    public void taskFail(String message, String taskId) {
        File sf = State.getFile(agent, taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        send("task.failed", message, taskId, null);
        State.delete(sf);
    }

    public void taskTimeout(String message, String taskId) {
        File sf = State.getFile(agent, taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        send("task.timeout", message, taskId, null);
        State.delete(sf);
    }

    public void taskCancel(String message, String taskId) {
        File sf = State.getFile(agent, taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        send("task.cancelled", message, taskId, null);
        State.delete(sf);
    }

    public void taskStop(String taskId) {
        File sf = State.getFile(agent, taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        send("task.stopped", "Task stopped", taskId, null);
    }

    public void taskTerminate(String message, String taskId) {
        File sf = State.getFile(agent, taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        send("task.terminated", message, taskId, null);
        State.delete(sf);
    }

    // ── Input events ──────────────────────────────────────────────────────────

    public void inputRequired(String message, String taskId) {
        send("input.required", message, taskId, null);
    }

    public void inputApproved(String message, String taskId) {
        send("input.approved", message, taskId, null);
    }

    public void inputRejected(String message, String taskId) {
        send("input.rejected", message, taskId, null);
    }

    // ── Output events ─────────────────────────────────────────────────────────

    public void outputGenerated(String message, String taskId) {
        send("output.generated", message, taskId, null);
    }

    public void outputFailed(String message, String taskId) {
        send("output.failed", message, taskId, null);
    }

    // ── Generic emit ──────────────────────────────────────────────────────────

    public void emit(String event, String message) {
        send(event, message, "", null);
    }

    public void emit(String event, String message, Map<String, Object> meta) {
        send(event, message, "", meta);
    }

    // ── Internal send ─────────────────────────────────────────────────────────

    private void send(String event, String message, String taskId, Map<String, Object> extra) {
        String title = (taskId == null || taskId.isEmpty())
                ? agent + " | " + event
                : agent + " | " + taskId + " | " + event;

        long duration = 0, retryCount = 0, loopCount = 0;
        if (taskId != null && !taskId.isEmpty()) {
            File sf = State.getFile(agent, taskId);
            Map<String, Object> s = State.read(sf);
            duration    = State.toLong(s.get("duration_ms"));
            retryCount  = State.toLong(s.get("retry_count"));
            loopCount   = State.toLong(s.get("loop_count"));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("agent", agent);
        if (duration    > 0) meta.put("duration_ms",  duration);
        if (retryCount  > 0) meta.put("retry_count",  retryCount);
        if (loopCount   > 0) meta.put("loop_count",   loopCount);
        metrics.forEach(meta::put);
        if (extra != null) meta.putAll(extra);

        String imageUrl    = popString(meta, "image_url");
        String openUrl     = popString(meta, "open_url");
        String downloadUrl = popString(meta, "download_url");
        String tags        = popString(meta, "tags");

        boolean isActionable = Notify.isActionableDefault(event);
        if (meta.containsKey("is_actionable")) {
            Object v = meta.remove("is_actionable");
            if (v instanceof Boolean) isActionable = (Boolean) v;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event",         event);
        payload.put("title",         title);
        payload.put("message",       message);
        payload.put("type",          Notify.getEventType(event));
        payload.put("agent",         agent);
        payload.put("task_id",       taskId != null ? taskId : "");
        payload.put("is_actionable", isActionable);
        payload.put("image_url",     imageUrl);
        payload.put("open_url",      openUrl);
        payload.put("download_url",  downloadUrl);
        payload.put("tags",          tags);
        payload.put("ts",            State.nowMs() / 1000.0);
        payload.put("meta",          meta);

        Notify.send(token, secret, payload);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String popString(Map<String, Object> meta, String key) {
        Object v = meta.remove(key);
        return v instanceof String ? (String) v : "";
    }

    private static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v != null ? v : def;
    }
}
