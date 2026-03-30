package com.simplotel.jsonmigrator.validation;

import com.jayway.jsonpath.DocumentContext;
import com.simplotel.jsonmigrator.AbstractJsonVersionMigration;
import com.simplotel.jsonmigrator.JsonMigrationRegistry;
import com.simplotel.jsonmigrator.JsonMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class JsonValidationServiceTest {

    // ── Test schema definitions ──

    static class CredentialV3Schema implements JsonSchemaDefinition {
        public String jsonType()  { return "CREDENTIAL"; }
        public int version()      { return 3; }

        public List<JsonFieldRule> rules() {
            return List.of(
                    JsonFieldRule.required("$.version", FieldType.STRING),
                    JsonFieldRule.required("$.fields", FieldType.OBJECT),
                    JsonFieldRule.required("$.fields.api_key_id", FieldType.STRING),
                    JsonFieldRule.required("$.fields.key_secret", FieldType.STRING),
                    JsonFieldRule.required("$.fields.gateway_mode", FieldType.STRING),
                    JsonFieldRule.optional("$.fields.webhook_secret", FieldType.STRING),
                    JsonFieldRule.forbidden("$.fields.key_id"),
                    JsonFieldRule.forbidden("$.fields.legacy_token")
            );
        }
    }

    static class CredentialV1Schema implements JsonSchemaDefinition {
        public String jsonType()  { return "CREDENTIAL"; }
        public int version()      { return 1; }

        public List<JsonFieldRule> rules() {
            return List.of(
                    JsonFieldRule.required("$.version", FieldType.STRING),
                    JsonFieldRule.required("$.fields", FieldType.OBJECT),
                    JsonFieldRule.required("$.fields.key_id", FieldType.STRING),
                    JsonFieldRule.required("$.fields.key_secret", FieldType.STRING)
            );
        }
    }

    static class CredentialV2Schema implements JsonSchemaDefinition {
        public String jsonType()  { return "CREDENTIAL"; }
        public int version()      { return 2; }

        public List<JsonFieldRule> rules() {
            return List.of(
                    JsonFieldRule.required("$.version", FieldType.STRING),
                    JsonFieldRule.required("$.fields", FieldType.OBJECT),
                    JsonFieldRule.required("$.fields.key_id", FieldType.STRING),
                    JsonFieldRule.required("$.fields.key_secret", FieldType.STRING),
                    JsonFieldRule.required("$.fields.gateway_mode", FieldType.STRING),
                    JsonFieldRule.forbidden("$.fields.api_key_id")  // not renamed yet in v2
            );
        }
    }

    // ── Test migrations ──

    static class CredV1ToV2 extends AbstractJsonVersionMigration {
        public String jsonType()  { return "CREDENTIAL"; }
        public int fromVersion()  { return 1; }
        public int toVersion()    { return 2; }
        protected void applyChanges(DocumentContext doc) {
            doc.put("$.fields", "gateway_mode", "live");
        }
    }

    static class CredV2ToV3 extends AbstractJsonVersionMigration {
        public String jsonType()  { return "CREDENTIAL"; }
        public int fromVersion()  { return 2; }
        public int toVersion()    { return 3; }
        protected void applyChanges(DocumentContext doc) {
            Object keyId = doc.read("$.fields.key_id");
            if (keyId != null) {
                doc.put("$.fields", "api_key_id", keyId);
                doc.delete("$.fields.key_id");
            }
            try { doc.delete("$.fields.legacy_token"); } catch (Exception ignored) {}
        }
    }

    // ── Setup ──

    private JsonValidationService validationService;
    private JsonMigrationService migrationService;

    @BeforeEach
    void setUp() {
        // Migration registry
        JsonMigrationRegistry migrationRegistry = new JsonMigrationRegistry(List.of(
                new CredV1ToV2(), new CredV2ToV3()));
        migrationRegistry.init();

        // Schema registry (schemas for all versions)
        JsonSchemaRegistry schemaRegistry = new JsonSchemaRegistry(List.of(
                new CredentialV1Schema(), new CredentialV2Schema(), new CredentialV3Schema()));
        schemaRegistry.init();

        // Validation service — wired with both registries
        validationService = new JsonValidationService(schemaRegistry);
        validationService.setMigrationRegistry(migrationRegistry);

        // Migration service
        migrationService = new JsonMigrationService(migrationRegistry);
        migrationService.setValidationService(validationService);
    }

    // ── Tests: standalone validation ──

    @Nested
    @DisplayName("validate()")
    class Validate {

        @Test
        @DisplayName("valid v3 JSON passes all rules")
        void validJson() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp_123","key_secret":"sk_456","gateway_mode":"live"}}
                """;

            JsonValidationResult result = validationService.validate("CREDENTIAL", json);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("missing required field reports error")
        void missingRequired() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp_123","gateway_mode":"live"}}
                """;
            // missing key_secret

            JsonValidationResult result = validationService.validate("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorCount()).isEqualTo(1);
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.MISSING_REQUIRED_FIELD);
            assertThat(result.getErrors().get(0).getJsonPath()).isEqualTo("$.fields.key_secret");
        }

        @Test
        @DisplayName("forbidden field still present reports error")
        void forbiddenPresent() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp_123","key_secret":"sk","gateway_mode":"live","key_id":"old_value"}}
                """;

            JsonValidationResult result = validationService.validate("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).anyMatch(e ->
                    e.getCode() == JsonValidationError.ErrorCode.FORBIDDEN_FIELD_PRESENT
                    && e.getJsonPath().equals("$.fields.key_id"));
        }

        @Test
        @DisplayName("type mismatch reports error")
        void typeMismatch() {
            String json = """
                {"version":"3","fields":{"api_key_id":12345,"key_secret":"sk","gateway_mode":"live"}}
                """;
            // api_key_id is a number, expected string

            JsonValidationResult result = validationService.validate("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).anyMatch(e ->
                    e.getCode() == JsonValidationError.ErrorCode.TYPE_MISMATCH
                    && e.getJsonPath().equals("$.fields.api_key_id"));
        }

        @Test
        @DisplayName("multiple errors reported together")
        void multipleErrors() {
            String json = """
                {"version":"3","fields":{"gateway_mode":"live","legacy_token":"old"}}
                """;
            // missing: api_key_id, key_secret
            // forbidden: legacy_token

            JsonValidationResult result = validationService.validate("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("optional field can be absent")
        void optionalAbsent() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;
            // webhook_secret is optional and absent — fine

            assertThat(validationService.validate("CREDENTIAL", json).isValid()).isTrue();
        }

        @Test
        @DisplayName("optional field present with wrong type reports error")
        void optionalWrongType() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp","key_secret":"sk","gateway_mode":"live","webhook_secret":999}}
                """;

            JsonValidationResult result = validationService.validate("CREDENTIAL", json);
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.TYPE_MISMATCH);
        }

        @Test
        @DisplayName("null JSON returns error")
        void nullJson() {
            JsonValidationResult result = validationService.validate("CREDENTIAL", null);
            assertThat(result.isValid()).isFalse();
        }

        @Test
        @DisplayName("unknown type with no schema returns valid (opt-in)")
        void unknownType() {
            JsonValidationResult result = validationService.validate("UNKNOWN", "{\"data\":1}");
            assertThat(result.isValid()).isTrue();
        }
    }

    // ── Tests: validateOrThrow ──

    @Nested
    @DisplayName("validateOrThrow()")
    class ValidateOrThrow {

        @Test
        @DisplayName("does not throw for valid JSON")
        void noThrowForValid() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;
            assertThatCode(() -> validationService.validateOrThrow("CREDENTIAL", json))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws JsonValidationException for invalid JSON")
        void throwsForInvalid() {
            String json = """
                {"version":"3","fields":{"gateway_mode":"live"}}
                """;
            assertThatThrownBy(() -> validationService.validateOrThrow("CREDENTIAL", json))
                    .isInstanceOf(JsonValidationException.class)
                    .hasMessageContaining("CREDENTIAL")
                    .hasMessageContaining("error");
        }
    }

    // ── Tests: migrateAndValidate ──

    @Nested
    @DisplayName("migrateAndValidate()")
    class MigrateAndValidate {

        @Test
        @DisplayName("v1 JSON migrated to v3 passes validation")
        void migrateAndValidateSuccess() {
            String v1 = """
                {"version":"1.0","fields":{"key_id":"rzp_123","key_secret":"sk_456"}}
                """;

            JsonMigrationService.MigrationResult result = migrationService.migrateAndValidate("CREDENTIAL", v1);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getJson()).contains("api_key_id");  // renamed
            assertThat(result.getJson()).contains("gateway_mode"); // added
        }

        @Test
        @DisplayName("v1 JSON with missing key_secret fails validation after migration")
        void migrateAndValidateFailure() {
            String v1 = """
                {"version":"1.0","fields":{"key_id":"rzp_123"}}
                """;
            // key_secret was never there — migration can't fix that

            JsonMigrationService.MigrationResult result = migrationService.migrateAndValidate("CREDENTIAL", v1);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getValidation().getErrors()).anyMatch(e ->
                    e.getJsonPath().equals("$.fields.key_secret"));
        }

        @Test
        @DisplayName("migrateValidateOrThrow succeeds for valid data")
        void migrateValidateOrThrowSuccess() {
            String v1 = """
                {"version":"1.0","fields":{"key_id":"rzp_123","key_secret":"sk_456"}}
                """;

            String result = migrationService.migrateValidateOrThrow("CREDENTIAL", v1);
            assertThat(result).contains("\"version\":\"3\"");
        }

        @Test
        @DisplayName("migrateValidateOrThrow throws for invalid data")
        void migrateValidateOrThrowFails() {
            String v1 = """
                {"version":"1.0","fields":{"key_id":"rzp_123"}}
                """;

            assertThatThrownBy(() -> migrationService.migrateValidateOrThrow("CREDENTIAL", v1))
                    .isInstanceOf(JsonValidationException.class);
        }

        @Test
        @DisplayName("null JSON returns valid result with null json")
        void nullJson() {
            JsonMigrationService.MigrationResult result = migrationService.migrateAndValidate("CREDENTIAL", null);
            assertThat(result.getJson()).isNull();
            assertThat(result.isValid()).isTrue();
        }
    }

    // ── Tests: schema registry ──

    @Nested
    @DisplayName("JsonSchemaRegistry")
    class SchemaRegistryTests {

        @Test
        @DisplayName("rejects duplicate schema for same type+version")
        void duplicateSchema() {
            JsonSchemaRegistry reg = new JsonSchemaRegistry(List.of(
                    new CredentialV3Schema(),
                    new CredentialV3Schema()  // duplicate
            ));

            assertThatThrownBy(reg::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate schema");
        }

        @Test
        @DisplayName("works with empty schema list")
        void emptyList() {
            JsonSchemaRegistry reg = new JsonSchemaRegistry(Collections.emptyList());
            assertThatCode(reg::init).doesNotThrowAnyException();
            assertThat(reg.hasSchema("CREDENTIAL")).isFalse();
        }
    }

    // ── Tests: error summary ──

    @Nested
    @DisplayName("JsonValidationResult")
    class ResultTests {

        @Test
        @DisplayName("error summary contains all error details")
        void errorSummary() {
            String json = """
                {"version":"3","fields":{"gateway_mode":"live","legacy_token":"old"}}
                """;

            JsonValidationResult result = validationService.validate("CREDENTIAL", json);
            String summary = result.getErrorSummary();

            assertThat(summary).contains("api_key_id");
            assertThat(summary).contains("key_secret");
            assertThat(summary).contains("legacy_token");
        }

        @Test
        @DisplayName("valid result summary says no errors")
        void validSummary() {
            assertThat(JsonValidationResult.valid().getErrorSummary()).isEqualTo("No errors");
        }
    }

    // ── Tests: strict input validation ──

    @Nested
    @DisplayName("validateInput()")
    class ValidateInput {

        @Test
        @DisplayName("valid latest-version JSON passes")
        void validLatest() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;

            JsonValidationResult result = validationService.validateInput("CREDENTIAL", json);
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("rejects JSON with no version field")
        void missingVersion() {
            String json = """
                {"fields":{"api_key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;

            JsonValidationResult result = validationService.validateInput("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_MISSING);
            assertThat(result.getErrors().get(0).getMessage()).contains("missing");
        }

        @Test
        @DisplayName("rejects JSON with unparseable version")
        void unparseableVersion() {
            String json = """
                {"version":"abc","fields":{"api_key_id":"rzp"}}
                """;

            JsonValidationResult result = validationService.validateInput("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_UNPARSEABLE);
        }

        @Test
        @DisplayName("rejects JSON with old version (not latest)")
        void oldVersion() {
            String json = """
                {"version":"1","fields":{"key_id":"rzp","key_secret":"sk"}}
                """;

            JsonValidationResult result = validationService.validateInput("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_NOT_LATEST);
            assertThat(result.getErrors().get(0).getMessage()).contains("outdated");
            assertThat(result.getErrors().get(0).getMessage()).contains("3"); // latest
        }

        @Test
        @DisplayName("rejects JSON with version higher than latest")
        void futureVersion() {
            String json = """
                {"version":"99","fields":{"api_key_id":"rzp"}}
                """;

            JsonValidationResult result = validationService.validateInput("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_UNKNOWN);
        }

        @Test
        @DisplayName("rejects null JSON")
        void nullJson() {
            JsonValidationResult result = validationService.validateInput("CREDENTIAL", null);
            assertThat(result.isValid()).isFalse();
        }

        @Test
        @DisplayName("latest version but invalid structure reports field errors")
        void latestVersionBadStructure() {
            // Version 3 but missing required fields
            String json = """
                {"version":"3","fields":{"gateway_mode":"live"}}
                """;

            JsonValidationResult result = validationService.validateInput("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).anyMatch(e ->
                    e.getCode() == JsonValidationError.ErrorCode.MISSING_REQUIRED_FIELD);
        }

        @Test
        @DisplayName("unknown type with no registrations passes (no-op)")
        void unknownType() {
            String json = """
                {"version":"1","data":"test"}
                """;

            JsonValidationResult result = validationService.validateInput("UNKNOWN", json);
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("dot format version '1.0' is parsed correctly and rejected as old")
        void dotFormatVersion() {
            String json = """
                {"version":"1.0","fields":{"key_id":"rzp"}}
                """;

            JsonValidationResult result = validationService.validateInput("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_NOT_LATEST);
        }
    }

    // ── Tests: validateInputOrThrow ──

    @Nested
    @DisplayName("validateInputOrThrow()")
    class ValidateInputOrThrow {

        @Test
        @DisplayName("passes for valid latest-version JSON")
        void passesForValid() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;

            assertThatCode(() -> validationService.validateInputOrThrow("CREDENTIAL", json))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws for old version")
        void throwsForOldVersion() {
            String json = """
                {"version":"2","fields":{"key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;

            assertThatThrownBy(() -> validationService.validateInputOrThrow("CREDENTIAL", json))
                    .isInstanceOf(JsonValidationException.class)
                    .hasMessageContaining("CREDENTIAL");
        }

        @Test
        @DisplayName("throws for missing version")
        void throwsForMissingVersion() {
            String json = """
                {"fields":{"api_key_id":"rzp"}}
                """;

            assertThatThrownBy(() -> validationService.validateInputOrThrow("CREDENTIAL", json))
                    .isInstanceOf(JsonValidationException.class)
                    .hasMessageContaining("CREDENTIAL");
        }
    }

    // ── Tests: validateInputForVersion ──

    @Nested
    @DisplayName("validateInputForVersion()")
    class ValidateInputForVersion {

        @Test
        @DisplayName("passes when version matches expected")
        void matchingVersion() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;

            JsonValidationResult result = validationService.validateInputForVersion("CREDENTIAL", 3, json);
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("rejects when version does not match expected")
        void mismatchedVersion() {
            String json = """
                {"version":"2","fields":{"key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;

            JsonValidationResult result = validationService.validateInputForVersion("CREDENTIAL", 3, json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_NOT_LATEST);
            assertThat(result.getErrors().get(0).getMessage()).contains("Expected version 3 but got 2");
        }

        @Test
        @DisplayName("rejects when version field is missing")
        void missingVersion() {
            String json = """
                {"fields":{"api_key_id":"rzp"}}
                """;

            JsonValidationResult result = validationService.validateInputForVersion("CREDENTIAL", 3, json);
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_MISSING);
        }
    }

    // ── Tests: validateInputAnyVersion ──

    @Nested
    @DisplayName("validateInputAnyVersion()")
    class ValidateInputAnyVersion {

        @Test
        @DisplayName("v1 JSON validated against V1Schema — passes")
        void v1PassesAgainstV1Schema() {
            String json = """
                {"version":"1","fields":{"key_id":"rzp_123","key_secret":"sk_456"}}
                """;

            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", json);
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("v2 JSON validated against V2Schema — passes")
        void v2PassesAgainstV2Schema() {
            String json = """
                {"version":"2","fields":{"key_id":"rzp_123","key_secret":"sk_456","gateway_mode":"live"}}
                """;

            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", json);
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("v3 JSON validated against V3Schema — passes")
        void v3PassesAgainstV3Schema() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp_123","key_secret":"sk_456","gateway_mode":"live"}}
                """;

            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", json);
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("v1 JSON with wrong structure — fails against V1Schema")
        void v1FailsWithBadStructure() {
            // v1 schema requires key_id, but this has api_key_id
            String json = """
                {"version":"1","fields":{"api_key_id":"rzp_123","key_secret":"sk_456"}}
                """;

            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).anyMatch(e ->
                    e.getCode() == JsonValidationError.ErrorCode.MISSING_REQUIRED_FIELD
                    && e.getJsonPath().equals("$.fields.key_id"));
        }

        @Test
        @DisplayName("v2 JSON with v3 structure — fails (api_key_id forbidden in v2)")
        void v2FailsWithV3Structure() {
            // v2 schema has forbidden("$.fields.api_key_id") — renamed only in v3
            String json = """
                {"version":"2","fields":{"api_key_id":"rzp","key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;

            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).anyMatch(e ->
                    e.getCode() == JsonValidationError.ErrorCode.FORBIDDEN_FIELD_PRESENT
                    && e.getJsonPath().equals("$.fields.api_key_id"));
        }

        @Test
        @DisplayName("rejects future version (no schema)")
        void rejectsFutureVersion() {
            String json = """
                {"version":"99","fields":{"key_id":"rzp"}}
                """;

            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_UNKNOWN);
        }

        @Test
        @DisplayName("rejects missing version")
        void rejectsMissingVersion() {
            String json = """
                {"fields":{"key_id":"rzp"}}
                """;

            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_MISSING);
        }

        @Test
        @DisplayName("rejects unparseable version")
        void rejectsUnparseableVersion() {
            String json = """
                {"version":"abc","fields":{"key_id":"rzp"}}
                """;

            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", json);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors().get(0).getCode())
                    .isEqualTo(JsonValidationError.ErrorCode.VERSION_UNPARSEABLE);
        }

        @Test
        @DisplayName("rejects null JSON")
        void rejectsNull() {
            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", null);
            assertThat(result.isValid()).isFalse();
        }

        @Test
        @DisplayName("dot format version '1.0' resolves to v1 and validates")
        void dotFormatResolvesToV1() {
            String json = """
                {"version":"1.0","fields":{"key_id":"rzp_123","key_secret":"sk_456"}}
                """;

            JsonValidationResult result = validationService.validateInputAnyVersion("CREDENTIAL", json);
            assertThat(result.isValid()).isTrue();
        }
    }

    // ── Tests: validateInputAnyVersionOrThrow ──

    @Nested
    @DisplayName("validateInputAnyVersionOrThrow()")
    class ValidateInputAnyVersionOrThrow {

        @Test
        @DisplayName("passes for valid v1 JSON")
        void passesForV1() {
            String json = """
                {"version":"1","fields":{"key_id":"rzp","key_secret":"sk"}}
                """;

            assertThatCode(() -> validationService.validateInputAnyVersionOrThrow("CREDENTIAL", json))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("passes for valid v3 JSON")
        void passesForV3() {
            String json = """
                {"version":"3","fields":{"api_key_id":"rzp","key_secret":"sk","gateway_mode":"live"}}
                """;

            assertThatCode(() -> validationService.validateInputAnyVersionOrThrow("CREDENTIAL", json))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws for v1 JSON with wrong structure")
        void throwsForBadV1() {
            String json = """
                {"version":"1","fields":{"wrong_field":"rzp"}}
                """;

            assertThatThrownBy(() -> validationService.validateInputAnyVersionOrThrow("CREDENTIAL", json))
                    .isInstanceOf(JsonValidationException.class);
        }
    }
}
