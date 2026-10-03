package io.swoc2.app.plugins;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The exception barrier around every plugin call (ARCHITECTURE §8.2, PLG-003): runs the call on a
 * virtual thread, waits at most the configured timeout, converts any throwable (including
 * {@link Error}s like {@code StackOverflowError}) into a {@link PluginCallException}, counts
 * failures and disables the plugin after too many in a row. Nothing a plugin does can propagate
 * into the caller beyond that exception.
 */
@Component
class PluginInvoker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PluginInvoker.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final PluginsProperties properties;

    PluginInvoker(PluginsProperties properties) {
        this.properties = properties;
    }

    <T> T invoke(PluginHandle handle, String what, Callable<T> call) {
        return invoke(handle, what, call, true);
    }

    /**
     * @param requireEnabled false only for lifecycle calls ({@code start}) that run before the
     *     plugin is marked enabled
     */
    <T> T invoke(PluginHandle handle, String what, Callable<T> call, boolean requireEnabled) {
        if (requireEnabled && handle.state() != PluginState.ENABLED) {
            throw new PluginCallException(
                    PluginCallException.Reason.NOT_ENABLED, "Plugin " + handle.id() + " is not enabled", null);
        }
        Future<T> future = executor.submit(call);
        try {
            T result = future.get(properties.callTimeout().toMillis(), TimeUnit.MILLISECONDS);
            handle.recordSuccess();
            return result;
        } catch (TimeoutException timeout) {
            future.cancel(true);
            fail(handle, what, "timed out after " + properties.callTimeout().toMillis() + " ms", null);
            throw new PluginCallException(
                    PluginCallException.Reason.TIMEOUT, "Plugin " + handle.id() + " timed out", timeout);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            fail(handle, what, cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
            throw new PluginCallException(
                    PluginCallException.Reason.FAILED, "Plugin " + handle.id() + " failed", cause);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PluginCallException(PluginCallException.Reason.FAILED, "Interrupted", interrupted);
        }
    }

    private void fail(PluginHandle handle, String what, String error, Throwable cause) {
        int inARow = handle.recordFailure(what + ": " + error);
        log.warn("Plugin {} failed in {} ({} in a row): {}", handle.id(), what, inARow, error, cause);
        if (inARow >= properties.maxConsecutiveFailures() && handle.state() == PluginState.ENABLED) {
            handle.state(PluginState.FAILED);
            log.error(
                    "Plugin {} disabled automatically after {} consecutive failures; an admin can re-enable it",
                    handle.id(),
                    inARow);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
