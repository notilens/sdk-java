package com.notilens;

import java.io.File;
import java.util.*;

/**
 * NotiLens SDK client.
 *
 * <pre>{@code
 * NotiLens nl  = NotiLens.init("my-agent");
 * NotiLens.Run run = nl.task("report");
 * run.start();
 * run.progress("Processing...");
 * run.complete("Done!");
 * }</pre>
 */
public class NotiLens {

    private final String agent;
    private final String token;
    private final String secret;
    private final int    stateTtl;
    private final Map<String, Object> metrics = new HashMap<>();

    private NotiLens(String agent, String token, String secret, int stateTtl) {
        this.agent    = agent;
        this.token    = token;
        this.secret   = secret;
        this.stateTtl = stateTtl;
        State.cleanupStale(agent, stateTtl);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static NotiLens init(String agent) {
        return init(agent, null, null, 86400);
    }

    public static NotiLens init(String agent, String token, String secret) {
        return init(agent, token, secret, 86400);
    }

    public static NotiLens init(String agent, String token, String secret, int stateTtl) {
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
        return new NotiLens(agent, t, s, stateTtl > 0 ? stateTtl : 86400);
    }

    // ── Task factory ──────────────────────────────────────────────────────────

    /** Create a Run for the given label. Each call gets a unique run_id. */
    public Run task(String label) {
        State.cleanupStale(agent, stateTtl);
        String runId = genRunId();
        return new Run(this, label, runId);
    }

    // ── Agent-level metrics ───────────────────────────────────────────────────

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

    public NotiLens resetMetrics(String key) {
        if (key != null) metrics.remove(key);
        else metrics.clear();
        return this;
    }

    public NotiLens resetMetrics() { return resetMetrics(null); }

    // ── Generic track ─────────────────────────────────────────────────────────

    public void track(String event, String message) {
        sendPayload(event, message, "", "", null, metrics, null);
    }

    public void track(String event, String message, Map<String, Object> meta) {
        sendPayload(event, message, "", "", null, metrics, meta);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getAgent() { return agent; }

    // ── Internal ──────────────────────────────────────────────────────────────

    public void sendPayload(
            String event, String message,
            String runId, String label, File stateFile,
            Map<String, Object> runMetrics, Map<String, Object> extra) {

        String title = (label == null || label.isEmpty())
                ? agent + " | " + event
                : agent + " | " + label + " | " + event;

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("agent", agent);
        if (runId != null && !runId.isEmpty()) meta.put("run_id", runId);
        if (label != null && !label.isEmpty()) meta.put("task",   label);

        if (stateFile != null) {
            Map<String, Object> s = State.read(stateFile);
            long now        = State.nowMs();
            long startTime  = State.toLong(s.get("start_time"));
            long queuedAt   = State.toLong(s.get("queued_at"));
            long pauseTotal = State.toLong(s.get("pause_total_ms"));
            long waitTotal  = State.toLong(s.get("wait_total_ms"));
            long pausedAt   = State.toLong(s.get("paused_at")); if (pausedAt > 0) pauseTotal += now - pausedAt;
            long waitAt     = State.toLong(s.get("wait_at"));   if (waitAt   > 0) waitTotal  += now - waitAt;
            long totalMs    = startTime > 0 ? now - startTime : 0;
            long queueMs    = (startTime > 0 && queuedAt > 0) ? startTime - queuedAt : 0;
            long activeMs   = Math.max(0, totalMs - pauseTotal - waitTotal);

            if (totalMs    > 0) meta.put("total_duration_ms", totalMs);
            if (queueMs    > 0) meta.put("queue_ms",          queueMs);
            if (pauseTotal > 0) meta.put("pause_ms",          pauseTotal);
            if (waitTotal  > 0) meta.put("wait_ms",           waitTotal);
            if (activeMs   > 0) meta.put("active_ms",         activeMs);
            long rc = State.toLong(s.get("retry_count")); if (rc > 0) meta.put("retry_count", rc);
            long lc = State.toLong(s.get("loop_count"));  if (lc > 0) meta.put("loop_count",  lc);
            long ec = State.toLong(s.get("error_count")); if (ec > 0) meta.put("error_count", ec);
            long pc = State.toLong(s.get("pause_count")); if (pc > 0) meta.put("pause_count", pc);
            long wc = State.toLong(s.get("wait_count"));  if (wc > 0) meta.put("wait_count",  wc);
            Object m = s.get("metrics");
            if (m instanceof Map) ((Map<?, ?>) m).forEach((k, v) -> meta.put(k.toString(), v));
        }

        if (runMetrics != null) runMetrics.forEach(meta::put);
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
        payload.put("task_id",       label != null ? label : "");
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

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v != null ? v : def;
    }

    private static String genRunId() {
        long ms     = State.nowMs();
        long suffix = (ms ^ (Thread.currentThread().getId() * 0x9e3779b97f4a7c15L)) & 0xFFFFFFFFL;
        return String.format("run_%d_%08x", ms, suffix);
    }

    // ── Run ───────────────────────────────────────────────────────────────────

    public static class Run {
        private final NotiLens          agent;
        private final String            label;
        public  final String            runId;
        private final File              sf;
        private final Map<String, Object> metrics = new HashMap<>();

        Run(NotiLens agent, String label, String runId) {
            this.agent = agent;
            this.label = label;
            this.runId = runId;
            this.sf    = State.getFile(agent.getAgent(), runId);
        }

        // ── Metrics ───────────────────────────────────────────────────────────

        public Run metric(String key, Object value) {
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

        public Run resetMetrics(String key) {
            if (key != null) metrics.remove(key);
            else metrics.clear();
            return this;
        }

        public Run resetMetrics() { return resetMetrics(null); }

        // ── Lifecycle ─────────────────────────────────────────────────────────

        public Run queue() {
            Map<String, Object> s = new HashMap<>();
            s.put("agent",          agent.getAgent());
            s.put("task",           label);
            s.put("run_id",         runId);
            s.put("queued_at",      State.nowMs());
            s.put("retry_count",    0);
            s.put("loop_count",     0);
            s.put("error_count",    0);
            s.put("pause_count",    0);
            s.put("wait_count",     0);
            s.put("pause_total_ms", 0);
            s.put("wait_total_ms",  0);
            State.write(sf, s);
            send("task.queued", "Task queued", null);
            return this;
        }

        public Run start() {
            Map<String, Object> existing = State.read(sf);
            if (existing.containsKey("queued_at")) {
                State.update(sf, singleMap("start_time", State.nowMs()));
            } else {
                Map<String, Object> s = new HashMap<>();
                s.put("agent",          agent.getAgent());
                s.put("task",           label);
                s.put("run_id",         runId);
                s.put("start_time",     State.nowMs());
                s.put("retry_count",    0);
                s.put("loop_count",     0);
                s.put("error_count",    0);
                s.put("pause_count",    0);
                s.put("wait_count",     0);
                s.put("pause_total_ms", 0);
                s.put("wait_total_ms",  0);
                State.write(sf, s);
            }
            send("task.started", "Task started", null);
            return this;
        }

        public void progress(String message) { send("task.progress", message, null); }

        public void loop(String message) {
            long count = State.toLong(State.read(sf).get("loop_count")) + 1;
            State.update(sf, singleMap("loop_count", count));
            send("task.loop", message, null);
        }

        public void retry() {
            long count = State.toLong(State.read(sf).get("retry_count")) + 1;
            State.update(sf, singleMap("retry_count", count));
            send("task.retry", "Retrying task", null);
        }

        public void pause(String message) {
            Map<String, Object> s = State.read(sf);
            Map<String, Object> u = new HashMap<>();
            u.put("paused_at",   State.nowMs());
            u.put("pause_count", State.toLong(s.get("pause_count")) + 1);
            State.update(sf, u);
            send("task.paused", message, null);
        }

        public void resume(String message) {
            Map<String, Object> s   = State.read(sf);
            long now = State.nowMs();
            Map<String, Object> u = new HashMap<>();
            long pausedAt = State.toLong(s.get("paused_at"));
            if (pausedAt > 0) {
                u.put("pause_total_ms", State.toLong(s.get("pause_total_ms")) + (now - pausedAt));
                u.put("paused_at", null);
            }
            long waitAt = State.toLong(s.get("wait_at"));
            if (waitAt > 0) {
                u.put("wait_total_ms", State.toLong(s.get("wait_total_ms")) + (now - waitAt));
                u.put("wait_at", null);
            }
            if (!u.isEmpty()) State.update(sf, u);
            send("task.resumed", message, null);
        }

        public void wait(String message) {
            Map<String, Object> s = State.read(sf);
            Map<String, Object> u = new HashMap<>();
            u.put("wait_at",    State.nowMs());
            u.put("wait_count", State.toLong(s.get("wait_count")) + 1);
            State.update(sf, u);
            send("task.waiting", message, null);
        }

        public void stop() { send("task.stopped", "Task stopped", null); }

        public void error(String message) {
            Map<String, Object> s = State.read(sf);
            Map<String, Object> u = new HashMap<>();
            u.put("last_error",  message);
            u.put("error_count", State.toLong(s.get("error_count")) + 1);
            State.update(sf, u);
            send("task.error", message, null);
        }

        public void complete(String message)  { send("task.completed",  message, null); terminal(); }
        public void fail(String message)      { send("task.failed",     message, null); terminal(); }
        public void timeout(String message)   { send("task.timeout",    message, null); terminal(); }
        public void cancel(String message)    { send("task.cancelled",  message, null); terminal(); }
        public void terminate(String message) { send("task.terminated", message, null); terminal(); }

        // ── Input / Output ────────────────────────────────────────────────────

        public void inputRequired(String message)   { send("input.required",  message, null); }
        public void inputApproved(String message)   { send("input.approved",  message, null); }
        public void inputRejected(String message)   { send("input.rejected",  message, null); }
        public void outputGenerated(String message) { send("output.generated", message, null); }
        public void outputFailed(String message)    { send("output.failed",   message, null); }

        public void track(String event, String message) { send(event, message, null); }
        public void track(String event, String message, Map<String, Object> meta) { send(event, message, meta); }

        // ── Internals ─────────────────────────────────────────────────────────

        private void send(String event, String message, Map<String, Object> extra) {
            agent.sendPayload(event, message, runId, label, sf, metrics, extra);
        }

        private void terminal() {
            State.delete(sf);
            State.deletePointer(agent.getAgent(), label);
        }

        private static Map<String, Object> singleMap(String k, Object v) {
            Map<String, Object> m = new HashMap<>(); m.put(k, v); return m;
        }
    }
}
