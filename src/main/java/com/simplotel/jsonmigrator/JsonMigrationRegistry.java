package com.simplotel.jsonmigrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Collects all {@link JsonVersionMigration} instances and indexes them by {@code (jsonType, fromVersion)}.
 * Provides ordered migration chains from any source version to the latest version.
 *
 * <p>In a Spring context this is created automatically by the auto-configuration.
 * For non-Spring usage, construct it manually:</p>
 * <pre>{@code
 * var registry = new JsonMigrationRegistry(List.of(new MyV1ToV2(), new MyV2ToV3()));
 * registry.init(); // validates chain integrity
 * }</pre>
 */
public class JsonMigrationRegistry {

    private static final Logger log = LoggerFactory.getLogger(JsonMigrationRegistry.class);

    private final List<JsonVersionMigration> migrations;

    /** (jsonType → (fromVersion → migration step)) */
    private final Map<String, TreeMap<Integer, JsonVersionMigration>> migrationsByType = new HashMap<>();

    /** jsonType → highest version number */
    private final Map<String, Integer> latestVersionByType = new HashMap<>();

    public JsonMigrationRegistry(List<JsonVersionMigration> migrations) {
        this.migrations = migrations != null ? migrations : Collections.emptyList();
    }

    /**
     * Indexes all migrations and validates chain integrity.
     * Called automatically by Spring ({@code @PostConstruct}) or manually for non-Spring usage.
     *
     * @throws IllegalStateException if the migration chain has gaps or invalid version increments
     */
    public void init() {
        migrationsByType.clear();
        latestVersionByType.clear();

        for (JsonVersionMigration m : migrations) {
            Objects.requireNonNull(m.jsonType(), "jsonType() must not be null");
            if (m.jsonType().isBlank()) {
                throw new IllegalArgumentException("jsonType() must not be blank");
            }

            migrationsByType
                    .computeIfAbsent(m.jsonType(), k -> new TreeMap<>())
                    .put(m.fromVersion(), m);

            latestVersionByType.merge(m.jsonType(), m.toVersion(),
                    (a, b) -> Math.max(a, b));
        }

        for (var entry : migrationsByType.entrySet()) {
            String type = entry.getKey();
            TreeMap<Integer, JsonVersionMigration> chain = entry.getValue();
            validateChain(type, chain);
            log.info("json-migrator: registered {} steps for '{}' (v{} → v{})",
                    chain.size(), type, chain.firstKey(), latestVersionByType.get(type));
        }
    }

    /**
     * Returns the highest registered version for the given type, or {@code 1} if none registered.
     */
    public int getLatestVersion(String type) {
        return latestVersionByType.getOrDefault(type, 1);
    }

    /**
     * Returns all registered JSON types that have at least one migration.
     */
    public Set<String> getRegisteredTypes() {
        return Collections.unmodifiableSet(migrationsByType.keySet());
    }

    /**
     * Returns an ordered list of migration steps from {@code fromVersion} to the latest version.
     * Returns an empty list if already at latest or no migrations registered for the type.
     *
     * @param type        the JSON document type
     * @param fromVersion the current version of the document
     * @return ordered migration steps (may be empty)
     * @throws IllegalStateException if the chain is broken (missing step)
     */
    public List<JsonVersionMigration> getMigrationChain(String type, int fromVersion) {
        TreeMap<Integer, JsonVersionMigration> chain = migrationsByType.get(type);
        if (chain == null) {
            return Collections.emptyList();
        }

        List<JsonVersionMigration> steps = new ArrayList<>();
        int current = fromVersion;
        int latest = getLatestVersion(type);

        while (current < latest) {
            JsonVersionMigration step = chain.get(current);
            if (step == null) {
                throw new IllegalStateException(String.format(
                        "Missing migration step for '%s': v%d → v%d", type, current, current + 1));
            }
            steps.add(step);
            current = step.toVersion();
        }

        return steps;
    }

    private void validateChain(String type, TreeMap<Integer, JsonVersionMigration> chain) {
        int expected = chain.firstKey();
        for (Map.Entry<Integer, JsonVersionMigration> entry : chain.entrySet()) {
            JsonVersionMigration m = entry.getValue();
            if (m.fromVersion() != expected) {
                throw new IllegalStateException(String.format(
                        "Gap in '%s' migration chain: expected fromVersion=%d but got %d",
                        type, expected, m.fromVersion()));
            }
            if (m.toVersion() != m.fromVersion() + 1) {
                throw new IllegalStateException(String.format(
                        "Migration '%s' v%d must go to v%d, not v%d",
                        type, m.fromVersion(), m.fromVersion() + 1, m.toVersion()));
            }
            expected = m.toVersion();
        }
    }
}
