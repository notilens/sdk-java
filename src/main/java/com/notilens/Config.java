package com.notilens;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class Config {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static File configFile() {
        return Paths.get(System.getProperty("user.home"), ".notilens_config.json").toFile();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> load() {
        File f = configFile();
        if (!f.exists()) return new HashMap<>();
        try {
            return MAPPER.readValue(f, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static void save(Map<String, Map<String, String>> cfg) {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(configFile(), cfg);
        } catch (Exception ignored) {}
    }

    public static void saveAgent(String agent, String token, String secret) {
        Map<String, Map<String, String>> cfg = load();
        Map<String, String> entry = new HashMap<>();
        entry.put("token", token);
        entry.put("secret", secret);
        cfg.put(agent, entry);
        save(cfg);
    }

    public static Map<String, String> getAgent(String agent) {
        return load().get(agent);
    }

    public static boolean removeAgent(String agent) {
        Map<String, Map<String, String>> cfg = load();
        if (!cfg.containsKey(agent)) return false;
        cfg.remove(agent);
        save(cfg);
        return true;
    }

    public static List<String> listAgents() {
        return new ArrayList<>(load().keySet());
    }
}
