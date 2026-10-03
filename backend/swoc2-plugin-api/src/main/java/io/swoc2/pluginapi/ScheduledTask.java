package io.swoc2.pluginapi;

import java.time.Duration;

/**
 * Periodic plugin work (PLG-002 "scheduled jobs"). Runs with fixed delay on a core-managed thread,
 * under the same timeout and failure accounting as every other plugin call.
 */
public interface ScheduledTask {

    String name();

    /** Delay between the end of one run and the start of the next; at least 1 s. */
    Duration interval();

    void run() throws Exception;
}
