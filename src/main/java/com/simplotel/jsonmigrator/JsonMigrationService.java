package com.simplotel.jsonmigrator;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.simplotel.jsonmigrator.validation.JsonValidationResult;
import com.simplotel.jsonmigrator.validation.JsonValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The main entry point for migrating versioned JSON strings to the latest schema.
 *
 * <p><b>Spring Boot usage</b> (auto-configured):</p>
 * <pre>{@code
 * @Autowired
 * private JsonMigrationService migrationService;
 *
 * String migrated = migrationService.migrateToLatest("CREDENTIAL", rawJson);
 * }</pre>
 *
 * <p><b>Non-Spring usage</b> (manual setup):</p>
 * <pre>{@code
 * var registry = new JsonMigrationRegistry(List.of(new V1ToV2(), new V2ToV3()));
 * registry.init();
 * var service = new JsonMigrationService(registry);
 *
 * String migrated = service.migrateToLatest("CREDENTIAL", rawJson);
 * }</pre>
 */
public class JsonMigrationService {

    private static final Logger log = LoggerFactory.getLogger(JsonMigrationService.class);

    private static final Configuration JSON_PATH_CONFIG = Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    private final JsonMigrationRegistry registry;
    private final String versionJsonPath;
    private JsonValidationService validationService;

    /**
     * Creates a migration service with the default version path {@code $.version}.
     *
     * @param registry the migration registry (must be initialized)
     */
    public JsonMigrationService(JsonMigrationRegistry registry) {
        this(registry, "$.version");
    }

    /**
     * Creates a migration service with a custom version field path.
     *
     * @param registry        the migration registry (must be initialized)
     * @param versionJsonPath the JsonPath expression to the version field (e.g., {@code "$.schema_version"})
     */
    public JsonMigrationService(JsonMigrationRegistry registry, String versionJsonPath) {
        this.registry = registry;
        this.versionJsonPath = versionJsonPath;
    }

    /**
     * Sets the validation service for {@link #migrateAndValidate(String, String)}.
     * Called automatically by Spring auto-configuration, or manually for non-Spring usage.
     */
    public void setValidationService(JsonValidationService validationService) {
        this.validationService = validationService;
    }

    /**
     * Migrates the given JSON string to the latest version for the specified type.
     * Returns the JSON unchanged if already at latest, or if {@code null}/blank.
     *
     * @param type the JSON document type (must match values returned by your {@link JsonVersionMigration#jsonType()})
     * @param json the raw JSON string from the database
     * @return the migrated JSON string, or the original if no migration was needed
     */
    public String migrateToLatest(String type, String json) {
        if (json == null || json.isBlank()) {
            return json;
        }

        DocumentContext doc = JsonPath.using(JSON_PATH_CONFIG).parse(json);
        int currentVersion = readVersion(doc);
        int latestVersion = registry.getLatestVersion(type);

        if (currentVersion >= latestVersion) {
            return json;
        }

        log.info("json-migrator: migrating '{}' from v{} to v{}", type, currentVersion, latestVersion);

        List<JsonVersionMigration> steps = registry.getMigrationChain(type, currentVersion);
        for (JsonVersionMigration step : steps) {
            log.debug("json-migrator: applying '{}' v{} → v{}", type, step.fromVersion(), step.toVersion());
            step.migrate(doc);
        }

        return doc.jsonString();
    }

    /**
     * Migrates to a specific target version (not necessarily the latest).
     *
     * @param type          the JSON document type
     * @param json          the raw JSON string
     * @param targetVersion the version to migrate to
     * @return the migrated JSON string
     * @throws IllegalArgumentException if targetVersion is less than the current version
     */
    public String migrateToVersion(String type, String json, int targetVersion) {
        if (json == null || json.isBlank()) {
            return json;
        }

        DocumentContext doc = JsonPath.using(JSON_PATH_CONFIG).parse(json);
        int currentVersion = readVersion(doc);

        if (currentVersion >= targetVersion) {
            return json;
        }

        List<JsonVersionMigration> allSteps = registry.getMigrationChain(type, currentVersion);
        for (JsonVersionMigration step : allSteps) {
            if (step.fromVersion() >= targetVersion) {
                break;
            }
            step.migrate(doc);
        }

        return doc.jsonString();
    }

    /**
     * Checks whether the given JSON needs migration (i.e., is not at the latest version).
     */
    public boolean needsMigration(String type, String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        DocumentContext doc = JsonPath.using(JSON_PATH_CONFIG).parse(json);
        return readVersion(doc) < registry.getLatestVersion(type);
    }

    /**
     * Reads the current version from the JSON document.
     * Exposed for consumers who need to check the version without migrating.
     *
     * @param json the raw JSON string
     * @return the parsed version number (defaults to 1 if absent or unparseable)
     */
    public int getCurrentVersion(String json) {
        if (json == null || json.isBlank()) {
            return 1;
        }
        DocumentContext doc = JsonPath.using(JSON_PATH_CONFIG).parse(json);
        return readVersion(doc);
    }

    // ── Migration + Validation (combined) ──

    /**
     * Migrates JSON to latest version, then validates it against the registered schema.
     * Returns a result containing the migrated JSON and any validation errors.
     *
     * <p>If no {@link JsonValidationService} is configured or no schema is registered
     * for the type, validation is skipped and the result is always valid.</p>
     *
     * @param type the JSON document type
     * @param json the raw JSON string
     * @return the migration + validation result
     */
    public MigrationResult migrateAndValidate(String type, String json) {
        String migrated = migrateToLatest(type, json);

        if (validationService == null || migrated == null || migrated.isBlank()) {
            return new MigrationResult(migrated, JsonValidationResult.valid());
        }

        JsonValidationResult validation = validationService.validate(type, migrated);
        return new MigrationResult(migrated, validation);
    }

    /**
     * Migrates to latest and throws if validation fails.
     *
     * @param type the JSON document type
     * @param json the raw JSON string
     * @return the migrated JSON string
     * @throws com.simplotel.jsonmigrator.validation.JsonValidationException if validation fails
     */
    public String migrateValidateOrThrow(String type, String json) {
        MigrationResult result = migrateAndValidate(type, json);
        result.getValidation().throwIfInvalid(type);
        return result.getJson();
    }

    /**
     * Holds the result of migration + validation together.
     */
    public static class MigrationResult {
        private final String json;
        private final JsonValidationResult validation;

        public MigrationResult(String json, JsonValidationResult validation) {
            this.json = json;
            this.validation = validation;
        }

        /** The migrated JSON string. */
        public String getJson() { return json; }

        /** The validation result (check {@code isValid()} before using the JSON). */
        public JsonValidationResult getValidation() { return validation; }

        /** Shorthand: true if validation passed or was skipped. */
        public boolean isValid() { return validation.isValid(); }
    }

    private int readVersion(DocumentContext doc) {
        Object versionObj = doc.read(versionJsonPath);
        if (versionObj == null) {
            return 1;
        }

        if (versionObj instanceof Number num) {
            return num.intValue();
        }

        String versionStr = versionObj.toString();
        try {
            if (versionStr.contains(".")) {
                return Integer.parseInt(versionStr.split("\\.")[0]);
            }
            return Integer.parseInt(versionStr);
        } catch (NumberFormatException e) {
            log.warn("json-migrator: unparseable version '{}', treating as v1", versionStr);
            return 1;
        }
    }
}
