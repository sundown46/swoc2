package io.swoc2.app.connections;

import io.swoc2.pluginapi.connection.ConnectionType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * All available connection types: the built-in ones (Spring beans) plus, later, those contributed
 * by plugins (CON-006). Validates each type once at startup; a broken type is skipped, never fatal.
 */
@Component
public class ConnectionTypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTypeRegistry.class);
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,40}");

    private final Map<String, ConnectionType> types = new LinkedHashMap<>();
    private final Map<String, Set<String>> secretFields = new LinkedHashMap<>();
    private final ObjectMapper mapper;

    ConnectionTypeRegistry(List<ConnectionType> builtIn, ObjectMapper mapper) {
        this.mapper = mapper;
        builtIn.forEach(this::register);
    }

    void register(ConnectionType type) {
        try {
            String id = type.id();
            if (id == null || !ID.matcher(id).matches() || types.containsKey(id)) {
                log.error("Skipping connection type with invalid or duplicate id '{}'", id);
                return;
            }
            JsonNode schema = mapper.readTree(type.configSchema());
            java.util.Set<String> secrets = new java.util.HashSet<>();
            schema.path("properties").properties().forEach(e -> {
                if (e.getValue().path("writeOnly").asBoolean(false)) {
                    secrets.add(e.getKey());
                }
            });
            types.put(id, type);
            secretFields.put(id, Set.copyOf(secrets));
        } catch (RuntimeException e) {
            log.error("Skipping broken connection type {}", type.getClass().getName(), e);
        }
    }

    public Optional<ConnectionType> get(String id) {
        return Optional.ofNullable(types.get(id));
    }

    public List<ConnectionType> all() {
        return List.copyOf(types.values());
    }

    /** Config fields of a type that are secrets (schema {@code writeOnly}). */
    Set<String> secretFields(String typeId) {
        return secretFields.getOrDefault(typeId, Set.of());
    }
}
