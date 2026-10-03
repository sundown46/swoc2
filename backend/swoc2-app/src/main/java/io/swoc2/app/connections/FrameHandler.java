package io.swoc2.app.connections;

import java.util.Map;

/** Processes received frames of one frame format (e.g. SEDAP-Express lines). Must never throw. */
public interface FrameHandler {

    String frameFormat();

    void handle(ConnectionRuntime connection, String frame, Map<String, String> meta);
}
