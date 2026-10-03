package io.swoc2.app.plugins;

import io.swoc2.pluginapi.PluginContext;
import io.swoc2.pluginapi.PluginEndpoint;
import io.swoc2.pluginapi.ScheduledTask;
import io.swoc2.pluginapi.Swoc2Plugin;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Discovers plugins (ServiceLoader, PLG-004 build-time registration), validates them, starts the
 * enabled ones, schedules their tasks and switches them on and off at runtime (PLG-003). A plugin
 * that cannot even be loaded or started is logged and skipped - it never prevents the application
 * from booting (CLAUDE.md principle 2).
 */
@Component
class PluginRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,40}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9][a-z0-9/-]{0,63}");

    private final Map<String, PluginHandle> plugins = new LinkedHashMap<>();
    private final PluginInvoker invoker;
    private final PluginsProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "plugin-scheduler");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    PluginRegistry(PluginInvoker invoker, PluginsProperties properties) {
        this(invoker, properties, () -> ServiceLoader.load(Swoc2Plugin.class).iterator());
    }

    PluginRegistry(PluginInvoker invoker, PluginsProperties properties, Supplier<Iterator<Swoc2Plugin>> discovery) {
        this.invoker = invoker;
        this.properties = properties;
        discover(discovery.get());
        for (PluginHandle handle : plugins.values()) {
            boolean on =
                    (handle.plugin().enabledByDefault() || properties.enabled().contains(handle.id()))
                            && !properties.disabled().contains(handle.id());
            if (on) {
                enable(handle.id());
            }
        }
    }

    private void discover(Iterator<Swoc2Plugin> iterator) {
        while (true) {
            Swoc2Plugin plugin;
            try {
                if (!iterator.hasNext()) {
                    return;
                }
                plugin = iterator.next();
            } catch (ServiceConfigurationError | RuntimeException | LinkageError broken) {
                // A plugin jar that cannot be instantiated; skip it, keep loading the others.
                log.error("Could not load a plugin, skipping it", broken);
                continue;
            }
            register(plugin);
        }
    }

    private void register(Swoc2Plugin plugin) {
        String id;
        try {
            id = plugin.id();
        } catch (RuntimeException e) {
            log.error(
                    "Plugin {} throws from id(), skipping it", plugin.getClass().getName(), e);
            return;
        }
        if (id == null || !ID.matcher(id).matches()) {
            log.error(
                    "Plugin {} has an invalid id '{}', skipping it",
                    plugin.getClass().getName(),
                    id);
        } else if (plugins.containsKey(id)) {
            log.error(
                    "Duplicate plugin id '{}' ({}), skipping it",
                    id,
                    plugin.getClass().getName());
        } else if (!Swoc2Plugin.SPI_VERSION.equals(plugin.spiVersion())) {
            log.error(
                    "Plugin {} was built for SPI {} but this core is {}, skipping it",
                    id,
                    plugin.spiVersion(),
                    Swoc2Plugin.SPI_VERSION);
        } else {
            plugins.put(id, new PluginHandle(plugin));
            log.info("Discovered plugin {} {} ({})", id, plugin.version(), plugin.name());
        }
    }

    Collection<PluginHandle> all() {
        return plugins.values();
    }

    Optional<PluginHandle> get(String id) {
        return Optional.ofNullable(plugins.get(id));
    }

    /** Starts the plugin (under the invoker) and schedules its tasks. Idempotent. */
    synchronized boolean enable(String id) {
        PluginHandle handle = plugins.get(id);
        if (handle == null) {
            return false;
        }
        if (handle.state() == PluginState.ENABLED) {
            return true;
        }
        PluginContext context = new PluginContext() {
            @Override
            public String pluginId() {
                return id;
            }

            @Override
            public Logger logger() {
                return LoggerFactory.getLogger("plugin." + id);
            }
        };
        try {
            invoker.invoke(
                    handle,
                    "start",
                    () -> {
                        handle.plugin().start(context);
                        return null;
                    },
                    false);
        } catch (PluginCallException startFailed) {
            handle.state(PluginState.FAILED);
            return false;
        }
        handle.state(PluginState.ENABLED);
        // Endpoints and tasks are read once per enable, so routing a request never counts as a
        // plugin call (which would reset the failure counter).
        try {
            handle.endpoints(invoker.invoke(
                    handle, "endpoints", () -> List.copyOf(handle.plugin().endpoints())));
        } catch (PluginCallException e) {
            handle.endpoints(List.of());
        }
        List<ScheduledTask> tasks;
        try {
            tasks = invoker.invoke(
                    handle, "scheduledTasks", () -> List.copyOf(handle.plugin().scheduledTasks()));
        } catch (PluginCallException e) {
            tasks = List.of();
        }
        for (ScheduledTask task : tasks) {
            schedule(handle, task);
        }
        log.info("Plugin {} enabled", id);
        return true;
    }

    private void schedule(PluginHandle handle, ScheduledTask task) {
        long interval;
        String name;
        try {
            Duration d = task.interval();
            interval = Math.max(1000, d == null ? 0 : d.toMillis());
            name = String.valueOf(task.name());
        } catch (RuntimeException e) {
            log.error("Plugin {} has a broken scheduled task, not scheduling it", handle.id(), e);
            return;
        }
        handle.scheduled()
                .add(scheduler.scheduleWithFixedDelay(
                        () -> {
                            try {
                                invoker.invoke(handle, "task " + name, () -> {
                                    task.run();
                                    return null;
                                });
                            } catch (PluginCallException e) {
                                // Already logged and counted by the invoker; the scheduler thread must survive.
                            } catch (RuntimeException unexpected) {
                                log.error("Unexpected error scheduling plugin task {}", name, unexpected);
                            }
                        },
                        interval,
                        interval,
                        TimeUnit.MILLISECONDS));
    }

    /** Stops the plugin and cancels its tasks; state becomes {@code DISABLED}. */
    synchronized boolean disable(String id) {
        PluginHandle handle = plugins.get(id);
        if (handle == null) {
            return false;
        }
        handle.scheduled().forEach(f -> f.cancel(true));
        handle.scheduled().clear();
        if (handle.state() != PluginState.DISABLED) {
            try {
                invoker.invoke(
                        handle,
                        "stop",
                        () -> {
                            handle.plugin().stop();
                            return null;
                        },
                        false);
            } catch (PluginCallException ignored) {
                // stop() failing must not keep the plugin running
            }
        }
        handle.state(PluginState.DISABLED);
        log.info("Plugin {} disabled", id);
        return true;
    }

    /** Finds the endpoint for a request, or empty. */
    Optional<PluginEndpoint> endpoint(PluginHandle handle, PluginEndpoint.Method method, String path) {
        if (!PATH.matcher(path).matches()) {
            return Optional.empty();
        }
        // Endpoints were read once on enable, so routing never counts as a plugin call (which
        // would reset the failure counter). path()/method() are trivial getters, still guarded.
        for (PluginEndpoint e : handle.endpoints()) {
            try {
                if (e.method() == method && path.equals(e.path())) {
                    return Optional.of(e);
                }
            } catch (RuntimeException broken) {
                log.warn("Plugin {} has a broken endpoint definition", handle.id(), broken);
            }
        }
        return Optional.empty();
    }

    @Override
    public void destroy() {
        plugins.keySet().forEach(this::disable);
        scheduler.shutdownNow();
    }
}
