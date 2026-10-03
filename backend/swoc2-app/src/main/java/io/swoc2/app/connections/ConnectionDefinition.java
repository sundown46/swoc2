package io.swoc2.app.connections;

import io.swoc2.app.settings.InstanceSettings;
import io.swoc2.pluginapi.connection.Direction;
import java.util.Map;
import java.util.UUID;

/**
 * A configured connection as stored and edited by admins (CON-001).
 *
 * @param aging per-connection aging override (PIC-004), or null for the instance default
 */
public record ConnectionDefinition(
        UUID id,
        String name,
        String type,
        boolean enabled,
        Direction direction,
        Map<String, Object> config,
        InstanceSettings.Aging aging) {}
