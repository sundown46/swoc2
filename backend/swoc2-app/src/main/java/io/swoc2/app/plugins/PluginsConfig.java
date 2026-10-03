package io.swoc2.app.plugins;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables {@link PluginsProperties}. */
@Configuration
@EnableConfigurationProperties(PluginsProperties.class)
class PluginsConfig {}
