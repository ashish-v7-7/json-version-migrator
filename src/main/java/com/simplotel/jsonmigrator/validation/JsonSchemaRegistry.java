package com.simplotel.jsonmigrator.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Collects all {@link JsonSchemaDefinition} instances and indexes them by {@code (jsonType, version)}.
 *
 * <p>In a Spring context this is created automatically by the auto-configuration.
 * For non-Spring usage, construct manually:</p>
 * <pre>{@code
 * var registry = new JsonSchemaRegistry(List.of(new CredentialV3Schema()));
 * registry.init();
 * }</pre>
 */
public class JsonSchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaRegistry.class);

    private final List<JsonSchemaDefinition> schemas;

    /** (jsonType → (version → schema)) */
    private final Map<String, TreeMap<Integer, JsonSchemaDefinition>> schemasByType = new HashMap<>();

    public JsonSchemaRegistry(List<JsonSchemaDefinition> schemas) {
        this.schemas = schemas != null ? schemas : Collections.emptyList();
    }

    /**
     * Indexes all schemas. Called automatically by Spring or manually for non-Spring usage.
     *
     * @throws IllegalStateException if duplicate schemas are registered for the same (type, version)
     */
    public void init() {
        schemasByType.clear();

        for (JsonSchemaDefinition schema : schemas) {
            Objects.requireNonNull(schema.jsonType(), "jsonType() must not be null");

            TreeMap<Integer, JsonSchemaDefinition> versions =
                    schemasByType.computeIfAbsent(schema.jsonType(), k -> new TreeMap<>());

            if (versions.containsKey(schema.version())) {
                throw new IllegalStateException(String.format(
                        "Duplicate schema for '%s' v%d: %s and %s",
                        schema.jsonType(), schema.version(),
                        versions.get(schema.version()).getClass().getSimpleName(),
                        schema.getClass().getSimpleName()));
            }

            versions.put(schema.version(), schema);
        }

        for (var entry : schemasByType.entrySet()) {
            log.info("json-migrator: registered schemas for '{}': {}",
                    entry.getKey(),
                    entry.getValue().keySet().stream()
                            .map(v -> "v" + v)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("none"));
        }
    }

    /**
     * Returns the schema for the given type and exact version, or {@code null} if not found.
     */
    public JsonSchemaDefinition getSchema(String type, int version) {
        TreeMap<Integer, JsonSchemaDefinition> versions = schemasByType.get(type);
        if (versions == null) return null;
        return versions.get(version);
    }

    /**
     * Returns the schema for the latest registered version of the given type, or {@code null}.
     */
    public JsonSchemaDefinition getLatestSchema(String type) {
        TreeMap<Integer, JsonSchemaDefinition> versions = schemasByType.get(type);
        if (versions == null || versions.isEmpty()) return null;
        return versions.lastEntry().getValue();
    }

    /**
     * Returns true if at least one schema is registered for the given type.
     */
    public boolean hasSchema(String type) {
        return schemasByType.containsKey(type);
    }

    /**
     * Returns all registered types that have schemas.
     */
    public Set<String> getRegisteredTypes() {
        return Collections.unmodifiableSet(schemasByType.keySet());
    }
}
