package com.simplotel.jsonmigrator.validation;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.simplotel.jsonmigrator.validation.JsonValidationError.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    public JsonValidationService(JsonSchemaRegistry schemaRegistry) {
        this.schemaRegistry = schemaRegistry;
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
