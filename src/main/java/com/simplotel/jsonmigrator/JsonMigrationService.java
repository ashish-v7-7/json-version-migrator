package com.simplotel.jsonmigrator;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.simplotel.jsonmigrator.validation.JsonValidationResult;
import com.simplotel.jsonmigrator.validation.JsonValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    // ── Array migration (for JSON arrays of versioned objects) ──

    /**
     * Migrates each element in a JSON array to the latest version.
     *
     * <p>Use this when the DB column stores an <b>array</b> of versioned objects
     * (e.g., refunds, split_payments), where each element has its own {@code $.version}.</p>
     *
     * <pre>{@code
     * // DB column: [{"version":"1","amount":50}, {"version":"2","amount":30}]
     * String migrated = migrationService.migrateArrayToLatest("REFUND", rawArrayJson);
     * // Result: [{"version":"3","amount":50,...}, {"version":"3","amount":30,...}]
     * }</pre>
     *
     * @param type the JSON document type (applied to each element)
     * @param jsonArray the raw JSON array string from the database
     * @return the migrated JSON array string, or the original if null/blank/empty
     */
    public String migrateArrayToLatest(String type, String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return jsonArray;
        }

        Object parsed = Configuration.defaultConfiguration().jsonProvider().parse(jsonArray);
        if (!(parsed instanceof List<?> list)) {
            // Not an array — fall back to single-object migration
            return migrateToLatest(type, jsonArray);
        }

        if (list.isEmpty()) {
            return jsonArray;
        }

        List<String> migratedElements = new ArrayList<>(list.size());
        for (Object element : list) {
            String elementJson = Configuration.defaultConfiguration().jsonProvider().toJson(element);
            migratedElements.add(migrateToLatest(type, elementJson));
        }

        return "[" + String.join(",", migratedElements) + "]";
    }

    /**
     * Migrates each element in a JSON array and deserializes into a typed list.
     *
     * <pre>{@code
     * List<RefundResponse> refunds = migrationService.migrateArrayToLatest(
     *     "REFUND", rawArrayJson,
     *     json -> objectMapper.readValue(json, RefundResponse.class));
     * }</pre>
     *
     * @param type         the JSON document type
     * @param jsonArray    the raw JSON array string
     * @param deserializer function to convert each migrated element JSON → POJO
     * @param <T>          the target element type
     * @return list of deserialized POJOs (empty list if input is null/blank/empty)
     */
    public <T> List<T> migrateArrayToLatest(String type, String jsonArray, JsonDeserializer<T> deserializer) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }

        Object parsed = Configuration.defaultConfiguration().jsonProvider().parse(jsonArray);
        if (!(parsed instanceof List<?> list)) {
            // Single object — wrap in list
            T single = migrateToLatest(type, jsonArray, deserializer);
            return single != null ? List.of(single) : List.of();
        }

        if (list.isEmpty()) {
            return List.of();
        }

        List<T> result = new ArrayList<>(list.size());
        for (Object element : list) {
            String elementJson = Configuration.defaultConfiguration().jsonProvider().toJson(element);
            String migrated = migrateToLatest(type, elementJson);
            result.add(deserializeOrThrow(migrated, deserializer));
        }
        return result;
    }

    /**
     * Migrates each element in a JSON array, validates each, and returns combined results.
     *
     * <pre>{@code
     * ArrayMigrationResult result = migrationService.migrateArrayAndValidate("REFUND", rawArrayJson);
     * if (result.isValid()) {
     *     String migratedArray = result.getJson();
     * } else {
     *     log.error("Element {} failed: {}", result.getFirstInvalidIndex(), result.getErrorSummary());
     * }
     * }</pre>
     *
     * @param type      the JSON document type
     * @param jsonArray the raw JSON array string
     * @return the array migration result
     */
    public ArrayMigrationResult migrateArrayAndValidate(String type, String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return new ArrayMigrationResult(jsonArray, List.of(), true);
        }

        Object parsed = Configuration.defaultConfiguration().jsonProvider().parse(jsonArray);
        if (!(parsed instanceof List<?> list) || list.isEmpty()) {
            return new ArrayMigrationResult(jsonArray, List.of(), true);
        }

        List<MigrationResult> elementResults = new ArrayList<>(list.size());
        boolean allValid = true;

        for (Object element : list) {
            String elementJson = Configuration.defaultConfiguration().jsonProvider().toJson(element);
            MigrationResult result = migrateAndValidate(type, elementJson);
            elementResults.add(result);
            if (!result.isValid()) {
                allValid = false;
            }
        }

        // Rebuild the array JSON from migrated elements
        List<String> migratedJsons = elementResults.stream()
                .map(MigrationResult::getJson)
                .toList();
        String migratedArray = "[" + String.join(",", migratedJsons) + "]";

        return new ArrayMigrationResult(migratedArray, elementResults, allValid);
    }

    /**
     * Result of migrating + validating a JSON array. Each element has its own result.
     */
    public static class ArrayMigrationResult {
        private final String json;
        private final List<MigrationResult> elementResults;
        private final boolean valid;

        public ArrayMigrationResult(String json, List<MigrationResult> elementResults, boolean valid) {
            this.json = json;
            this.elementResults = elementResults;
            this.valid = valid;
        }

        /** The migrated JSON array string. */
        public String getJson() { return json; }

        /** Per-element migration + validation results. */
        public List<MigrationResult> getElementResults() { return elementResults; }

        /** True if ALL elements passed validation. */
        public boolean isValid() { return valid; }

        /** Total number of elements. */
        public int size() { return elementResults.size(); }

        /** Index of the first invalid element, or -1 if all valid. */
        public int getFirstInvalidIndex() {
            for (int i = 0; i < elementResults.size(); i++) {
                if (!elementResults.get(i).isValid()) return i;
            }
            return -1;
        }

        /** Combined error summary across all elements. */
        public String getErrorSummary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < elementResults.size(); i++) {
                MigrationResult r = elementResults.get(i);
                if (!r.isValid()) {
                    sb.append(String.format("[element %d] %s\n", i, r.getValidation().getErrorSummary()));
                }
            }
            return sb.isEmpty() ? "No errors" : sb.toString().trim();
        }
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

    // ── Typed POJO return methods ──

    /**
     * Migrates JSON to latest version and deserializes into a typed POJO.
     *
     * <pre>{@code
     * GatewayCredential cred = migrationService.migrateToLatest(
     *     "CREDENTIAL", rawJson, json -> objectMapper.readValue(json, GatewayCredential.class));
     * }</pre>
     *
     * @param type         the JSON document type
     * @param json         the raw JSON string
     * @param deserializer function to convert migrated JSON → POJO
     * @param <T>          the target POJO type
     * @return the deserialized POJO, or null if input was null/blank
     */
    public <T> T migrateToLatest(String type, String json, JsonDeserializer<T> deserializer) {
        String migrated = migrateToLatest(type, json);
        if (migrated == null || migrated.isBlank()) {
            return null;
        }
        return deserializeOrThrow(migrated, deserializer);
    }

    /**
     * Migrates to latest, validates, and deserializes into a typed result.
     *
     * <pre>{@code
     * TypedMigrationResult<GatewayCredential> result = migrationService.migrateAndValidate(
     *     "CREDENTIAL", rawJson, json -> objectMapper.readValue(json, GatewayCredential.class));
     *
     * if (result.isValid()) {
     *     GatewayCredential cred = result.getValue();  // typed POJO
     * }
     * }</pre>
     *
     * @param type         the JSON document type
     * @param json         the raw JSON string
     * @param deserializer function to convert migrated JSON → POJO
     * @param <T>          the target POJO type
     * @return typed migration result with POJO + validation
     */
    public <T> TypedMigrationResult<T> migrateAndValidate(String type, String json, JsonDeserializer<T> deserializer) {
        MigrationResult result = migrateAndValidate(type, json);
        T value = null;
        if (result.getJson() != null && !result.getJson().isBlank()) {
            value = deserializeOrThrow(result.getJson(), deserializer);
        }
        return new TypedMigrationResult<>(result.getJson(), result.getValidation(), value);
    }

    /**
     * Migrates to latest, validates (throws if invalid), and returns a typed POJO.
     *
     * <pre>{@code
     * GatewayCredential cred = migrationService.migrateValidateOrThrow(
     *     "CREDENTIAL", rawJson, json -> objectMapper.readValue(json, GatewayCredential.class));
     * }</pre>
     *
     * @param type         the JSON document type
     * @param json         the raw JSON string
     * @param deserializer function to convert migrated JSON → POJO
     * @param <T>          the target POJO type
     * @return the deserialized POJO (only returned if validation passed)
     * @throws com.simplotel.jsonmigrator.validation.JsonValidationException if validation fails
     */
    public <T> T migrateValidateOrThrow(String type, String json, JsonDeserializer<T> deserializer) {
        String migrated = migrateValidateOrThrow(type, json);
        if (migrated == null || migrated.isBlank()) {
            return null;
        }
        return deserializeOrThrow(migrated, deserializer);
    }

    private <T> T deserializeOrThrow(String json, JsonDeserializer<T> deserializer) {
        try {
            return deserializer.deserialize(json);
        } catch (Exception e) {
            throw new RuntimeException("json-migrator: deserialization failed — " + e.getMessage(), e);
        }
    }

    // ── Result classes ──

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

    /**
     * Typed result containing migrated JSON, validation result, and the deserialized POJO.
     *
     * @param <T> the POJO type
     */
    public static class TypedMigrationResult<T> extends MigrationResult {
        private final T value;

        public TypedMigrationResult(String json, JsonValidationResult validation, T value) {
            super(json, validation);
            this.value = value;
        }

        /** The deserialized POJO (may be null if input was null/blank). */
        public T getValue() { return value; }

        /**
         * Returns the POJO if valid, otherwise returns the provided default.
         *
         * @param defaultValue the fallback value if validation failed or value is null
         * @return the POJO or defaultValue
         */
        public T getValueOrDefault(T defaultValue) {
            return (isValid() && value != null) ? value : defaultValue;
        }
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
