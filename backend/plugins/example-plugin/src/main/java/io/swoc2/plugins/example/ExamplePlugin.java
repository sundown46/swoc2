package io.swoc2.plugins.example;

import io.swoc2.pluginapi.PluginContext;
import io.swoc2.pluginapi.PluginEndpoint;
import io.swoc2.pluginapi.ScheduledTask;
import io.swoc2.pluginapi.Swoc2Plugin;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example plugin (ROADMAP P0 item 10). Endpoints under {@code /api/plugins/example/endpoints/}:
 *
 * <ul>
 *   <li>{@code GET hello} - normal call, returns a greeting and the tick counter
 *   <li>{@code GET crash} - throws, to show the exception barrier and automatic disable
 *   <li>{@code GET hang} - blocks far longer than the call timeout, to show the timeout
 * </ul>
 *
 * A scheduled task increments a counter every 5 s.
 */
public final class ExamplePlugin implements Swoc2Plugin {

    private final AtomicLong ticks = new AtomicLong();
    private PluginContext context;

    @Override
    public String id() {
        return "example";
    }

    @Override
    public String name() {
        return "Example plugin";
    }

    @Override
    public String version() {
        return "0.1.0";
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public void start(PluginContext pluginContext) {
        this.context = pluginContext;
        context.logger().info("Example plugin started");
    }

    @Override
    public List<PluginEndpoint> endpoints() {
        return List.of(
                endpoint("hello", query -> Map.of("message", "Hello from the example plugin", "ticks", ticks.get())),
                endpoint("crash", query -> {
                    throw new IllegalStateException("Deliberate crash in the example plugin");
                }),
                endpoint("hang", query -> {
                    Thread.sleep(Duration.ofMinutes(5));
                    return Map.of();
                }));
    }

    @Override
    public List<ScheduledTask> scheduledTasks() {
        return List.of(new ScheduledTask() {
            @Override
            public String name() {
                return "tick";
            }

            @Override
            public Duration interval() {
                return Duration.ofSeconds(5);
            }

            @Override
            public void run() {
                ticks.incrementAndGet();
            }
        });
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(Map<String, String> query) throws Exception;
    }

    private static PluginEndpoint endpoint(String path, Handler handler) {
        return new PluginEndpoint() {
            @Override
            public String path() {
                return path;
            }

            @Override
            public Method method() {
                return Method.GET;
            }

            @Override
            public Object handle(Map<String, String> query, String body) throws Exception {
                return handler.handle(query);
            }
        };
    }
}
