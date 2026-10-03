package io.swoc2.app.connections.transport;

import java.util.Map;

/** Reading and checking untrusted connection config values (they come from the admin API). */
final class ConfigValues {

    private ConfigValues() {}

    static final int DEFAULT_SEDAP_PORT = 50000; // ICD §4

    static String host(Map<String, Object> config, String key, Map<String, String> errors) {
        Object v = config.get(key);
        if (!(v instanceof String s) || s.isBlank() || s.length() > 253 || !s.matches("[A-Za-z0-9.:\\-\\[\\]]+")) {
            errors.put(key, "must be a host name or IP address");
            return null;
        }
        return s.strip();
    }

    static int port(Map<String, Object> config, String key, Map<String, String> errors) {
        Object v = config.getOrDefault(key, DEFAULT_SEDAP_PORT);
        if (v instanceof Number n && n.doubleValue() == n.intValue() && n.intValue() >= 1 && n.intValue() <= 65535) {
            return n.intValue();
        }
        errors.put(key, "must be a port number 1-65535");
        return -1;
    }

    static int positiveInt(
            Map<String, Object> config, String key, int defaultValue, int max, Map<String, String> errors) {
        Object v = config.getOrDefault(key, defaultValue);
        if (v instanceof Number n && n.doubleValue() == n.intValue() && n.intValue() >= 1 && n.intValue() <= max) {
            return n.intValue();
        }
        errors.put(key, "must be a whole number 1-" + max);
        return defaultValue;
    }

    static String optionalText(Map<String, Object> config, String key, int maxLength, Map<String, String> errors) {
        Object v = config.get(key);
        if (v == null || (v instanceof String s && s.isEmpty())) {
            return null;
        }
        if (!(v instanceof String s) || s.length() > maxLength) {
            errors.put(key, "must be text of at most " + maxLength + " characters");
            return null;
        }
        return s;
    }
}
