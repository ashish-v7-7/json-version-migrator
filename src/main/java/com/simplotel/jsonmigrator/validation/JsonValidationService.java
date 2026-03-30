package com.simplotel.jsonmigrator.validation;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.simplotel.jsonmigrator.JsonMigrationRegistry;
import com.simplotel.jsonmigrator.validation.JsonValidationError.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates JSON documents against registered {@link JsonSchemaDefinition}s.
 *
 * <p><b>Spring Boot usage</b> (auto-configured):</p>
 * <pre>{@code
 * @Autowired
 * private JsonValidationService validationService;
 *
 * JsonValidationResult result = validationService.validate("CREDENTIAL", json);
 * if (!result.isValid()) {
 *     throw new BadRequestException(result.getErrorSummary());
 * }
 * }</pre>
 *
 * <p><b>Validate specific version</b>:</p>
 * <pre>{@code
 * JsonValidationResult result = validationService.validate("CREDENTIAL", 3, json);
 * }</pre>
 */
public class JsonValidationService {

    private static final Logger log = LoggerFactory.getLogger(JsonValidationService.class);

    private static final Configuration JSON_PATH_CONFIG = Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    private final JsonSchemaRegistry schemaRegistry;
    private final String versionJsonPath;
    private JsonMigrationRegistry migrationRegistry;

    public JsonValidationService(JsonSchemaRegistry schemaRegistry) {
        this(schemaRegistry, "$.version");
    }

    public JsonValidationService(JsonSchemaRegistry schemaRegistry, String versionJsonPath) {
        this.schemaRegistry = schemaRegistry;
        this.versionJsonPath = versionJsonPath;
    }

    /**
     * Sets the migration registry, used by {@link #validateInput} to know
     * which versions are valid. Called automatically by Spring auto-configuration.
     */
    public void setMigrationRegistry(JsonMigrationRegistry migrationRegistry) {
        this.migrationRegistry = migrationRegistry;
    }

    /**
     * Validates JSON against the latest schema for the given type.
     * Returns a valid result if no schema is registered (validation is opt-in).
     *
     * @param type the JSON document type
     * @param json the JSON string to validate
     * @return the validation result
     */
    public JsonValidationResult validate(String type, String json) {
        JsonSchemaDefinition schema = schemaRegistry.getLatestSchema(type);
        if (schema == null) {
            return JsonValidationResult.valid();
        }
        return validateAgainst(schema, json);
    }

    /**
     * Validates JSON against a specific version's schema.
     *
     * @param type    the JSON document type
     * @param version the version schema to validate against
     * @param json    the JSON string to validate
     * @return the validation result (valid if no schema registered for this version)
     */
    public JsonValidationResult validate(String type, int version, String json) {
        JsonSchemaDefinition schema = schemaRegistry.getSchema(type, version);
        if (schema == null) {
            return JsonValidationResult.valid();
        }
        return validateAgainst(schema, json);
    }

    /**
     * Validates and throws if invalid. Convenience method combining validate + throwIfInvalid.
     *
     * @param type the JSON document type
     * @param json the JSON string to validate
     * @throws JsonValidationException if validation fails
     */
    public void validateOrThrow(String type, String json) {
        validate(type, json).throwIfInvalid(type);
    }

    /**
     * Validates against a specific schema definition (useful for testing or custom schemas).
     */
    public JsonValidationResult validateAgainst(JsonSchemaDefinition schema, String json) {
        if (json == null || json.isBlank()) {
            return new JsonValidationResult(List.of(
                    new JsonValidationError(ErrorCode.MISSING_REQUIRED_FIELD, "$", "JSON is null or blank")));
        }

        DocumentContext doc = JsonPath.using(JSON_PATH_CONFIG).parse(json);
        JsonValidationResult.Builder builder = new JsonValidationResult.Builder();

        for (JsonFieldRule rule : schema.rules()) {
            validateField(doc, rule, builder);
        }

        JsonValidationResult result = builder.build();
        if (!result.isValid()) {
            log.warn("json-migrator: validation failed for '{}' v{} — {} error(s)",
                    schema.jsonType(), schema.version(), result.getErrorCount());
        }
        return result;
    }

    // ── Strict input validation (version + schema) ──

    /**
     * Strict input validation for JSON being <b>written</b> to the database.
     * <p>
     * Checks:
     * <ol>
     *   <li>{@code $.version} field exists and is parseable</li>
     *   <li>Version is a known version (has a schema or migration registered)</li>
     *   <li>Version equals the latest version (rejects old versions)</li>
     *   <li>JSON structure matches the schema for that version</li>
     * </ol>
     *
     * <p>Use this on write paths to ensure only valid, latest-version JSON enters the DB.</p>
     *
     * <pre>{@code
     * // Before saving to DB:
     * validationService.validateInputOrThrow("CREDENTIAL", json);
     * repository.save(json);
     * }</pre>
     *
     * @param type the JSON document type
     * @param json the JSON string to validate
     * @return the validation result
     */
    public JsonValidationResult validateInput(String type, String json) {
        if (json == null || json.isBlank()) {
            return new JsonValidationResult(List.of(
                    new JsonValidationError(ErrorCode.MISSING_REQUIRED_FIELD, "$",
                            "JSON is null or blank")));
        }

        DocumentContext doc = JsonPath.using(JSON_PATH_CONFIG).parse(json);
        JsonValidationResult.Builder builder = new JsonValidationResult.Builder();

        // 1. Check version field exists
        Object versionObj = doc.read(versionJsonPath);
        if (versionObj == null) {
            builder.addError(ErrorCode.VERSION_MISSING, versionJsonPath,
                    String.format("Version field '%s' is missing. Every JSON document must have a version.",
                            versionJsonPath));
            return builder.build(); // can't continue without version
        }

        // 2. Check version is parseable
        int version = parseVersion(versionObj);
        if (version < 1) {
            builder.addError(ErrorCode.VERSION_UNPARSEABLE, versionJsonPath,
                    String.format("Version '%s' is not a valid version number. Expected a positive integer (e.g., \"3\" or 3).",
                            versionObj));
            return builder.build();
        }

        // 3. Check version is known (has a schema or migration registered for this type)
        int latestVersion = getKnownLatestVersion(type);
        if (latestVersion < 1) {
            // No migrations and no schemas — can't validate, pass through
            return JsonValidationResult.valid();
        }

        if (version > latestVersion) {
            builder.addError(ErrorCode.VERSION_UNKNOWN, versionJsonPath,
                    String.format("Version %d is unknown for type '%s'. Latest known version is %d.",
                            version, type, latestVersion));
            return builder.build();
        }

        // 4. Check version is the latest (reject old versions on write)
        if (version < latestVersion) {
            builder.addError(ErrorCode.VERSION_NOT_LATEST, versionJsonPath,
                    String.format("Version %d is outdated for type '%s'. Expected latest version %d. "
                                    + "Migrate the JSON to the latest version before saving, "
                                    + "or use migrateValidateOrThrow() to auto-migrate.",
                            version, type, latestVersion));
            return builder.build();
        }

        // 5. Version is latest — now validate structure against schema
        JsonSchemaDefinition schema = schemaRegistry.getSchema(type, version);
        if (schema == null) {
            // Latest version but no schema defined — pass (schema is opt-in)
            return JsonValidationResult.valid();
        }

        for (JsonFieldRule rule : schema.rules()) {
            validateField(doc, rule, builder);
        }

        JsonValidationResult result = builder.build();
        if (!result.isValid()) {
            log.warn("json-migrator: input validation failed for '{}' v{} — {} error(s)",
                    type, version, result.getErrorCount());
        }
        return result;
    }

    /**
     * Validates input and throws if invalid. Use on write paths.
     *
     * @param type the JSON document type
     * @param json the JSON string to validate
     * @throws JsonValidationException if version is missing, old, unknown, or structure is invalid
     */
    public void validateInputOrThrow(String type, String json) {
        validateInput(type, json).throwIfInvalid(type);
    }

    /**
     * Validates input allowing a specific version (not just latest).
     * Useful when you accept writes at a known older version.
     *
     * @param type            the JSON document type
     * @param expectedVersion the version the JSON must declare
     * @param json            the JSON string to validate
     * @return the validation result
     */
    public JsonValidationResult validateInputForVersion(String type, int expectedVersion, String json) {
        if (json == null || json.isBlank()) {
            return new JsonValidationResult(List.of(
                    new JsonValidationError(ErrorCode.MISSING_REQUIRED_FIELD, "$",
                            "JSON is null or blank")));
        }

        DocumentContext doc = JsonPath.using(JSON_PATH_CONFIG).parse(json);
        JsonValidationResult.Builder builder = new JsonValidationResult.Builder();

        Object versionObj = doc.read(versionJsonPath);
        if (versionObj == null) {
            builder.addError(ErrorCode.VERSION_MISSING, versionJsonPath,
                    String.format("Version field '%s' is missing.", versionJsonPath));
            return builder.build();
        }

        int version = parseVersion(versionObj);
        if (version != expectedVersion) {
            builder.addError(ErrorCode.VERSION_NOT_LATEST, versionJsonPath,
                    String.format("Expected version %d but got %d for type '%s'.",
                            expectedVersion, version, type));
            return builder.build();
        }

        JsonSchemaDefinition schema = schemaRegistry.getSchema(type, version);
        if (schema != null) {
            for (JsonFieldRule rule : schema.rules()) {
                validateField(doc, rule, builder);
            }
        }

        return builder.build();
    }

    private int parseVersion(Object versionObj) {
        if (versionObj instanceof Number num) {
            return num.intValue();
        }
        String str = versionObj.toString();
        try {
            if (str.contains(".")) {
                return Integer.parseInt(str.split("\\.")[0]);
            }
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return -1; // signals unparseable
        }
    }

    private int getKnownLatestVersion(String type) {
        int fromMigrations = (migrationRegistry != null) ? migrationRegistry.getLatestVersion(type) : 1;
        JsonSchemaDefinition latestSchema = schemaRegistry.getLatestSchema(type);
        int fromSchemas = (latestSchema != null) ? latestSchema.version() : 0;
        return Math.max(fromMigrations, fromSchemas);
    }

    private void validateField(DocumentContext doc, JsonFieldRule rule, JsonValidationResult.Builder builder) {
        Object value = doc.read(rule.getJsonPath());

        switch (rule.getPresence()) {
            case REQUIRED -> {
                if (value == null) {
                    builder.addError(ErrorCode.MISSING_REQUIRED_FIELD, rule.getJsonPath(),
                            String.format("Required field '%s' is missing or null", rule.getJsonPath()));
                } else {
                    checkType(value, rule, builder);
                }
            }
            case OPTIONAL -> {
                if (value != null) {
                    checkType(value, rule, builder);
                }
            }
            case FORBIDDEN -> {
                if (value != null) {
                    builder.addError(ErrorCode.FORBIDDEN_FIELD_PRESENT, rule.getJsonPath(),
                            String.format("Forbidden field '%s' still exists (should have been removed)",
                                    rule.getJsonPath()));
                }
            }
        }
    }

    private void checkType(Object value, JsonFieldRule rule, JsonValidationResult.Builder builder) {
        if (rule.getExpectedType() == FieldType.ANY) return;

        boolean typeMatch = switch (rule.getExpectedType()) {
            case STRING  -> value instanceof String;
            case NUMBER  -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case OBJECT  -> value instanceof Map;
            case ARRAY   -> value instanceof Collection;
            case ANY     -> true;
        };

        if (!typeMatch) {
            builder.addError(ErrorCode.TYPE_MISMATCH, rule.getJsonPath(),
                    String.format("Field '%s' expected %s but got %s (%s)",
                            rule.getJsonPath(),
                            rule.getExpectedType(),
                            value.getClass().getSimpleName(),
                            truncateValue(value)));
        }
    }

    private String truncateValue(Object value) {
        String str = String.valueOf(value);
        return str.length() > 50 ? str.substring(0, 50) + "..." : str;
    }
}
