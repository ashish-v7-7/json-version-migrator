package com.simplotel.jsonmigrator.validation;

import java.util.Collections;
import java.util.List;

/**
 * Describes validation rules for a single field in a JSON document.
 *
 * <p>Example:</p>
 * <pre>{@code
 * JsonFieldRule.required("$.merchant_id", FieldType.STRING)
 * JsonFieldRule.optional("$.gateway_mode", FieldType.STRING, "live")
 * JsonFieldRule.forbidden("$.legacy_token")
 *
 * // Validate each element in an array:
 * JsonFieldRule.eachElement("$.paymentAccountInfo", List.of(
 *     JsonFieldRule.required("$.status", FieldType.STRING),
 *     JsonFieldRule.required("$.refundAmount", FieldType.NUMBER),
 *     JsonFieldRule.optional("$.errorReason", FieldType.STRING)
 * ))
 * }</pre>
 */
public class JsonFieldRule {

    private final String jsonPath;
    private final Presence presence;
    private final FieldType expectedType;
    private final Object defaultValue;
    private final List<JsonFieldRule> elementRules;

    public enum Presence {
        /** Field must exist and be non-null */
        REQUIRED,
        /** Field may or may not exist */
        OPTIONAL,
        /** Field must NOT exist (deprecated/removed) */
        FORBIDDEN,
        /** Field is an array — validate each element against sub-rules */
        EACH_ELEMENT
    }

    private JsonFieldRule(String jsonPath, Presence presence, FieldType expectedType,
                          Object defaultValue, List<JsonFieldRule> elementRules) {
        this.jsonPath = jsonPath;
        this.presence = presence;
        this.expectedType = expectedType;
        this.defaultValue = defaultValue;
        this.elementRules = elementRules;
    }

    // ── Factory methods ──

    /** Field must exist and be non-null with the expected type. */
    public static JsonFieldRule required(String jsonPath, FieldType type) {
        return new JsonFieldRule(jsonPath, Presence.REQUIRED, type, null, Collections.emptyList());
    }

    /** Field may or may not exist. If present, must match the expected type. */
    public static JsonFieldRule optional(String jsonPath, FieldType type) {
        return new JsonFieldRule(jsonPath, Presence.OPTIONAL, type, null, Collections.emptyList());
    }

    /** Field may or may not exist. If missing, the default value is noted in error context. */
    public static JsonFieldRule optional(String jsonPath, FieldType type, Object defaultValue) {
        return new JsonFieldRule(jsonPath, Presence.OPTIONAL, type, defaultValue, Collections.emptyList());
    }

    /** Field must NOT exist. Used to verify deprecated fields were actually removed. */
    public static JsonFieldRule forbidden(String jsonPath) {
        return new JsonFieldRule(jsonPath, Presence.FORBIDDEN, FieldType.ANY, null, Collections.emptyList());
    }

    /**
     * Validates that the field at {@code arrayPath} is an array, and each element
     * passes the given sub-rules.
     *
     * <p>Sub-rule paths are relative to each element (use {@code $.<fieldName>}).</p>
     *
     * <pre>{@code
     * JsonFieldRule.eachElement("$.paymentAccountInfo", List.of(
     *     JsonFieldRule.required("$.payeeRefundId", FieldType.STRING),
     *     JsonFieldRule.required("$.refundAmount", FieldType.NUMBER),
     *     JsonFieldRule.required("$.status", FieldType.STRING),
     *     JsonFieldRule.optional("$.errorReason", FieldType.STRING)
     * ))
     * }</pre>
     *
     * @param arrayPath    the JsonPath to the array field (e.g., {@code "$.paymentAccountInfo"})
     * @param elementRules rules applied to each element in the array
     */
    public static JsonFieldRule eachElement(String arrayPath, List<JsonFieldRule> elementRules) {
        return new JsonFieldRule(arrayPath, Presence.EACH_ELEMENT, FieldType.ARRAY, null, elementRules);
    }

    // ── Getters ──

    public String getJsonPath()                { return jsonPath; }
    public Presence getPresence()              { return presence; }
    public FieldType getExpectedType()         { return expectedType; }
    public Object getDefaultValue()            { return defaultValue; }
    public List<JsonFieldRule> getElementRules() { return elementRules; }

    @Override
    public String toString() {
        if (presence == Presence.EACH_ELEMENT) {
            return String.format("EACH_ELEMENT(%s, %d rules)", jsonPath, elementRules.size());
        }
        return String.format("%s(%s, %s)", presence, jsonPath, expectedType);
    }
}
