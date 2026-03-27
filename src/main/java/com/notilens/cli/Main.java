package com.notilens.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notilens.*;

import java.io.File;
import java.util.*;

public class Main {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        if (args.length < 1) { printUsage(); System.exit(1); }

        String command = args[0];
        String[] rest  = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "init"          -> runInit(rest);
            case "agents"        -> runAgents();
            case "remove-agent"  -> runRemoveAgent(rest);
            case "queue"    -> runTaskQueue(rest);
            case "start"    -> runTaskStart(rest);
            case "progress" -> runWithMsg(rest, "task.progress");
            case "loop"     -> runTaskLoop(rest);
            case "retry"    -> runTaskRetry(rest);
            case "stop"     -> runTaskStop(rest);
            case "pause"    -> runTaskPause(rest);
            case "resume"   -> runTaskResume(rest);
            case "wait"     -> runTaskWait(rest);
            case "error"    -> runTaskError(rest);
            case "fail"     -> runTerminal(rest, "task.failed");
            case "timeout"  -> runTerminal(rest, "task.timeout");
            case "cancel"   -> runTerminal(rest, "task.cancelled");
            case "terminate"-> runTerminal(rest, "task.terminated");
            case "complete" -> runTerminal(rest, "task.completed");
            case "metric"        -> runMetric(rest);
            case "metric.reset"  -> runMetricReset(rest);
            case "output.generate" -> runSimple(rest, "output.generated");
            case "output.fail"   -> runSimple(rest, "output.failed");
            case "input.required"-> runSimple(rest, "input.required");
            case "input.approve" -> runSimple(rest, "input.approved");
            case "input.reject"  -> runSimple(rest, "input.rejected");
            case "track"         -> runEmit(rest);
            case "version"       -> System.out.println("NotiLens v" + Notify.VERSION);
            default              -> { printUsage(); System.exit(1); }
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private static void runInit(String[] args) {
        String agent = "", token = "", secret = "";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--agent"  -> agent  = args[++i];
                case "--token"  -> token  = args[++i];
                case "--secret" -> secret = args[++i];
            }
        }
        if (agent.isEmpty() || token.isEmpty() || secret.isEmpty()) {
            err("Usage: notilens init --agent <name> --token <token> --secret <secret>");
            System.exit(1);
        }
        Config.saveAgent(agent, token, secret);
        System.out.println("✔ Agent '" + agent + "' saved");
    }

    private static void runAgents() {
        List<String> agents = Config.listAgents();
        if (agents.isEmpty()) System.out.println("No agents configured.");
        else agents.forEach(a -> System.out.println("  " + a));
    }

    private static void runRemoveAgent(String[] args) {
        if (args.length == 0) { err("Usage: notilens remove-agent <agent>"); System.exit(1); }
        if (Config.removeAgent(args[0])) System.out.println("✔ Agent '" + args[0] + "' removed");
        else err("Agent '" + args[0] + "' not found");
    }

    private static void runTaskQueue(String[] args) {
        Flags f      = parseFlags(args);
        String runId = genRunId();
        File sf      = State.getFile(f.agent, runId);
        Map<String, Object> s = new HashMap<>();
        s.put("agent",          f.agent);
        s.put("task",           f.taskLabel);
        s.put("run_id",         runId);
        s.put("queued_at",      State.nowMs());
        s.put("retry_count",    0); s.put("loop_count",     0);
        s.put("error_count",    0); s.put("pause_count",    0);
        s.put("wait_count",     0); s.put("pause_total_ms", 0);
        s.put("wait_total_ms",  0);
        State.write(sf, s);
        State.writePointer(f.agent, f.taskLabel, runId);
        sendNotify("task.queued", "Task queued", f, runId);
        System.out.println(runId);
    }

    private static void runTaskStart(String[] args) {
        Flags f = parseFlags(args);
        // Reuse run_id from a prior task.queue if available
        String runId = State.readPointer(f.agent, f.taskLabel);
        if (runId.isEmpty()) runId = genRunId();
        File sf = State.getFile(f.agent, runId);
        Map<String, Object> existing = State.read(sf);
        if (existing.containsKey("queued_at")) {
            State.update(sf, singleMap("start_time", State.nowMs()));
        } else {
            Map<String, Object> s = new HashMap<>();
            s.put("agent",          f.agent);
            s.put("task",           f.taskLabel);
            s.put("run_id",         runId);
            s.put("start_time",     State.nowMs());
            s.put("retry_count",    0); s.put("loop_count",     0);
            s.put("error_count",    0); s.put("pause_count",    0);
            s.put("wait_count",     0); s.put("pause_total_ms", 0);
            s.put("wait_total_ms",  0);
            State.write(sf, s);
        }
        State.writePointer(f.agent, f.taskLabel, runId);
        sendNotify("task.started", "Task started", f, runId);
        System.out.println(runId);
    }

    private static void runWithMsg(String[] args, String event) {
        String[] pos  = positionalArgs(args);
        String msg    = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        sendNotify(event, msg, f, runId);
    }

    private static void runTaskLoop(String[] args) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        File sf = State.getFile(f.agent, runId);
        long count = State.toLong(State.read(sf).get("loop_count")) + 1;
        State.update(sf, singleMap("loop_count", count));
        sendNotify("task.loop", msg, f, runId);
    }

    private static void runTaskRetry(String[] args) {
        Flags f = parseFlags(args);
        String runId = resolveRunId(f);
        File sf = State.getFile(f.agent, runId);
        long count = State.toLong(State.read(sf).get("retry_count")) + 1;
        State.update(sf, singleMap("retry_count", count));
        sendNotify("task.retry", "Retrying task", f, runId);
    }

    private static void runTaskStop(String[] args) {
        Flags f = parseFlags(args);
        String runId = resolveRunId(f);
        sendNotify("task.stopped", "Task stopped", f, runId);
    }

    private static void runTaskPause(String[] args) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        File sf = State.getFile(f.agent, runId);
        Map<String, Object> s = State.read(sf);
        Map<String, Object> u = new HashMap<>();
        u.put("paused_at",   State.nowMs());
        u.put("pause_count", State.toLong(s.get("pause_count")) + 1);
        State.update(sf, u);
        sendNotify("task.paused", msg, f, runId);
    }

    private static void runTaskResume(String[] args) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        File sf = State.getFile(f.agent, runId);
        Map<String, Object> s = State.read(sf);
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
        sendNotify("task.resumed", msg, f, runId);
    }

    private static void runTaskWait(String[] args) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        File sf = State.getFile(f.agent, runId);
        Map<String, Object> s = State.read(sf);
        Map<String, Object> u = new HashMap<>();
        u.put("wait_at",    State.nowMs());
        u.put("wait_count", State.toLong(s.get("wait_count")) + 1);
        State.update(sf, u);
        sendNotify("task.waiting", msg, f, runId);
    }

    private static void runTaskError(String[] args) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        File sf = State.getFile(f.agent, runId);
        Map<String, Object> s = State.read(sf);
        Map<String, Object> u = new HashMap<>();
        u.put("last_error",  msg);
        u.put("error_count", State.toLong(s.get("error_count")) + 1);
        State.update(sf, u);
        sendNotify("task.error", msg, f, runId);
        err("❌ Error: " + msg);
    }

    private static void runTerminal(String[] args, String event) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        sendNotify(event, msg, f, runId);
        State.delete(State.getFile(f.agent, runId));
        State.deletePointer(f.agent, f.taskLabel);
    }

    private static void runSimple(String[] args, String event) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        sendNotify(event, msg, f, runId);
    }

    @SuppressWarnings("unchecked")
    private static void runMetric(String[] args) {
        String[] pos = positionalArgs(args);
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        File sf = State.getFile(f.agent, runId);
        Map<String, Object> state = State.read(sf);
        Map<String, Object> metrics = state.get("metrics") instanceof Map
                ? (Map<String, Object>) state.get("metrics") : new HashMap<>();

        for (String kv : pos) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            try {
                double fv = Double.parseDouble(v);
                Object existing = metrics.get(k);
                if (existing instanceof Number) {
                    metrics.put(k, ((Number) existing).doubleValue() + fv);
                } else {
                    metrics.put(k, fv);
                }
            } catch (NumberFormatException e) {
                metrics.put(k, v);
            }
        }
        State.update(sf, singleMap("metrics", metrics));
        StringBuilder sb = new StringBuilder("📊 Metrics: ");
        metrics.forEach((k, v) -> sb.append(k).append("=").append(v).append(", "));
        System.out.println(sb.toString().replaceAll(", $", ""));
    }

    @SuppressWarnings("unchecked")
    private static void runMetricReset(String[] args) {
        String[] pos = positionalArgs(args);
        Flags f = parseFlags(flagArgs(args));
        String runId = resolveRunId(f);
        File sf = State.getFile(f.agent, runId);
        if (pos.length > 0) {
            Map<String, Object> state = State.read(sf);
            Map<String, Object> metrics = state.get("metrics") instanceof Map
                    ? (Map<String, Object>) state.get("metrics") : new HashMap<>();
            metrics.remove(pos[0]);
            State.update(sf, singleMap("metrics", metrics));
            System.out.println("📊 Metric '" + pos[0] + "' reset");
        } else {
            State.update(sf, singleMap("metrics", new HashMap<>()));
            System.out.println("📊 All metrics reset");
        }
    }

    private static void runEmit(String[] args) {
        if (args.length < 2) { err("Usage: notilens track <event> <message> --agent <agent>"); System.exit(1); }
        String event = args[0];
        String msg   = args[1];
        Flags f = parseFlags(Arrays.copyOfRange(args, 2, args.length));
        // track is agent-level; use pointer if available but don't error if absent
        String runId = State.readPointer(f.agent, f.taskLabel);
        sendNotify(event, msg, f, runId);
        System.out.println("📡 Tracked: " + event);
    }

    // ── Core send ─────────────────────────────────────────────────────────────

    private static void sendNotify(String event, String message, Flags f, String runId) {
        Map<String, String> conf = Config.getAgent(f.agent);
        if (conf == null || conf.getOrDefault("token", "").isEmpty()) {
            err("❌ Agent '" + f.agent + "' not configured. Run: notilens init --agent " + f.agent + " --token TOKEN --secret SECRET");
            System.exit(1);
        }

        File sf = State.getFile(f.agent, runId != null ? runId : "");
        Map<String, Object> state = State.read(sf);

        long now        = State.nowMs();
        long startTime  = State.toLong(state.get("start_time"));
        long queuedAt   = State.toLong(state.get("queued_at"));
        long pauseTotal = State.toLong(state.get("pause_total_ms"));
        long waitTotal  = State.toLong(state.get("wait_total_ms"));
        long pausedAt   = State.toLong(state.get("paused_at")); if (pausedAt > 0) pauseTotal += now - pausedAt;
        long waitAt     = State.toLong(state.get("wait_at"));   if (waitAt   > 0) waitTotal  += now - waitAt;
        long totalMs    = startTime > 0 ? now - startTime : 0;
        long queueMs    = (startTime > 0 && queuedAt > 0) ? startTime - queuedAt : 0;
        long activeMs   = Math.max(0, totalMs - pauseTotal - waitTotal);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("agent", f.agent);
        if (runId != null && !runId.isEmpty()) meta.put("run_id", runId);
        if (!f.taskLabel.isEmpty()) {
            meta.put("task", f.taskLabel);
            if (totalMs    > 0) meta.put("total_duration_ms", totalMs);
            if (queueMs    > 0) meta.put("queue_ms",          queueMs);
            if (pauseTotal > 0) meta.put("pause_ms",          pauseTotal);
            if (waitTotal  > 0) meta.put("wait_ms",           waitTotal);
            if (activeMs   > 0) meta.put("active_ms",         activeMs);
            long rc = State.toLong(state.get("retry_count")); if (rc > 0) meta.put("retry_count", rc);
            long lc = State.toLong(state.get("loop_count"));  if (lc > 0) meta.put("loop_count",  lc);
            long ec = State.toLong(state.get("error_count")); if (ec > 0) meta.put("error_count", ec);
            long pc = State.toLong(state.get("pause_count")); if (pc > 0) meta.put("pause_count", pc);
            long wc = State.toLong(state.get("wait_count"));  if (wc > 0) meta.put("wait_count",  wc);
        }

        Object m = state.get("metrics");
        if (m instanceof Map) ((Map<?, ?>) m).forEach((k, v) -> meta.put(k.toString(), v));
        f.meta.forEach(meta::put);

        String title = f.taskLabel.isEmpty()
                ? f.agent + " | " + event
                : f.agent + " | " + f.taskLabel + " | " + event;

        String evType = List.of("info","success","warning","urgent").contains(f.type)
                ? f.type : Notify.getEventType(event);

        boolean isActionable = f.isActionable.isEmpty()
                ? Notify.isActionableDefault(event)
                : f.isActionable.equalsIgnoreCase("true");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event",         event);
        payload.put("title",         title);
        payload.put("message",       message);
        payload.put("type",          evType);
        payload.put("agent",         f.agent);
        payload.put("task_id",       f.taskLabel);
        payload.put("is_actionable", isActionable);
        payload.put("image_url",     f.imageUrl);
        payload.put("open_url",      f.openUrl);
        payload.put("download_url",  f.downloadUrl);
        payload.put("tags",          f.tags);
        payload.put("ts",            State.nowMs() / 1000.0);
        payload.put("meta",          meta);

        Notify.send(conf.get("token"), conf.get("secret"), payload);
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
    }

    // ── Flag parsing ──────────────────────────────────────────────────────────

    private static class Flags {
        String agent = "", taskLabel = "", type = "", imageUrl = "",
               openUrl = "", downloadUrl = "", tags = "", isActionable = "";
        Map<String, String> meta = new HashMap<>();
    }

    private static Flags parseFlags(String[] args) {
        Flags f = new Flags();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--agent"         -> f.agent        = args[++i];
                case "--task"          -> f.taskLabel    = args[++i];
                case "--type"          -> f.type         = args[++i];
                case "--image_url"     -> f.imageUrl     = args[++i];
                case "--open_url"      -> f.openUrl      = args[++i];
                case "--download_url"  -> f.downloadUrl  = args[++i];
                case "--tags"          -> f.tags         = args[++i];
                case "--is_actionable" -> f.isActionable = args[++i];
                case "--meta" -> {
                    String kv = args[++i];
                    int eq = kv.indexOf('=');
                    if (eq >= 0) f.meta.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
        }
        if (f.agent.isEmpty()) { err("❌ --agent is required"); System.exit(1); }
        return f;
    }

    private static String resolveRunId(Flags f) {
        if (f.taskLabel.isEmpty()) { err("❌ --task is required"); System.exit(1); }
        String runId = State.readPointer(f.agent, f.taskLabel);
        if (runId.isEmpty()) {
            err("❌ No active run for task '" + f.taskLabel + "' on agent '" + f.agent + "'. Run start first.");
            System.exit(1);
        }
        return runId;
    }

    private static String[] positionalArgs(String[] args) {
        List<String> pos = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--")) break;
            pos.add(a);
        }
        return pos.toArray(new String[0]);
    }

    private static String[] flagArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) return Arrays.copyOfRange(args, i, args.length);
        }
        return new String[0];
    }

    private static Map<String, Object> singleMap(String k, Object v) {
        Map<String, Object> m = new HashMap<>(); m.put(k, v); return m;
    }

    private static String genRunId() {
        long ms     = State.nowMs();
        long suffix = (ms ^ (Thread.currentThread().getId() * 0x9e3779b97f4a7c15L)) & 0xFFFFFFFFL;
        return String.format("run_%d_%08x", ms, suffix);
    }

    private static void err(String msg) { System.err.println(msg); }

    // ── Usage ─────────────────────────────────────────────────────────────────

    private static void printUsage() {
        System.out.println("""
Usage:
  notilens init --agent <name> --token <token> --secret <secret>
  notilens agents
  notilens remove-agent <agent>

Task Lifecycle:
  notilens queue           --agent <agent> --task <label>
  notilens start           --agent <agent> --task <label>
  notilens progress  "msg" --agent <agent> --task <label>
  notilens loop      "msg" --agent <agent> --task <label>
  notilens retry           --agent <agent> --task <label>
  notilens stop            --agent <agent> --task <label>
  notilens pause     "msg" --agent <agent> --task <label>
  notilens resume    "msg" --agent <agent> --task <label>
  notilens wait      "msg" --agent <agent> --task <label>
  notilens error     "msg" --agent <agent> --task <label>
  notilens fail      "msg" --agent <agent> --task <label>
  notilens timeout   "msg" --agent <agent> --task <label>
  notilens cancel    "msg" --agent <agent> --task <label>
  notilens terminate "msg" --agent <agent> --task <label>
  notilens complete  "msg" --agent <agent> --task <label>

Output / Input:
  notilens output.generate "msg" --agent <agent> --task <label>
  notilens output.fail     "msg" --agent <agent> --task <label>
  notilens input.required  "msg" --agent <agent> --task <label>
  notilens input.approve   "msg" --agent <agent> --task <label>
  notilens input.reject    "msg" --agent <agent> --task <label>

Metrics:
  notilens metric       tokens=512 cost=0.003 --agent <agent> --task <label>
  notilens metric.reset tokens               --agent <agent> --task <label>
  notilens metric.reset                      --agent <agent> --task <label>

Generic:
  notilens track <event> "msg" --agent <agent>

Options:
  --agent <name>
  --task <label>
  --type success|warning|urgent|info
  --meta key=value   (repeatable)
  --image_url <url>  --open_url <url>  --download_url <url>
  --tags "tag1,tag2"
  --is_actionable true|false

Other:
  notilens version
""");
    }
}
