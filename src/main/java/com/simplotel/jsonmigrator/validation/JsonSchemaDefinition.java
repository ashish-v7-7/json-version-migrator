package com.simplotel.jsonmigrator.validation;

import java.util.List;

/**
 * Defines the expected JSON structure for a specific (type, version) combination.
 *
 * <p>Implement this interface and register as a Spring {@code @Component} to enable
 * automatic validation after migration.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Component
 * public class CredentialV3Schema implements JsonSchemaDefinition {
 *
 *     public String jsonType()  { return "CREDENTIAL"; }
 *     public int version()      { return 3; }
 *
 *     public List<JsonFieldRule> rules() {
 *         return List.of(
 *             JsonFieldRule.required("$.version", FieldType.STRING),
 *             JsonFieldRule.required("$.fields.api_key_id", FieldType.STRING),
 *             JsonFieldRule.required("$.fields.key_secret", FieldType.STRING),
 *             JsonFieldRule.required("$.fields.gateway_mode", FieldType.STRING),
 *             JsonFieldRule.forbidden("$.fields.key_id"),          // renamed in v3
 *             JsonFieldRule.forbidden("$.fields.legacy_token")     // removed in v3
 *         );
 *     }
 * }
 * }</pre>
 *
 * @see JsonFieldRule
 * @see JsonValidationService
 */
public interface JsonSchemaDefinition {

    /**
     * The JSON document type this schema validates (must match your migration type strings).
     */
    String jsonType();

    /**
     * The version this schema describes.
     * Typically you only define a schema for the latest version,
     * but you can define schemas for any version.
     */
    int version();

    /**
     * The list of field rules that define the expected structure.
     */
    List<JsonFieldRule> rules();
}
