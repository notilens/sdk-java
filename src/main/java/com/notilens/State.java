package com.notilens;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class State {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String osUser() {
        String u = System.getenv("USER");
        if (u == null || u.isEmpty()) u = System.getenv("USERNAME");
        if (u == null) u = "";
        return u;
    }

    public static File getFile(String agent, String runId) {
        String user = osUser();
        String a = agent.replace(File.separatorChar, '_');
        String r = runId.replace(File.separatorChar, '_');
        return Paths.get(System.getProperty("java.io.tmpdir"),
                "notilens_" + user + "_" + a + "_" + r + ".json").toFile();
    }

    public static File getPointerFile(String agent, String label) {
        String user       = osUser();
        String safeLabel  = label.replace('/', '_').replace('\\', '_');
        return Paths.get(System.getProperty("java.io.tmpdir"),
                "notilens_" + user + "_" + agent + "_" + safeLabel + ".ptr").toFile();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(File f) {
        if (!f.exists()) return new HashMap<>();
        try {
            return MAPPER.readValue(f, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public static void write(File f, Map<String, Object> state) {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(f, state);
        } catch (Exception ignored) {}
    }

    public static void update(File f, Map<String, Object> updates) {
        Map<String, Object> s = read(f);
        s.putAll(updates);
        write(f, s);
    }

    public static void delete(File f) {
        f.delete();
    }

    public static String readPointer(String agent, String label) {
        File p = getPointerFile(agent, label);
        if (!p.exists()) return "";
        try {
            return new String(Files.readAllBytes(p.toPath())).trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static void writePointer(String agent, String label, String runId) {
        try {
            Files.write(getPointerFile(agent, label).toPath(), runId.getBytes());
        } catch (Exception ignored) {}
    }

    public static void deletePointer(String agent, String label) {
        getPointerFile(agent, label).delete();
    }

    public static void cleanupStale(String agent, int stateTtlSeconds) {
        String user   = osUser();
        String prefix = "notilens_" + user + "_" + agent + "_";
        File   tmp    = new File(System.getProperty("java.io.tmpdir"));
        long   cutoff = System.currentTimeMillis() - (long) stateTtlSeconds * 1000;
        File[] files  = tmp.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!name.startsWith(prefix)) continue;
            if (!name.endsWith(".json") && !name.endsWith(".ptr")) continue;
            if (f.lastModified() < cutoff) f.delete();
        }
    }

    public static long nowMs() {
        return System.currentTimeMillis();
    }

    public static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        return 0;
    }

    public static double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return 0;
    }
}
