package io.swoc2.app.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swoc2.pluginapi.PluginContext;
import io.swoc2.pluginapi.ScheduledTask;
import io.swoc2.pluginapi.Swoc2Plugin;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Isolation guarantees of the plugin host (PLG-003), without Spring. */
class PluginRegistryTest {

    private final PluginsProperties properties = new PluginsProperties(Duration.ofMillis(300), 3, List.of(), List.of());
    private final PluginInvoker invoker = new PluginInvoker(properties);
    private PluginRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.destroy();
        }
        invoker.destroy();
    }

    /** Configurable test plugin. */
    static class TestPlugin implements Swoc2Plugin {
        final String id;
        boolean failStart;
        String spi = SPI_VERSION;
        final AtomicInteger stops = new AtomicInteger();
        List<ScheduledTask> tasks = List.of();

        TestPlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public String spiVersion() {
            return spi;
        }

        @Override
        public void start(PluginContext context) {
            if (failStart) {
                throw new IllegalStateException("start failed");
            }
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
        }

        @Override
        public List<ScheduledTask> scheduledTasks() {
            return tasks;
        }
    }

    private PluginRegistry registry(Swoc2Plugin... plugins) {
        registry =
                new PluginRegistry(invoker, properties, () -> List.of(plugins).iterator());
        return registry;
    }

    @Test
    void crashingCallsAreContainedAndDisableThePluginAfterThreeInARow() {
        PluginRegistry r = registry(new TestPlugin("crashy"));
        PluginHandle h = r.get("crashy").orElseThrow();

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> invoker.invoke(h, "boom", () -> {
                        throw new IllegalStateException("boom");
                    }))
                    .isInstanceOf(PluginCallException.class);
        }

        assertThat(h.state()).isEqualTo(PluginState.FAILED);
        assertThat(h.health().failures()).isEqualTo(3);
        assertThatThrownBy(() -> invoker.invoke(h, "after", () -> "x"))
                .isInstanceOf(PluginCallException.class)
                .extracting(e -> ((PluginCallException) e).reason())
                .isEqualTo(PluginCallException.Reason.NOT_ENABLED);
    }

    @Test
    void errorsLikeStackOverflowAreContainedToo() {
        PluginHandle h = registry(new TestPlugin("deep")).get("deep").orElseThrow();

        assertThatThrownBy(() -> invoker.invoke(h, "recurse", () -> {
                    throw new StackOverflowError("simulated");
                }))
                .isInstanceOf(PluginCallException.class);
        assertThat(h.state()).isEqualTo(PluginState.ENABLED); // one failure is not enough
    }

    @Test
    void hangingCallTimesOut() {
        PluginHandle h = registry(new TestPlugin("slow")).get("slow").orElseThrow();
        long start = System.nanoTime();

        assertThatThrownBy(() -> invoker.invoke(h, "hang", () -> {
                    Thread.sleep(10_000);
                    return null;
                }))
                .extracting(e -> ((PluginCallException) e).reason())
                .isEqualTo(PluginCallException.Reason.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void successResetsTheConsecutiveCount() {
        PluginHandle h = registry(new TestPlugin("flaky")).get("flaky").orElseThrow();
        for (int i = 0; i < 5; i++) {
            try {
                invoker.invoke(h, "fail", () -> {
                    throw new RuntimeException("x");
                });
            } catch (PluginCallException ignored) {
                // expected
            }
            invoker.invoke(h, "ok", () -> "ok");
        }
        assertThat(h.state()).isEqualTo(PluginState.ENABLED);
    }

    @Test
    void brokenPluginsDoNotPreventTheOthersFromLoading() {
        TestPlugin good = new TestPlugin("good");
        TestPlugin badStart = new TestPlugin("bad-start");
        badStart.failStart = true;
        TestPlugin badId = new TestPlugin("Bad Id!");
        TestPlugin duplicate = new TestPlugin("good");
        TestPlugin oldSpi = new TestPlugin("old-spi");
        oldSpi.spi = "0.0";
        Iterator<Swoc2Plugin> discovery = new Iterator<>() {
            final List<Swoc2Plugin> items = List.of(good, badStart, badId, duplicate, oldSpi);
            int i;
            boolean thrown;

            @Override
            public boolean hasNext() {
                return i < items.size() || !thrown;
            }

            @Override
            public Swoc2Plugin next() {
                if (i == 1 && !thrown) {
                    thrown = true;
                    throw new java.util.ServiceConfigurationError("cannot instantiate");
                }
                if (i >= items.size()) {
                    throw new NoSuchElementException();
                }
                return items.get(i++);
            }
        };
        registry = new PluginRegistry(invoker, properties, () -> discovery);

        assertThat(registry.all()).extracting(PluginHandle::id).containsExactly("good", "bad-start");
        assertThat(registry.get("good").orElseThrow().state()).isEqualTo(PluginState.ENABLED);
        assertThat(registry.get("bad-start").orElseThrow().state()).isEqualTo(PluginState.FAILED);
    }

    @Test
    void failingScheduledTaskIsCountedAndDisableStopsIt() throws Exception {
        TestPlugin plugin = new TestPlugin("ticker");
        AtomicInteger runs = new AtomicInteger();
        plugin.tasks = List.of(new ScheduledTask() {
            @Override
            public String name() {
                return "fail";
            }

            @Override
            public Duration interval() {
                return Duration.ofMillis(10); // clamped to 1 s by the host
            }

            @Override
            public void run() {
                runs.incrementAndGet();
                throw new IllegalStateException("task failed");
            }
        });
        PluginRegistry r = registry(plugin);
        PluginHandle h = r.get("ticker").orElseThrow();

        Thread.sleep(3_500);

        assertThat(runs.get()).isGreaterThanOrEqualTo(3);
        assertThat(h.state()).isEqualTo(PluginState.FAILED);
        int afterFailure = runs.get();
        Thread.sleep(1_500);
        assertThat(runs.get()).isEqualTo(afterFailure); // invoker refuses calls once FAILED

        r.disable("ticker");
        assertThat(h.scheduled()).isEmpty();
        assertThat(h.state()).isEqualTo(PluginState.DISABLED);
    }

    @Test
    void configurationCanForcePluginsOnOrOff() {
        TestPlugin on = new TestPlugin("on-by-default");
        Swoc2Plugin offByDefault = new TestPlugin("opt-in") {
            @Override
            public boolean enabledByDefault() {
                return false;
            }
        };
        registry = new PluginRegistry(
                invoker,
                new PluginsProperties(Duration.ofSeconds(1), 3, List.of("opt-in"), List.of("on-by-default")),
                () -> List.<Swoc2Plugin>of(on, offByDefault).iterator());

        assertThat(registry.get("on-by-default").orElseThrow().state()).isEqualTo(PluginState.DISABLED);
        assertThat(registry.get("opt-in").orElseThrow().state()).isEqualTo(PluginState.ENABLED);
    }
}
