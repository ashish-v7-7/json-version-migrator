# JSON Version Migrator — Developer Guide

Step-by-step guide for integrating the `json-version-migrator` module into any Simplotel service.

---

## What It Does

If your service stores JSON in a database column (credentials, configs, metadata), this module:

1. **Auto-migrates** old JSON to the latest version when you read it (no batch scripts, no downtime)
2. **Validates** the JSON structure matches the expected schema (catches bad data before it hits your DTO)
3. **Rejects bad writes** — missing version, wrong version, invalid structure

---

## Step 1: Add the Dependency

In your service's `pom.xml`:

```xml
<dependency>
    <groupId>com.simplotel</groupId>
    <artifactId>json-version-migrator</artifactId>
    <version>1.0.0</version>
</dependency>
```

That's it. Spring Boot auto-configures everything — no `@EnableXxx` needed.

---

## Step 2: Define Your JSON Type Constants

Create a constants class in your service. Each versioned JSON column gets its own type string.

```java
package com.yourservice.migration;

public final class JsonTypes {
    private JsonTypes() {}

    // One constant per JSON column / JSON family
    public static final String BRANDING_CONFIG = "BRANDING_CONFIG";
    public static final String CREDENTIAL_RAZORPAY = "CREDENTIAL_RAZORPAY";
    public static final String CREDENTIAL_CCAVENUE = "CREDENTIAL_CCAVENUE";
    public static final String REFUND = "REFUND";
    // ... add more as needed

    // Helper for gateway-specific types
    public static String credentialTypeForGateway(String gatewayId) {
        return "CREDENTIAL_" + gatewayId;
    }
}
```

---

## Step 3: Define Your V1 Schema

Create a schema class that describes what the current JSON structure looks like. This is used for **validation**.

```
src/main/java/com/yourservice/migration/schema/branding/BrandingV1Schema.java
```

```java
package com.yourservice.migration.schema.branding;

import com.simplotel.jsonmigrator.validation.FieldType;
import com.simplotel.jsonmigrator.validation.JsonFieldRule;
import com.simplotel.jsonmigrator.validation.JsonSchemaDefinition;
import com.yourservice.migration.JsonTypes;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BrandingV1Schema implements JsonSchemaDefinition {

    @Override public String jsonType() { return JsonTypes.BRANDING_CONFIG; }
    @Override public int version()     { return 1; }

    @Override
    public List<JsonFieldRule> rules() {
        return List.of(
            JsonFieldRule.required("$.version", FieldType.STRING),
            JsonFieldRule.optional("$.logoUrl", FieldType.STRING),
            JsonFieldRule.optional("$.themeColor", FieldType.STRING),
            JsonFieldRule.optional("$.name", FieldType.STRING)
        );
    }
}
```

### Field Rule Types

| Factory Method | Meaning |
|---|---|
| `JsonFieldRule.required("$.field", FieldType.STRING)` | Must exist, must be a String |
| `JsonFieldRule.optional("$.field", FieldType.NUMBER)` | Can be absent, but if present must be a Number |
| `JsonFieldRule.forbidden("$.oldField")` | Must NOT exist (removed/renamed) |
| `JsonFieldRule.eachElement("$.items", List.of(...))` | Array — validate each element against sub-rules |

### Field Types

`STRING`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY`, `ANY`

### Nested Array Validation

For arrays of objects (like refunds, splits):

```java
JsonFieldRule.eachElement("$.paymentAccountInfo", List.of(
    JsonFieldRule.required("$.payeeRefundId", FieldType.STRING),
    JsonFieldRule.required("$.refundAmount", FieldType.NUMBER),
    JsonFieldRule.required("$.status", FieldType.STRING),
    JsonFieldRule.optional("$.errorReason", FieldType.STRING)
))
```

Error paths include the index: `$.paymentAccountInfo[1].status`

---

## Step 4: Use in Your Service (Read Path)

Inject `JsonMigrationService` and use it when reading JSON from the DB.

### Option A: Get JSON string

```java
@Service
public class MyService {

    private final JsonMigrationService migrationService;

    public void readData(String rawJson) {
        String migrated = migrationService.migrateToLatest(JsonTypes.BRANDING_CONFIG, rawJson);
        // migrated is now at the latest version
    }
}
```

### Option B: Get typed POJO directly

```java
BrandingConfig config = migrationService.migrateToLatest(
    JsonTypes.BRANDING_CONFIG, rawJson,
    json -> jacksonUtils.fromJsonString(json, BrandingConfig.class));
```

### Option C: Migrate + validate + get POJO

```java
BrandingConfig config = migrationService.migrateValidateOrThrow(
    JsonTypes.BRANDING_CONFIG, rawJson,
    json -> jacksonUtils.fromJsonString(json, BrandingConfig.class));
// Throws JsonValidationException if structure doesn't match schema
```

### Option D: For JSON arrays (refunds, splits)

```java
// Returns migrated JSON array string
String migratedArray = migrationService.migrateArrayToLatest(JsonTypes.REFUND, rawArrayJson);

// Returns List<T>
List<RefundResponse> refunds = migrationService.migrateArrayToLatest(
    JsonTypes.REFUND, rawArrayJson,
    json -> objectMapper.readValue(json, RefundResponse.class));
```

---

## Step 5: Validate on Write (Optional)

Before saving JSON to the database, validate it:

```java
// Reject if version is not latest or structure is wrong
validationService.validateInputOrThrow(JsonTypes.BRANDING_CONFIG, json);
repository.save(json);
```

Or accept any version that has a registered schema:

```java
// Accepts v1 or v2 — validates against that version's schema
validationService.validateInputAnyVersionOrThrow(JsonTypes.BRANDING_CONFIG, json);
```

---

## Step 6: When the JSON Needs to Change — Add a Migration

When a field needs to be added, removed, or renamed, create:

1. **A migration class** (moves data from old format to new)
2. **A new version schema** (describes the new structure)

### Example: Rename `name` → `payeeName` in branding config

**Migration class:**

```
src/main/java/com/yourservice/migration/branding/BrandingV1ToV2.java
```

```java
@Component
public class BrandingV1ToV2 extends AbstractJsonVersionMigration {

    @Override public String jsonType()  { return JsonTypes.BRANDING_CONFIG; }
    @Override public int fromVersion()  { return 1; }
    @Override public int toVersion()    { return 2; }

    @Override
    protected void applyChanges(DocumentContext doc) {
        Object name = doc.read("$.name");
        if (name != null) {
            doc.put("$", "payeeName", name);
            doc.delete("$.name");
        }
    }
}
```

**V2 Schema:**

```
src/main/java/com/yourservice/migration/schema/branding/BrandingV2Schema.java
```

```java
@Component
public class BrandingV2Schema implements JsonSchemaDefinition {

    @Override public String jsonType() { return JsonTypes.BRANDING_CONFIG; }
    @Override public int version()     { return 2; }

    @Override
    public List<JsonFieldRule> rules() {
        return List.of(
            JsonFieldRule.optional("$.logoUrl", FieldType.STRING),
            JsonFieldRule.optional("$.themeColor", FieldType.STRING),
            JsonFieldRule.optional("$.payeeName", FieldType.STRING),   // new name
            JsonFieldRule.forbidden("$.name")                          // old name — must not exist
        );
    }
}
```

**That's it.** Deploy the code. All existing v1 rows auto-migrate to v2 on read. No batch scripts. No downtime.

---

## Common Migration Recipes

### Add a field

```java
protected void applyChanges(DocumentContext doc) {
    doc.put("$", "newField", "defaultValue");
}
```

### Remove a field

```java
protected void applyChanges(DocumentContext doc) {
    try { doc.delete("$.deprecatedField"); } catch (Exception ignored) {}
}
```

### Rename a field

```java
protected void applyChanges(DocumentContext doc) {
    Object val = doc.read("$.oldName");
    if (val != null) {
        doc.put("$", "newName", val);
        doc.delete("$.oldName");
    }
}
```

### Change a field type

```java
protected void applyChanges(DocumentContext doc) {
    Object val = doc.read("$.timeout");
    if (val instanceof String s) {
        doc.set("$.timeout", Integer.parseInt(s));
    }
}
```

---

## Step 7: Write Tests

Each migration and schema should have a unit test. No Spring context or DB needed.

### Test file location

Mirror the source package:

```
test/migration/
├── JsonTypesTest.java
├── branding/BrandingMigrationTest.java
├── credential/RazorpayCredentialSchemaTest.java
├── refund/RefundSchemaTest.java
└── split/SplitPaymentSchemaTest.java
```

### Test template

```java
class MySchemaTest {

    private JsonValidationService validationService;

    @BeforeEach
    void setUp() {
        JsonMigrationRegistry mr = new JsonMigrationRegistry(List.of(
            new MyV1ToV2Migration()  // add your migrations here
        ));
        mr.init();

        JsonSchemaRegistry sr = new JsonSchemaRegistry(List.of(
            new MyV1Schema(),
            new MyV2Schema()         // add your schemas here
        ));
        sr.init();

        validationService = new JsonValidationService(sr);
        validationService.setMigrationRegistry(mr);
    }

    @Test
    void validV1() {
        String json = """
            {"version":"1","field":"value"}
            """;
        assertThat(validationService.validateInputAnyVersion("MY_TYPE", json).isValid()).isTrue();
    }

    @Test
    void missingRequiredFieldFails() {
        String json = """
            {"version":"1"}
            """;
        assertThat(validationService.validateInputAnyVersion("MY_TYPE", json).isValid()).isFalse();
    }
}
```

### Testing migrations

```java
@Test
void v1MigratesToV2() {
    JsonMigrationService migrationService = new JsonMigrationService(migrationRegistry);

    String v1 = """
        {"version":"1","name":"Taj"}
        """;

    String result = migrationService.migrateToLatest("BRANDING", v1);
    DocumentContext doc = JsonPath.parse(result);

    assertThat(doc.read("$.version", String.class)).isEqualTo("2");
    assertThat(doc.read("$.payeeName", String.class)).isEqualTo("Taj");
}
```

---

## Recommended Package Structure

```
src/main/java/com/yourservice/migration/
├── JsonTypes.java                            # Type constants
│
├── branding/                                 # Migrations per type
│   ├── BrandingV1ToV2.java                   # V1 → V2
│   └── BrandingV2ToV3.java                   # V2 → V3 (future)
│
├── credential/                               # Per-gateway migrations
│   └── RazorpayCredentialV1ToV2.java
│
└── schema/                                   # Schemas (all versions)
    ├── branding/
    │   ├── BrandingV1Schema.java
    │   └── BrandingV2Schema.java
    ├── credential/
    │   ├── razorpay/RazorpayCredentialV1Schema.java
    │   ├── ccavenue/CCAvenueCredentialV1Schema.java
    │   └── ...
    ├── refund/RefundV1Schema.java
    └── split/SplitPaymentV1Schema.java
```

---

## Rules to Remember

| Rule | What happens if violated |
|---|---|
| `toVersion()` must be `fromVersion() + 1` | App fails to start |
| No gaps in chain (v1→v2 exists, v3→v4 exists, but no v2→v3) | App fails to start |
| No duplicate schemas for same (type, version) | App fails to start |
| Each migration class must have `@Component` | Not discovered by Spring |
| Each schema class must have `@Component` | Not discovered by Spring |

---

## API Quick Reference

### JsonMigrationService (inject this)

| Method | Use |
|---|---|
| `migrateToLatest(type, json)` | Read: returns migrated JSON string |
| `migrateToLatest(type, json, deserializer)` | Read: returns POJO |
| `migrateAndValidate(type, json)` | Read: migrate + validate |
| `migrateValidateOrThrow(type, json)` | Read: migrate + validate, throw if bad |
| `migrateArrayToLatest(type, jsonArray)` | Read: migrate each element in array |
| `migrateArrayToLatest(type, jsonArray, deserializer)` | Read: returns `List<T>` |
| `migrateArrayAndValidate(type, jsonArray)` | Read: migrate + validate each element |
| `needsMigration(type, json)` | Check without migrating |

### JsonValidationService (inject this)

| Method | Use |
|---|---|
| `validate(type, json)` | Read: check structure against latest schema |
| `validateInput(type, json)` | Write: must be latest version + valid structure |
| `validateInputOrThrow(type, json)` | Write: same, throws on failure |
| `validateInputAnyVersion(type, json)` | Write: any version with a registered schema |
| `validateInputAnyVersionOrThrow(type, json)` | Write: same, throws on failure |

### AbstractJsonVersionMigration (extend this)

```java
@Component
public class MyV1ToV2 extends AbstractJsonVersionMigration {
    public String jsonType()  { return "MY_TYPE"; }
    public int fromVersion()  { return 1; }
    public int toVersion()    { return 2; }

    protected void applyChanges(DocumentContext doc) {
        // Your changes here — version is auto-updated
    }
}
```

### JsonSchemaDefinition (implement this)

```java
@Component
public class MyV1Schema implements JsonSchemaDefinition {
    public String jsonType() { return "MY_TYPE"; }
    public int version()     { return 1; }

    public List<JsonFieldRule> rules() {
        return List.of(
            JsonFieldRule.required("$.field", FieldType.STRING),
            JsonFieldRule.optional("$.other", FieldType.NUMBER),
            JsonFieldRule.forbidden("$.removed"),
            JsonFieldRule.eachElement("$.items", List.of(
                JsonFieldRule.required("$.name", FieldType.STRING)
            ))
        );
    }
}
```

---

## Checklist for Adding a New Versioned JSON Column

- [ ] Add type constant to `JsonTypes.java`
- [ ] Create V1 schema in `migration/schema/{type}/`
- [ ] Add validation where JSON is read in your service
- [ ] Add validation where JSON is written in your service
- [ ] Write unit tests in `test/migration/{type}/`

## Checklist for Changing a JSON Schema

- [ ] Create migration class in `migration/{type}/` (extends `AbstractJsonVersionMigration`)
- [ ] Create new version schema in `migration/schema/{type}/`
- [ ] Add `forbidden()` rules for removed/renamed fields in new schema
- [ ] Write migration + schema unit tests
- [ ] Deploy — old data auto-migrates on read, zero downtime
