package com.simplotel.jsonmigrator;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class JsonMigrationServiceTest {

    // ──────────────────────────────────────────────────────────────
    // Test migration classes
    // ──────────────────────────────────────────────────────────────

    static class ConfigV1ToV2 extends AbstractJsonVersionMigration {
        public String jsonType()  { return "CONFIG"; }
        public int fromVersion()  { return 1; }
        public int toVersion()    { return 2; }

        protected void applyChanges(DocumentContext doc) {
            doc.put("$", "new_field", "default_value");
        }
    }

    static class ConfigV2ToV3 extends AbstractJsonVersionMigration {
        public String jsonType()  { return "CONFIG"; }
        public int fromVersion()  { return 2; }
        public int toVersion()    { return 3; }

        protected void applyChanges(DocumentContext doc) {
            // Rename: old_name → renamed_field
            Object val = doc.read("$.name");
            if (val != null) {
                doc.put("$", "display_name", val);
                doc.delete("$.name");
            }
        }
    }

    static class ConfigV3ToV4 extends AbstractJsonVersionMigration {
        public String jsonType()  { return "CONFIG"; }
        public int fromVersion()  { return 3; }
        public int toVersion()    { return 4; }

        protected void applyChanges(DocumentContext doc) {
            // Remove deprecated field
            try { doc.delete("$.legacy"); } catch (Exception ignored) {}
        }
    }

    static class OtherTypeV1ToV2 extends AbstractJsonVersionMigration {
        public String jsonType()  { return "OTHER"; }
        public int fromVersion()  { return 1; }
        public int toVersion()    { return 2; }

        protected void applyChanges(DocumentContext doc) {
            doc.put("$", "added", true);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Setup
    // ──────────────────────────────────────────────────────────────

    private static final Configuration LENIENT = Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    /** Parse JSON with suppressed exceptions so read() returns null instead of throwing */
    private static DocumentContext parseLenient(String json) {
        return JsonPath.using(LENIENT).parse(json);
    }

    private JsonMigrationService service;

    @BeforeEach
    void setUp() {
        JsonMigrationRegistry registry = new JsonMigrationRegistry(List.of(
                new ConfigV1ToV2(),
                new ConfigV2ToV3(),
                new ConfigV3ToV4(),
                new OtherTypeV1ToV2()
        ));
        registry.init();
        service = new JsonMigrationService(registry);
    }

    // ──────────────────────────────────────────────────────────────
    // Tests: migrateToLatest
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("migrateToLatest()")
    class MigrateToLatest {

        @Test
        @DisplayName("migrates v1 all the way to v4")
        void fullChainMigration() {
            String v1 = """
                {"version":"1.0","name":"hello","legacy":"old"}
                """;

            String result = service.migrateToLatest("CONFIG", v1);
            DocumentContext doc = parseLenient(result);

            assertThat(doc.read("$.version", String.class)).isEqualTo("4");
            assertThat(doc.read("$.new_field", String.class)).isEqualTo("default_value"); // v1→v2
            assertThat(doc.read("$.display_name", String.class)).isEqualTo("hello");      // v2→v3 rename
            assertThat((Object) doc.read("$.name")).isNull();                              // old key removed
            assertThat((Object) doc.read("$.legacy")).isNull();                            // v3→v4 removed
        }

        @Test
        @DisplayName("migrates from v2 (skips v1→v2)")
        void partialChainMigration() {
            String v2 = """
                {"version":"2","name":"world","new_field":"custom"}
                """;

            String result = service.migrateToLatest("CONFIG", v2);
            DocumentContext doc = JsonPath.parse(result);

            assertThat(doc.read("$.version", String.class)).isEqualTo("4");
            assertThat(doc.read("$.new_field", String.class)).isEqualTo("custom"); // kept from v2
            assertThat(doc.read("$.display_name", String.class)).isEqualTo("world");
        }

        @Test
        @DisplayName("returns unchanged if already at latest")
        void alreadyLatest() {
            String v4 = """
                {"version":"4","display_name":"test","new_field":"x"}
                """;

            String result = service.migrateToLatest("CONFIG", v4);
            // Should return original (no re-serialization needed)
            assertThat(result).isEqualTo(v4);
        }

        @Test
        @DisplayName("returns null for null input")
        void nullInput() {
            assertThat(service.migrateToLatest("CONFIG", null)).isNull();
        }

        @Test
        @DisplayName("returns blank for blank input")
        void blankInput() {
            assertThat(service.migrateToLatest("CONFIG", "  ")).isEqualTo("  ");
        }

        @Test
        @DisplayName("handles missing version field as v1")
        void missingVersion() {
            String noVersion = """
                {"name":"test"}
                """;

            String result = service.migrateToLatest("CONFIG", noVersion);
            DocumentContext doc = JsonPath.parse(result);

            assertThat(doc.read("$.version", String.class)).isEqualTo("4");
            assertThat(doc.read("$.new_field", String.class)).isEqualTo("default_value");
        }

        @Test
        @DisplayName("handles '1.0' format version")
        void dotVersion() {
            String v1dot = """
                {"version":"1.0","name":"dot"}
                """;

            String result = service.migrateToLatest("CONFIG", v1dot);
            DocumentContext doc = JsonPath.parse(result);
            assertThat(doc.read("$.version", String.class)).isEqualTo("4");
        }

        @Test
        @DisplayName("returns unchanged for unknown type with no migrations")
        void unknownType() {
            String json = """
                {"version":"1","data":"test"}
                """;

            String result = service.migrateToLatest("UNKNOWN_TYPE", json);
            assertThat(result).isEqualTo(json);
        }

        @Test
        @DisplayName("different types are independent")
        void independentTypes() {
            String json = """
                {"version":"1","data":"test"}
                """;

            String configResult = service.migrateToLatest("CONFIG", json);
            String otherResult = service.migrateToLatest("OTHER", json);

            assertThat(JsonPath.parse(configResult).read("$.version", String.class)).isEqualTo("4");
            assertThat(JsonPath.parse(otherResult).read("$.version", String.class)).isEqualTo("2");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Tests: migrateToVersion
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("migrateToVersion()")
    class MigrateToVersion {

        @Test
        @DisplayName("stops at specified version")
        void stopsAtTarget() {
            String v1 = """
                {"version":"1","name":"hello","legacy":"old"}
                """;

            String result = service.migrateToVersion("CONFIG", v1, 2);
            DocumentContext doc = JsonPath.parse(result);

            assertThat(doc.read("$.version", String.class)).isEqualTo("2");
            assertThat(doc.read("$.new_field", String.class)).isEqualTo("default_value");
            assertThat(doc.read("$.name", String.class)).isEqualTo("hello"); // not renamed yet
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Tests: needsMigration
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("needsMigration()")
    class NeedsMigration {

        @Test
        void trueForOldVersion() {
            assertThat(service.needsMigration("CONFIG", "{\"version\":\"1\"}")).isTrue();
            assertThat(service.needsMigration("CONFIG", "{\"version\":\"3\"}")).isTrue();
        }

        @Test
        void falseForLatest() {
            assertThat(service.needsMigration("CONFIG", "{\"version\":\"4\"}")).isFalse();
        }

        @Test
        void falseForNull() {
            assertThat(service.needsMigration("CONFIG", null)).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Tests: getCurrentVersion
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getCurrentVersion()")
    class GetCurrentVersion {

        @Test
        void parsesIntegerVersion() {
            assertThat(service.getCurrentVersion("{\"version\":\"3\"}")).isEqualTo(3);
        }

        @Test
        void parsesDotVersion() {
            assertThat(service.getCurrentVersion("{\"version\":\"2.1\"}")).isEqualTo(2);
        }

        @Test
        void parsesNumericVersion() {
            assertThat(service.getCurrentVersion("{\"version\":5}")).isEqualTo(5);
        }

        @Test
        void defaultsTo1ForMissing() {
            assertThat(service.getCurrentVersion("{\"data\":\"test\"}")).isEqualTo(1);
        }

        @Test
        void defaultsTo1ForNull() {
            assertThat(service.getCurrentVersion(null)).isEqualTo(1);
        }

        @Test
        void defaultsTo1ForUnparseable() {
            assertThat(service.getCurrentVersion("{\"version\":\"abc\"}")).isEqualTo(1);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Tests: Registry validation
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Registry validation")
    class RegistryValidation {

        @Test
        @DisplayName("throws on gap in migration chain")
        void gapInChain() {
            // v1→v2 exists, v2→v3 missing, v3→v4 exists
            JsonMigrationRegistry broken = new JsonMigrationRegistry(List.of(
                    new ConfigV1ToV2(),
                    new ConfigV3ToV4()
            ));

            assertThatThrownBy(broken::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Gap");
        }

        @Test
        @DisplayName("throws on wrong toVersion increment")
        void wrongIncrement() {
            JsonVersionMigration bad = new JsonVersionMigration() {
                public String jsonType()  { return "BAD"; }
                public int fromVersion()  { return 1; }
                public int toVersion()    { return 3; }  // should be 2!
                public void migrate(DocumentContext doc) {}
            };

            JsonMigrationRegistry broken = new JsonMigrationRegistry(List.of(bad));
            assertThatThrownBy(broken::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must go to v2, not v3");
        }

        @Test
        @DisplayName("works with empty migration list")
        void emptyList() {
            JsonMigrationRegistry empty = new JsonMigrationRegistry(Collections.emptyList());
            assertThatCode(empty::init).doesNotThrowAnyException();

            JsonMigrationService svc = new JsonMigrationService(empty);
            assertThat(svc.migrateToLatest("ANY", "{\"version\":\"1\"}"))
                    .isEqualTo("{\"version\":\"1\"}");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Tests: Custom version path
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Custom version path")
    class CustomVersionPath {

        @Test
        @DisplayName("supports custom version field like $.schema_version")
        void customPath() {
            // Migration that writes to custom path
            JsonVersionMigration m = new JsonVersionMigration() {
                public String jsonType()  { return "CUSTOM"; }
                public int fromVersion()  { return 1; }
                public int toVersion()    { return 2; }
                public void migrate(DocumentContext doc) {
                    doc.put("$", "extra", "added");
                    doc.set("$.schema_version", "2");
                }
            };

            JsonMigrationRegistry reg = new JsonMigrationRegistry(List.of(m));
            reg.init();
            JsonMigrationService svc = new JsonMigrationService(reg, "$.schema_version");

            String json = "{\"schema_version\":\"1\",\"data\":\"hello\"}";
            String result = svc.migrateToLatest("CUSTOM", json);

            DocumentContext doc = JsonPath.parse(result);
            assertThat(doc.read("$.schema_version", String.class)).isEqualTo("2");
            assertThat(doc.read("$.extra", String.class)).isEqualTo("added");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Tests: Typed POJO return
    // ──────────────────────────────────────────────────────────────

    /** Simple POJO for testing deserialization */
    public static class SimpleConfig {
        public String version;
        public String new_field;
        public String display_name;
    }

    /** Minimal JSON→POJO deserializer using JsonPath (no Jackson needed in tests) */
    static final JsonDeserializer<SimpleConfig> SIMPLE_DESERIALIZER = json -> {
        DocumentContext doc = JsonPath.parse(json);
        SimpleConfig cfg = new SimpleConfig();
        cfg.version = doc.read("$.version", String.class);
        try { cfg.new_field = doc.read("$.new_field", String.class); } catch (Exception e) { /* optional */ }
        try { cfg.display_name = doc.read("$.display_name", String.class); } catch (Exception e) { /* optional */ }
        return cfg;
    };

    @Nested
    @DisplayName("Typed POJO return — migrateToLatest(type, json, deserializer)")
    class TypedMigrateToLatest {

        @Test
        @DisplayName("returns typed POJO after migration")
        void returnsPojo() {
            String v1 = """
                {"version":"1.0","name":"hello"}
                """;

            SimpleConfig cfg = service.migrateToLatest("CONFIG", v1, SIMPLE_DESERIALIZER);

            assertThat(cfg).isNotNull();
            assertThat(cfg.version).isEqualTo("4");
            assertThat(cfg.new_field).isEqualTo("default_value");
            assertThat(cfg.display_name).isEqualTo("hello");
        }

        @Test
        @DisplayName("returns null for null input")
        void returnsNullForNull() {
            SimpleConfig cfg = service.migrateToLatest("CONFIG", null, SIMPLE_DESERIALIZER);
            assertThat(cfg).isNull();
        }

        @Test
        @DisplayName("wraps deserialization error as RuntimeException")
        void wrapsDeserializationError() {
            JsonDeserializer<SimpleConfig> broken = json -> { throw new Exception("parse error"); };

            assertThatThrownBy(() -> service.migrateToLatest("CONFIG", "{\"version\":\"1\"}", broken))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("deserialization failed");
        }
    }

    @Nested
    @DisplayName("Typed POJO return — migrateAndValidate(type, json, deserializer)")
    class TypedMigrateAndValidate {

        @Test
        @DisplayName("returns TypedMigrationResult with POJO")
        void returnsTypedResult() {
            String v1 = """
                {"version":"1.0","name":"hello"}
                """;

            JsonMigrationService.TypedMigrationResult<SimpleConfig> result =
                    service.migrateAndValidate("CONFIG", v1, SIMPLE_DESERIALIZER);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getValue()).isNotNull();
            assertThat(result.getValue().version).isEqualTo("4");
            assertThat(result.getJson()).contains("\"version\":\"4\"");
        }

        @Test
        @DisplayName("getValue() available even when validation fails (no schema)")
        void valueAvailableWithoutSchema() {
            // "CONFIG" type has no schema registered, so validation is always valid
            String v1 = """
                {"version":"1.0","name":"test"}
                """;

            JsonMigrationService.TypedMigrationResult<SimpleConfig> result =
                    service.migrateAndValidate("CONFIG", v1, SIMPLE_DESERIALIZER);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getValue().new_field).isEqualTo("default_value");
        }

        @Test
        @DisplayName("getValueOrDefault returns default when value is null")
        void getValueOrDefaultForNull() {
            JsonMigrationService.TypedMigrationResult<SimpleConfig> result =
                    service.migrateAndValidate("CONFIG", null, SIMPLE_DESERIALIZER);

            SimpleConfig fallback = new SimpleConfig();
            fallback.version = "0";

            assertThat(result.getValueOrDefault(fallback).version).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("Typed POJO return — migrateValidateOrThrow(type, json, deserializer)")
    class TypedMigrateValidateOrThrow {

        @Test
        @DisplayName("returns POJO directly when valid")
        void returnsPojo() {
            String v1 = """
                {"version":"1.0","name":"hello"}
                """;

            SimpleConfig cfg = service.migrateValidateOrThrow("CONFIG", v1, SIMPLE_DESERIALIZER);

            assertThat(cfg).isNotNull();
            assertThat(cfg.version).isEqualTo("4");
        }

        @Test
        @DisplayName("returns null for null input")
        void nullInput() {
            SimpleConfig cfg = service.migrateValidateOrThrow("CONFIG", null, SIMPLE_DESERIALIZER);
            assertThat(cfg).isNull();
        }
    }
}
