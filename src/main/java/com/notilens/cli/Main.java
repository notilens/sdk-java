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
            case "init"         -> runInit(rest);
            case "agents"       -> runAgents();
            case "remove-agent" -> runRemoveAgent(rest);
            case "task.start"   -> runTaskStart(rest);
            case "task.progress"-> runWithMsg(rest, "task.progress");
            case "task.loop"    -> runTaskLoop(rest);
            case "task.retry"   -> runTaskRetry(rest);
            case "task.stop"    -> runTaskStop(rest);
            case "task.error"   -> runTaskError(rest);
            case "task.fail"    -> runTerminal(rest, "task.failed",    "💥 Failed");
            case "task.timeout" -> runTerminal(rest, "task.timeout",   "⏰ Timeout");
            case "task.cancel"  -> runTerminal(rest, "task.cancelled", "🚫 Cancelled");
            case "task.terminate"->runTerminal(rest, "task.terminated","⚠  Terminated");
            case "task.complete"-> runTerminal(rest, "task.completed", "✅ Completed");
            case "metric"       -> runMetric(rest);
            case "metric.reset" -> runMetricReset(rest);
            case "output.generate"->runSimple(rest, "output.generated");
            case "output.fail"  -> runSimple(rest, "output.failed");
            case "input.required"->runSimple(rest, "input.required");
            case "input.approve"-> runSimple(rest, "input.approved");
            case "input.reject" -> runSimple(rest, "input.rejected");
            case "emit"         -> runEmit(rest);
            case "version"      -> System.out.println("NotiLens v" + Notify.VERSION);
            default             -> { printUsage(); System.exit(1); }
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

    private static void runTaskStart(String[] args) {
        Flags f = parseFlags(args);
        File sf = State.getFile(f.agent, f.taskId);
        Map<String, Object> s = new HashMap<>();
        s.put("agent",       f.agent);
        s.put("task",        f.taskId);
        s.put("start_time",  State.nowMs());
        s.put("retry_count", 0);
        s.put("loop_count",  0);
        State.write(sf, s);
        sendNotify("task.started", "Task started", f);
        System.out.println("▶  Started: " + f.agent + " | " + f.taskId);
    }

    private static void runWithMsg(String[] args, String event) {
        String[] pos  = positionalArgs(args);
        String[] rest = flagArgs(args);
        String msg    = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(rest);
        File sf = State.getFile(f.agent, f.taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        sendNotify(event, msg, f);
        System.out.println("⏳ Progress: " + f.agent + " | " + f.taskId);
    }

    private static void runTaskLoop(String[] args) {
        String[] pos  = positionalArgs(args);
        String msg    = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        File sf = State.getFile(f.agent, f.taskId);
        long count = State.toLong(State.read(sf).get("loop_count")) + 1;
        Map<String, Object> u = new HashMap<>();
        u.put("duration_ms", State.calcDuration(sf));
        u.put("loop_count",  count);
        State.update(sf, u);
        sendNotify("task.loop", msg, f);
        System.out.println("🔄 Loop (" + count + "): " + f.agent + " | " + f.taskId);
    }

    private static void runTaskRetry(String[] args) {
        Flags f = parseFlags(args);
        File sf = State.getFile(f.agent, f.taskId);
        long count = State.toLong(State.read(sf).get("retry_count")) + 1;
        Map<String, Object> u = new HashMap<>();
        u.put("duration_ms",  State.calcDuration(sf));
        u.put("retry_count",  count);
        State.update(sf, u);
        sendNotify("task.retry", "Retrying task", f);
        System.out.println("🔁 Retry: " + f.agent + " | " + f.taskId);
    }

    private static void runTaskStop(String[] args) {
        Flags f  = parseFlags(args);
        File sf  = State.getFile(f.agent, f.taskId);
        long dur = State.calcDuration(sf);
        State.update(sf, map("duration_ms", dur));
        sendNotify("task.stopped", "Task stopped", f);
        System.out.println("⏹  Stopped: " + f.agent + " | " + f.taskId + " (" + dur + " ms)");
    }

    private static void runTaskError(String[] args) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        File sf = State.getFile(f.agent, f.taskId);
        Map<String, Object> u = new HashMap<>();
        u.put("duration_ms", State.calcDuration(sf));
        u.put("last_error",  msg);
        State.update(sf, u);
        sendNotify("task.error", msg, f);
        err("❌ Error: " + msg);
    }

    private static void runTerminal(String[] args, String event, String label) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        Flags f = parseFlags(flagArgs(args));
        File sf = State.getFile(f.agent, f.taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        sendNotify(event, msg, f);
        State.delete(sf);
        System.out.println(label + ": " + f.agent + " | " + f.taskId);
    }

    private static void runSimple(String[] args, String event) {
        String[] pos = positionalArgs(args);
        String msg   = pos.length > 0 ? pos[0] : "";
        sendNotify(event, msg, parseFlags(flagArgs(args)));
    }

    @SuppressWarnings("unchecked")
    private static void runMetric(String[] args) {
        String[] pos = positionalArgs(args);
        Flags f = parseFlags(flagArgs(args));
        File sf = State.getFile(f.agent, f.taskId);
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
        State.update(sf, map("metrics", metrics));
        StringBuilder sb = new StringBuilder("📊 Metrics: ");
        metrics.forEach((k, v) -> sb.append(k).append("=").append(v).append(", "));
        System.out.println(sb.toString().replaceAll(", $", ""));
    }

    @SuppressWarnings("unchecked")
    private static void runMetricReset(String[] args) {
        String[] pos = positionalArgs(args);
        Flags f = parseFlags(flagArgs(args));
        File sf = State.getFile(f.agent, f.taskId);
        if (pos.length > 0) {
            Map<String, Object> state = State.read(sf);
            Map<String, Object> metrics = state.get("metrics") instanceof Map
                    ? (Map<String, Object>) state.get("metrics") : new HashMap<>();
            metrics.remove(pos[0]);
            State.update(sf, map("metrics", metrics));
            System.out.println("📊 Metric '" + pos[0] + "' reset");
        } else {
            State.update(sf, map("metrics", new HashMap<>()));
            System.out.println("📊 All metrics reset");
        }
    }

    private static void runEmit(String[] args) {
        if (args.length < 2) { err("Usage: notilens emit <event> <message> --agent <agent>"); System.exit(1); }
        String event = args[0];
        String msg   = args[1];
        Flags f = parseFlags(Arrays.copyOfRange(args, 2, args.length));
        File sf = State.getFile(f.agent, f.taskId);
        State.update(sf, map("duration_ms", State.calcDuration(sf)));
        sendNotify(event, msg, f);
        System.out.println("📡 Event emitted: " + event);
    }

    // ── Core send ─────────────────────────────────────────────────────────────

    private static void sendNotify(String event, String message, Flags f) {
        Map<String, String> conf = Config.getAgent(f.agent);
        if (conf == null || conf.getOrDefault("token", "").isEmpty()) {
            err("❌ Agent '" + f.agent + "' not configured. Run: notilens init --agent " + f.agent + " --token TOKEN --secret SECRET");
            System.exit(1);
        }

        File sf = State.getFile(f.agent, f.taskId);
        Map<String, Object> state = State.read(sf);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("agent", f.agent);
        long dur = State.toLong(state.get("duration_ms"));
        long rc  = State.toLong(state.get("retry_count"));
        long lc  = State.toLong(state.get("loop_count"));
        if (dur > 0) meta.put("duration_ms", dur);
        if (rc  > 0) meta.put("retry_count", rc);
        if (lc  > 0) meta.put("loop_count",  lc);

        Object m = state.get("metrics");
        if (m instanceof Map) ((Map<?, ?>) m).forEach((k, v) -> meta.put(k.toString(), v));
        f.meta.forEach(meta::put);

        String title = f.agent + " | " + f.taskId + " | " + event;

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
        payload.put("task_id",       f.taskId);
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
        String agent = "", taskId = "", type = "", imageUrl = "",
               openUrl = "", downloadUrl = "", tags = "", isActionable = "";
        Map<String, String> meta = new HashMap<>();
    }

    private static Flags parseFlags(String[] args) {
        Flags f = new Flags();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--agent"         -> f.agent        = args[++i];
                case "--task"          -> f.taskId       = args[++i];
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
        if (f.taskId.isEmpty()) f.taskId = "task_" + State.nowMs();
        return f;
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

    private static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new HashMap<>(); m.put(k, v); return m;
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
  notilens task.start     --agent <agent> [--task <id>]
  notilens task.progress  "msg" --agent <agent> [--task <id>]
  notilens task.loop      "msg" --agent <agent> [--task <id>]
  notilens task.retry           --agent <agent> [--task <id>]
  notilens task.stop            --agent <agent> [--task <id>]
  notilens task.error     "msg" --agent <agent> [--task <id>]
  notilens task.fail      "msg" --agent <agent> [--task <id>]
  notilens task.timeout   "msg" --agent <agent> [--task <id>]
  notilens task.cancel    "msg" --agent <agent> [--task <id>]
  notilens task.terminate "msg" --agent <agent> [--task <id>]
  notilens task.complete  "msg" --agent <agent> [--task <id>]

Output / Input:
  notilens output.generate "msg" --agent <agent> [--task <id>]
  notilens output.fail     "msg" --agent <agent> [--task <id>]
  notilens input.required  "msg" --agent <agent> [--task <id>]
  notilens input.approve   "msg" --agent <agent> [--task <id>]
  notilens input.reject    "msg" --agent <agent> [--task <id>]

Metrics:
  notilens metric       tokens=512 cost=0.003 --agent <agent> --task <id>
  notilens metric.reset tokens               --agent <agent> --task <id>
  notilens metric.reset                      --agent <agent> --task <id>

Generic:
  notilens emit <event> "msg" --agent <agent>

Options:
  --agent <name>
  --task <id>
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
