package com.notilens;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class State {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static File getFile(String agent, String taskId) {
        String user   = System.getenv("USER") != null ? System.getenv("USER")
                      : System.getenv("USERNAME") != null ? System.getenv("USERNAME") : "";
        String a = agent.replace(File.separatorChar, '_');
        String t = taskId.replace(File.separatorChar, '_');
        return Paths.get(System.getProperty("java.io.tmpdir"),
                "notilens_" + user + "_" + a + "_" + t + ".json").toFile();
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

    public static long calcDuration(File f) {
        Map<String, Object> s = read(f);
        long start = toLong(s.get("start_time"));
        if (start == 0) return 0;
        return nowMs() - start;
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
