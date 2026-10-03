package io.swoc2.app.plugins;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Plugin host settings ({@code SWOC2_PLUGINS_*}).
 *
 * @param callTimeout maximum duration of one plugin call (lifecycle, endpoint, scheduled run)
 * @param maxConsecutiveFailures after this many failed calls in a row the plugin is disabled
 *     automatically (state {@code FAILED}) until an admin re-enables it
 * @param enabled plugin ids to enable at startup even if they are off by default
 * @param disabled plugin ids to keep off at startup
 */
@ConfigurationProperties("swoc2.plugins")
record PluginsProperties(
        @DefaultValue("5s") Duration callTimeout,
        @DefaultValue("3") int maxConsecutiveFailures,
        @DefaultValue List<String> enabled,
        @DefaultValue List<String> disabled) {}
