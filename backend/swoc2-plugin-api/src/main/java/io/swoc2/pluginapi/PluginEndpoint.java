package io.swoc2.pluginapi;

import java.util.Map;

/**
 * One REST endpoint of a plugin, reachable at {@code /api/plugins/{pluginId}/endpoints/{path}}. The core
 * handles authentication, role checks ({@link #requiredRole()}), auditing of non-GET calls, the
 * timeout and error mapping to {@code application/problem+json}; the plugin only computes a result.
 */
public interface PluginEndpoint {

    enum Method {
        GET,
        POST
    }

    /** Path below the plugin root, {@code [a-z0-9-/]{1,64}}, without leading slash. */
    String path();

    Method method();

    /** Minimum role: {@code VIEWER}, {@code OPERATOR}, {@code COMMANDER} or {@code ADMIN}. */
    default String requiredRole() {
        return "VIEWER";
    }

    /**
     * Handles a call. The returned object is serialised as JSON (records, maps, lists, strings,
     * numbers). Throwing is fine: the core turns it into a problem response and counts a failure.
     *
     * @param query query parameters (first value each)
     * @param body request body for POST (at most 64 KiB), else empty
     */
    Object handle(Map<String, String> query, String body) throws Exception;
}
