# JSON Version Migrator

A chain-based migration and validation framework for evolving versioned JSON structures stored in database columns. Automatically migrates any old version to the latest schema on read, then validates the structure is correct — zero downtime, zero batch scripts.

**Think Flyway, but for JSON documents instead of SQL schemas.**

---

## Table of Contents

- [Why?](#why)
- [Features](#features)
- [Installation](#installation)
- [Quick Start (3 Steps)](#quick-start)
- [Real-World Example: Hotel Payment Gateway Credentials](#real-world-example-hotel-payment-gateway-credentials)
- [How It Works](#how-it-works)
- [Core Components — Migration](#core-components--migration)
  - [JsonVersionMigration (interface)](#jsonversionmigration-interface)
  - [AbstractJsonVersionMigration (base class)](#abstractjsonversionmigration-base-class)
  - [JsonMigrationRegistry](#jsonmigrationregistry)
  - [JsonMigrationService](#jsonmigrationservice)
- [Core Components — Validation](#core-components--validation)
  - [JsonFieldRule](#jsonfieldrule)
  - [FieldType (enum)](#fieldtype-enum)
  - [JsonSchemaDefinition (interface)](#jsonschemadefinition-interface)
  - [JsonSchemaRegistry](#jsonschemaregistry)
  - [JsonValidationService](#jsonvalidationservice)
  - [JsonValidationResult](#jsonvalidationresult)
  - [JsonValidationException](#jsonvalidationexception)
- [Migration + Validation Combined](#migration--validation-combined)
- [Migration Recipes](#migration-recipes)
  - [Add a field](#add-a-field)
  - [Remove a field](#remove-a-field)
  - [Rename a field](#rename-a-field)
  - [Restructure (nest fields)](#restructure-nest-fields)
  - [Change a field type](#change-a-field-type)
  - [Conditional migration](#conditional-migration)
- [Validation Recipes](#validation-recipes)
  - [Define a schema for the latest version](#define-a-schema-for-the-latest-version)
  - [Validate after migration](#validate-after-migration)
  - [Validate on write (before saving to DB)](#validate-on-write-before-saving-to-db)
  - [Multiple schemas for different versions](#multiple-schemas-for-different-versions)
- [Version Format](#version-format)
- [Custom Version Field Path](#custom-version-field-path)
- [Spring Boot Auto-Configuration](#spring-boot-auto-configuration)
- [Non-Spring Usage](#non-spring-usage)
- [Testing Guide](#testing-guide)
  - [Testing a single migration step](#testing-a-single-migration-step)
  - [Testing the full chain](#testing-the-full-chain)
  - [Testing validation schemas](#testing-validation-schemas)
  - [Testing migration + validation together](#testing-migration--validation-together)
- [Project Structure & Naming Convention](#project-structure--naming-convention)
- [File Map](#file-map)
- [Rules & Constraints](#rules--constraints)
- [Error Handling](#error-handling)
- [Performance](#performance)
- [Publishing](#publishing)
- [Requirements](#requirements)
- [FAQ](#faq)
- [License](#license)

---

## Why?

When you store JSON in database columns (credentials, configs, metadata), the schema evolves over time. Fields get added, removed, or renamed. Without a migration framework:

- Old rows don't match your current DTOs — deserialization breaks or silently drops data
- Code accumulates `if/else` branches for every historical format
- Batch migration scripts are risky and cause downtime
- No audit trail for what changed between versions
- No way to verify the JSON structure is correct after migration

This library solves all of that with two simple patterns:
1. **Migration:** Write one migration class per version step — the framework chains them automatically
2. **Validation:** Define a schema for your latest version — the framework validates JSON matches it

---

## Features

| Feature | Description |
|---------|-------------|
| **Chain-based migration** | Each step handles v(N) → v(N+1). Framework chains them: v1→v2→v3→...→latest |
| **Schema validation** | Define required/optional/forbidden fields with type checking per version |
| **Auto-discovery** | Drop a `@Component` class — Spring finds it. No manual registration |
| **Fail-fast startup** | Missing chain steps or duplicate schemas = app won't start |
| **Read-only migration** | DB rows are never modified. Migration happens in memory on read |
| **Migrate + validate** | One-call `migrateAndValidate()` does both — migrate then check structure |
| **Type-safe** | Each migration/schema declares its type — types are fully independent |
| **Custom version path** | Default `$.version`, or use `$.schema_version`, `$.meta.v`, etc. |
| **Works without Spring** | Manual construction supported — use anywhere |
| **Null-safe** | Null/blank JSON returns as-is. No NPEs |
| **43 unit tests** | Full coverage of migration, validation, edge cases |

---

## Installation

### Maven (GitHub Packages)

Add the repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/ashish-v7-7/json-version-migrator</url>
    </repository>
</repositories>
```

Add the dependency:

```xml
<dependency>
    <groupId>com.simplotel</groupId>
    <artifactId>json-version-migrator-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/ashish-v7-7/json-version-migrator")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.token") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation 'com.simplotel:json-version-migrator-spring-boot-starter:1.0.0'
}
```

### Authentication

GitHub Packages requires authentication even for public packages. Add to your Maven `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>github</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>YOUR_GITHUB_TOKEN</password>
    </server>
</servers>
```

Generate a personal access token at [github.com/settings/tokens](https://github.com/settings/tokens) with `read:packages` scope.

### Local install (for development)

```bash
git clone https://github.com/ashish-v7-7/json-version-migrator.git
cd json-version-migrator
mvn clean install
```

---

## Quick Start

### Step 1: Add the dependency

See [Installation](#installation) above.

### Step 2: Write a migration

```java
import com.simplotel.jsonmigrator.AbstractJsonVersionMigration;
import com.jayway.jsonpath.DocumentContext;
import org.springframework.stereotype.Component;

@Component
public class CredentialV1ToV2 extends AbstractJsonVersionMigration {

    @Override
    public String jsonType() { return "CREDENTIAL"; }

    @Override
    public int fromVersion() { return 1; }

    @Override
    public int toVersion() { return 2; }

    @Override
    protected void applyChanges(DocumentContext doc) {
        doc.put("$.fields", "gateway_mode", "live");
    }
}
```

### Step 3: Inject and use

```java
import com.simplotel.jsonmigrator.JsonMigrationService;

@Service
public class MyService {

    private final JsonMigrationService migrationService;

    public MyService(JsonMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    public Config readConfig(String rawJson) {
        String migrated = migrationService.migrateToLatest("CREDENTIAL", rawJson);
        return objectMapper.readValue(migrated, Config.class);
    }
}
```

That's it. When v1 JSON is read, the framework applies v1→v2 automatically. Add more steps and it chains them: v1→v2→v3→...→latest.

---

## Real-World Example: Hotel Payment Gateway Credentials

Imagine you run a hotel payments platform. Each hotel stores its payment gateway credentials as JSON in a `credential` column:

### The starting point (v1)

A hotel's Razorpay credential is stored in the database as:

```json
{
  "version": "1.0",
  "fields": {
    "key_id": "rzp_live_abc123",
    "key_secret": "sk_live_xyz789"
  }
}
```

Over time, requirements change:
- **v2**: Business wants to track whether credentials are for live or test mode → add `gateway_mode`
- **v3**: API naming standardization → rename `key_id` to `api_key_id`
- **v4**: New feature needs a webhook secret, and the old `legacy_token` field should be cleaned up

### Define your type constants

```java
public final class JsonTypes {
    private JsonTypes() {}
    public static final String CREDENTIAL = "CREDENTIAL";
}
```

### Write one migration per version step

```java
// V1 → V2: Add "gateway_mode" field with default "live"
@Component
public class CredentialV1ToV2 extends AbstractJsonVersionMigration {

    @Override public String jsonType()  { return JsonTypes.CREDENTIAL; }
    @Override public int fromVersion()  { return 1; }
    @Override public int toVersion()    { return 2; }

    @Override
    protected void applyChanges(DocumentContext doc) {
        doc.put("$.fields", "gateway_mode", "live");
    }
}
```

```java
// V2 → V3: Rename "key_id" to "api_key_id"
@Component
public class CredentialV2ToV3 extends AbstractJsonVersionMigration {

    @Override public String jsonType()  { return JsonTypes.CREDENTIAL; }
    @Override public int fromVersion()  { return 2; }
    @Override public int toVersion()    { return 3; }

    @Override
    protected void applyChanges(DocumentContext doc) {
        Object keyId = doc.read("$.fields.key_id");
        if (keyId != null) {
            doc.put("$.fields", "api_key_id", keyId);
            doc.delete("$.fields.key_id");
        }
    }
}
```

```java
// V3 → V4: Add "webhook_secret", remove deprecated "legacy_token"
@Component
public class CredentialV3ToV4 extends AbstractJsonVersionMigration {

    @Override public String jsonType()  { return JsonTypes.CREDENTIAL; }
    @Override public int fromVersion()  { return 3; }
    @Override public int toVersion()    { return 4; }

    @Override
    protected void applyChanges(DocumentContext doc) {
        doc.put("$.fields", "webhook_secret", "");
        try { doc.delete("$.fields.legacy_token"); } catch (Exception ignored) {}
    }
}
```

### Define a validation schema for the latest version (v4)

```java
@Component
public class CredentialV4Schema implements JsonSchemaDefinition {

    @Override public String jsonType() { return JsonTypes.CREDENTIAL; }
    @Override public int version()     { return 4; }

    @Override
    public List<JsonFieldRule> rules() {
        return List.of(
            // Required fields — must exist with correct type
            JsonFieldRule.required("$.version", FieldType.STRING),
            JsonFieldRule.required("$.fields", FieldType.OBJECT),
            JsonFieldRule.required("$.fields.api_key_id", FieldType.STRING),
            JsonFieldRule.required("$.fields.key_secret", FieldType.STRING),
            JsonFieldRule.required("$.fields.gateway_mode", FieldType.STRING),
            JsonFieldRule.required("$.fields.webhook_secret", FieldType.STRING),

            // Forbidden fields — must NOT exist (were removed/renamed by migrations)
            JsonFieldRule.forbidden("$.fields.key_id"),          // renamed to api_key_id in v3
            JsonFieldRule.forbidden("$.fields.legacy_token")     // removed in v4
        );
    }
}
```

### Use migration + validation in your service

```java
@Service
public class PaymentGatewayService {

    private final JsonMigrationService migrationService;
    private final ObjectMapper objectMapper;

    public GatewayCredential getCredential(String hotelId) {
        String rawJson = credentialRepository.findByHotelId(hotelId).getCredentialJson();

        // Option A: Migrate + validate in one call, throw if invalid
        String migrated = migrationService.migrateValidateOrThrow(JsonTypes.CREDENTIAL, rawJson);

        // Option B: Migrate + validate, handle errors yourself
        MigrationResult result = migrationService.migrateAndValidate(JsonTypes.CREDENTIAL, rawJson);
        if (!result.isValid()) {
            log.error("Credential validation failed for hotel {}: {}",
                hotelId, result.getValidation().getErrorSummary());
            throw new BadRequestException("Invalid credential structure");
        }
        String migrated = result.getJson();

        return objectMapper.readValue(migrated, GatewayCredential.class);
    }
}
```

### What happens at runtime

```
DB Row (v1, stored 6 months ago)          In Memory
┌─────────────────────────┐
│ version: "1.0"          │──→ V1→V2: add gateway_mode="live"
│ key_id: "rzp_..."       │──→ V2→V3: rename key_id → api_key_id
│ key_secret: "sk_..."    │──→ V3→V4: add webhook_secret, remove legacy_token
└─────────────────────────┘──→ Validate against CredentialV4Schema
                                      ┌──────────────────────────┐
                                      │ version: "4"             │  ✓ required STRING
                                      │ api_key_id: "rzp_..."    │  ✓ required STRING
                                      │ key_secret: "sk_..."     │  ✓ required STRING
                                      │ gateway_mode: "live"     │  ✓ required STRING
                                      │ webhook_secret: ""       │  ✓ required STRING
                                      │ (no key_id)              │  ✓ forbidden absent
                                      │ (no legacy_token)        │  ✓ forbidden absent
                                      └──────────────────────────┘
                                                   │
                                                   ▼
                                          Deserialize → DTO
```

- **No batch migration scripts** — old rows are migrated on-the-fly when read
- **No downtime** — deploy the new code and it just works
- **No DB writes** — the original row is untouched (write back explicitly if you want)
- **Structural guarantee** — validation catches bad data before it hits your DTO
- **New hotels** get v4 format from day one — the migration chain is only for old data

---

## How It Works

### Migration Flow

```
DB Row (v1 JSON)
    │
    ▼
JsonMigrationService.migrateToLatest("CREDENTIAL", json)
    │
    ├── 1. Parse JSON → DocumentContext
    ├── 2. Read $.version → 1
    ├── 3. Ask registry for latest version → 4
    ├── 4. Get chain: [V1→V2, V2→V3, V3→V4]
    ├── 5. Apply each step sequentially (mutate in-place)
    ├── 6. Return migrated JSON string
    │
    ▼
Migrated JSON (v4)
```

### Migration + Validation Flow

```
DB Row (v1 JSON)
    │
    ▼
JsonMigrationService.migrateAndValidate("CREDENTIAL", json)
    │
    ├── 1. Run full migration chain → v4 JSON
    ├── 2. Look up CredentialV4Schema
    ├── 3. Check each rule:
    │      ├── $.fields.api_key_id → exists? STRING? ✓
    │      ├── $.fields.key_secret → exists? STRING? ✓
    │      ├── $.fields.key_id → absent? ✓ (forbidden)
    │      └── ...
    ├── 4. Return MigrationResult { json, validationResult }
    │
    ▼
MigrationResult
    ├── .getJson()         → migrated JSON string
    ├── .isValid()         → true/false
    └── .getValidation()   → JsonValidationResult with error details
```

### Key Properties

| Property | Detail |
|----------|--------|
| **Read-only** | Migration happens in memory. The DB row is NOT modified |
| **Lazy** | Only triggers when the version is behind. Latest-version reads are a no-op |
| **Sequential** | Each step goes from version N to N+1. Multi-version jumps are composed by chaining |
| **Fail-fast** | Missing chain steps = startup failure. Duplicate schemas = startup failure |
| **Opt-in validation** | No schema registered = validation always passes. Add schemas when ready |
| **Type-independent** | `"CREDENTIAL"` and `"BRANDING"` have completely separate chains and schemas |
| **Null-safe** | Null/blank JSON → returns as-is (migration) or valid result (validation) |

---

## Core Components — Migration

### `JsonVersionMigration` (interface)

The contract for a single migration step.

```java
public interface JsonVersionMigration {
    String jsonType();      // e.g., "CREDENTIAL", "BRANDING_CONFIG"
    int fromVersion();      // e.g., 1
    int toVersion();        // must be fromVersion() + 1
    void migrate(DocumentContext doc);
}
```

| Method | Returns | Rule |
|--------|---------|------|
| `jsonType()` | `String` | Your type identifier. Must be non-null, non-blank |
| `fromVersion()` | `int` | The version this step reads |
| `toVersion()` | `int` | Must be exactly `fromVersion() + 1` |
| `migrate(doc)` | `void` | Mutate the document. Must update `$.version` |

### `AbstractJsonVersionMigration` (base class)

**Recommended.** Auto-updates `$.version` after your changes. You only implement `applyChanges(doc)`.

```java
public abstract class AbstractJsonVersionMigration implements JsonVersionMigration {

    @Override
    public final void migrate(DocumentContext doc) {
        applyChanges(doc);                                    // your changes
        doc.put("$", "version", String.valueOf(toVersion())); // auto-bump
    }

    protected abstract void applyChanges(DocumentContext doc);
}
```

### `JsonMigrationRegistry`

Auto-discovers all `JsonVersionMigration` beans at startup. Indexes by `(type, fromVersion)`. Validates:
- No gaps in the chain (v1→v2 exists, v2→v3 missing = **startup failure**)
- Each step increments by exactly 1 (v1→v3 = **startup failure**)

Key methods:

| Method | Description |
|--------|-------------|
| `init()` | Index + validate. Called by Spring or manually |
| `getLatestVersion(type)` | Highest version for a type (default: 1) |
| `getMigrationChain(type, fromVersion)` | Ordered steps from `fromVersion` → latest |
| `getRegisteredTypes()` | All types with migrations |

### `JsonMigrationService`

The main public API. Inject this into your services.

| Method | Description |
|--------|-------------|
| `migrateToLatest(type, json)` | Migrate to the latest version. No-op if already latest |
| `migrateToVersion(type, json, target)` | Migrate to a specific version (not necessarily latest) |
| `migrateAndValidate(type, json)` | Migrate + validate. Returns `MigrationResult` |
| `migrateValidateOrThrow(type, json)` | Migrate + validate. Throws `JsonValidationException` if invalid |
| `needsMigration(type, json)` | Check if migration is needed without doing it |
| `getCurrentVersion(json)` | Read the version number from a JSON string |

---

## Core Components — Validation

### `JsonFieldRule`

Describes validation rules for a single field. Three factory methods:

```java
// Field MUST exist and be non-null, with the expected type
JsonFieldRule.required("$.fields.api_key_id", FieldType.STRING)

// Field MAY exist. If present, must match the type
JsonFieldRule.optional("$.fields.webhook_secret", FieldType.STRING)

// Field with default value hint (for documentation / error context)
JsonFieldRule.optional("$.fields.gateway_mode", FieldType.STRING, "live")

// Field must NOT exist (deprecated / removed)
JsonFieldRule.forbidden("$.fields.legacy_token")
```

### `FieldType` (enum)

Supported type checks:

| FieldType | Java type | JSON example |
|-----------|-----------|--------------|
| `STRING` | `String` | `"hello"` |
| `NUMBER` | `Number` (Integer, Double, etc.) | `42`, `3.14` |
| `BOOLEAN` | `Boolean` | `true`, `false` |
| `OBJECT` | `Map` | `{"key": "val"}` |
| `ARRAY` | `Collection` (List) | `[1, 2, 3]` |
| `ANY` | any type | anything — skips type check |

### `JsonSchemaDefinition` (interface)

Define the expected structure for a specific `(type, version)`:

```java
public interface JsonSchemaDefinition {
    String jsonType();          // must match your migration type string
    int version();              // the version this schema describes
    List<JsonFieldRule> rules();// the field rules
}
```

Register as a Spring `@Component` — the framework auto-discovers it.

### `JsonSchemaRegistry`

Collects all `JsonSchemaDefinition` beans. Validates no duplicates for the same `(type, version)`.

| Method | Description |
|--------|-------------|
| `init()` | Index + validate. Called by Spring or manually |
| `getSchema(type, version)` | Schema for exact version, or `null` |
| `getLatestSchema(type)` | Schema for highest registered version |
| `hasSchema(type)` | True if any schema registered for the type |

### `JsonValidationService`

The public validation API.

| Method | Description |
|--------|-------------|
| `validate(type, json)` | Validate against the **latest** schema. Returns `JsonValidationResult` |
| `validate(type, version, json)` | Validate against a **specific** version's schema |
| `validateOrThrow(type, json)` | Validate. Throws `JsonValidationException` if invalid |
| `validateAgainst(schema, json)` | Validate against a specific `JsonSchemaDefinition` (for testing) |

**Opt-in behavior:** If no schema is registered for a type, `validate()` returns a valid result. You only get validation when you define a schema.

### `JsonValidationResult`

Holds the results of validation.

```java
JsonValidationResult result = validationService.validate("CREDENTIAL", json);

result.isValid();         // true if no errors
result.getErrorCount();   // number of errors
result.getErrors();       // List<JsonValidationError>
result.getErrorSummary(); // human-readable multi-line string
result.throwIfInvalid("CREDENTIAL"); // throws JsonValidationException
```

Each `JsonValidationError` has:
- `getCode()` — `MISSING_REQUIRED_FIELD`, `TYPE_MISMATCH`, `FORBIDDEN_FIELD_PRESENT`
- `getJsonPath()` — the path that failed (e.g., `$.fields.key_secret`)
- `getMessage()` — human-readable description

### `JsonValidationException`

Thrown by `throwIfInvalid()` or `validateOrThrow()`. Contains the full `JsonValidationResult`:

```java
try {
    validationService.validateOrThrow("CREDENTIAL", json);
} catch (JsonValidationException e) {
    e.getMessage();              // "JSON validation failed for 'CREDENTIAL' (2 errors):\n..."
    e.getResult().getErrors();   // access individual errors
}
```

---

## Migration + Validation Combined

The `JsonMigrationService` offers two combined methods:

### `migrateAndValidate(type, json)` — get the result, decide what to do

```java
MigrationResult result = migrationService.migrateAndValidate("CREDENTIAL", rawJson);

String migratedJson = result.getJson();    // always available (migrated JSON)
boolean isValid = result.isValid();         // did validation pass?

if (!result.isValid()) {
    // Log the errors and decide: reject, fallback, alert, etc.
    log.error("Validation failed: {}", result.getValidation().getErrorSummary());
    // Each error has: code, jsonPath, message
    for (JsonValidationError error : result.getValidation().getErrors()) {
        log.error("  {} at {} — {}", error.getCode(), error.getJsonPath(), error.getMessage());
    }
}
```

### `migrateValidateOrThrow(type, json)` — fail fast

```java
// Throws JsonValidationException if migration result doesn't match schema
String migrated = migrationService.migrateValidateOrThrow("CREDENTIAL", rawJson);
// If we get here, json is migrated AND validated
```

### When validation is skipped

- `null` or blank JSON → returns valid (nothing to validate)
- No `JsonValidationService` configured → returns valid
- No schema registered for the type → returns valid
- Schema registered but JSON passes all rules → returns valid

---

## Migration Recipes

### Add a field

```java
protected void applyChanges(DocumentContext doc) {
    // Add at nested path with default value
    doc.put("$.fields", "gateway_mode", "live");

    // Add at root level
    doc.put("$", "metadata", new HashMap<>());

    // Add nested object
    Map<String, Object> config = Map.of("timeout", 30, "retries", 3);
    doc.put("$", "settings", config);
}
```

### Remove a field

```java
protected void applyChanges(DocumentContext doc) {
    // Safe delete — won't throw if field doesn't exist
    try {
        doc.delete("$.fields.legacy_token");
    } catch (Exception ignored) {
        // Field may not exist in all documents — that's fine
    }
}
```

### Rename a field

```java
protected void applyChanges(DocumentContext doc) {
    // Copy value to new key, then delete old key
    Object value = doc.read("$.fields.key_id");
    if (value != null) {
        doc.put("$.fields", "api_key_id", value);
        doc.delete("$.fields.key_id");
    }
}
```

### Restructure (nest fields)

```java
protected void applyChanges(DocumentContext doc) {
    // Move flat fields into a nested "oauth" object
    Object clientId = doc.read("$.fields.client_id");
    Object clientSecret = doc.read("$.fields.client_secret");

    Map<String, Object> oauth = new LinkedHashMap<>();
    if (clientId != null) oauth.put("client_id", clientId);
    if (clientSecret != null) oauth.put("client_secret", clientSecret);

    doc.put("$.fields", "oauth", oauth);
    doc.delete("$.fields.client_id");
    doc.delete("$.fields.client_secret");
}
```

### Change a field type

```java
protected void applyChanges(DocumentContext doc) {
    // Convert string "30" to integer 30
    Object timeout = doc.read("$.settings.timeout");
    if (timeout instanceof String s) {
        doc.set("$.settings.timeout", Integer.parseInt(s));
    }
}
```

### Conditional migration

```java
protected void applyChanges(DocumentContext doc) {
    // Only add field if another field has a specific value
    Object gatewayId = doc.read("$.gateway_id");
    if ("RAZORPAY".equals(gatewayId)) {
        doc.put("$.fields", "razorpay_account_id", "");
    }
}
```

---

## Validation Recipes

### Define a schema for the latest version

```java
@Component
public class CredentialV4Schema implements JsonSchemaDefinition {

    @Override public String jsonType() { return "CREDENTIAL"; }
    @Override public int version()     { return 4; }

    @Override
    public List<JsonFieldRule> rules() {
        return List.of(
            // Required: must exist with correct type
            JsonFieldRule.required("$.version", FieldType.STRING),
            JsonFieldRule.required("$.fields", FieldType.OBJECT),
            JsonFieldRule.required("$.fields.api_key_id", FieldType.STRING),
            JsonFieldRule.required("$.fields.key_secret", FieldType.STRING),
            JsonFieldRule.required("$.fields.gateway_mode", FieldType.STRING),
            JsonFieldRule.required("$.fields.webhook_secret", FieldType.STRING),

            // Optional: can be absent, but if present must be STRING
            JsonFieldRule.optional("$.fields.razorpay_account_id", FieldType.STRING),

            // Forbidden: must NOT exist (were removed/renamed by migrations)
            JsonFieldRule.forbidden("$.fields.key_id"),
            JsonFieldRule.forbidden("$.fields.legacy_token")
        );
    }
}
```

### Validate after migration

```java
// Migrate first, then validate
String migrated = migrationService.migrateToLatest("CREDENTIAL", rawJson);
JsonValidationResult result = validationService.validate("CREDENTIAL", migrated);

if (!result.isValid()) {
    // handle errors
}
```

Or use the combined method:

```java
// One call — migrates then validates
String migrated = migrationService.migrateValidateOrThrow("CREDENTIAL", rawJson);
```

### Validate on write (before saving to DB)

```java
public void saveCredential(String payeeId, GatewayCredential credential) {
    String json = objectMapper.writeValueAsString(credential);

    // Validate before writing to DB — catch bad data early
    validationService.validateOrThrow("CREDENTIAL", json);

    repository.save(payeeId, json);
}
```

### Multiple schemas for different versions

You can define schemas for multiple versions (useful during gradual rollouts):

```java
@Component
public class CredentialV3Schema implements JsonSchemaDefinition {
    public String jsonType() { return "CREDENTIAL"; }
    public int version()     { return 3; }
    public List<JsonFieldRule> rules() { /* v3 rules */ }
}

@Component
public class CredentialV4Schema implements JsonSchemaDefinition {
    public String jsonType() { return "CREDENTIAL"; }
    public int version()     { return 4; }
    public List<JsonFieldRule> rules() { /* v4 rules */ }
}
```

Then validate against a specific version:

```java
// Validate against v3 schema specifically
JsonValidationResult result = validationService.validate("CREDENTIAL", 3, json);
```

---

## Version Format

The library reads `$.version` from the JSON:

| JSON value | Parsed as | Notes |
|------------|-----------|-------|
| `"1.0"` | 1 | Legacy dot format — major part extracted |
| `"3"` | 3 | Integer string |
| `5` (number) | 5 | Native JSON number |
| `null` / missing | 1 | Pre-versioning rows treated as v1 |
| `"abc"` | 1 | Unparseable → warning logged, defaults to v1 |

After migration, version is written as a plain integer string: `"2"`, `"3"`, etc.

---

## Custom Version Field Path

By default, the version is read from `$.version`. Override it:

```java
// Non-Spring: pass to constructor
var service = new JsonMigrationService(registry, "$.schema_version");

// Spring: define your own bean
@Bean
public JsonMigrationService jsonMigrationService(
        JsonMigrationRegistry registry, JsonValidationService validationService) {
    var service = new JsonMigrationService(registry, "$.meta.schema_version");
    service.setValidationService(validationService);
    return service;
}
```

> **Important:** When using a custom path, your migration classes must write to the same path. `AbstractJsonVersionMigration` always writes to `$.version`. Implement the `JsonVersionMigration` interface directly for custom paths.

---

## Spring Boot Auto-Configuration

When used with Spring Boot 3.x, the library auto-configures these beans:

| Bean | What it does |
|------|-------------|
| `JsonMigrationRegistry` | Collects all `JsonVersionMigration` `@Component` beans, validates chain |
| `JsonSchemaRegistry` | Collects all `JsonSchemaDefinition` `@Component` beans, validates no duplicates |
| `JsonValidationService` | Ready to inject for standalone validation |
| `JsonMigrationService` | Ready to inject — wired with both registries and validation |

**No `@EnableXxx` annotation needed.** Just add the dependency and create your `@Component` classes.

All beans use `@ConditionalOnMissingBean` — define your own to override.

---

## Non-Spring Usage

The library works without Spring Boot — construct manually:

```java
// 1. Create and init migration registry
var migrationRegistry = new JsonMigrationRegistry(List.of(
    new CredentialV1ToV2(),
    new CredentialV2ToV3()
));
migrationRegistry.init();

// 2. Create and init schema registry
var schemaRegistry = new JsonSchemaRegistry(List.of(
    new CredentialV3Schema()
));
schemaRegistry.init();

// 3. Create services
var validationService = new JsonValidationService(schemaRegistry);
var migrationService = new JsonMigrationService(migrationRegistry);
migrationService.setValidationService(validationService);

// 4. Use
String migrated = migrationService.migrateValidateOrThrow("CREDENTIAL", rawJson);
```

---

## Testing Guide

### Testing a single migration step

```java
@Test
void testV1ToV2AddGatewayMode() {
    CredentialV1ToV2 migration = new CredentialV1ToV2();

    String v1 = """
        {"version":"1.0","fields":{"key_id":"rzp_123","key_secret":"sk_456"}}
        """;

    DocumentContext doc = JsonPath.parse(v1);
    migration.migrate(doc);

    assertThat(doc.read("$.version", String.class)).isEqualTo("2");
    assertThat(doc.read("$.fields.gateway_mode", String.class)).isEqualTo("live");
    assertThat(doc.read("$.fields.key_id", String.class)).isEqualTo("rzp_123"); // unchanged
}
```

### Testing the full chain

```java
@Test
void testFullMigrationV1ToV4() {
    var registry = new JsonMigrationRegistry(List.of(
        new CredentialV1ToV2(), new CredentialV2ToV3(),
        new CredentialV3ToV4()));
    registry.init();
    var service = new JsonMigrationService(registry);

    String v1 = """
        {"version":"1.0","fields":{"key_id":"rzp_123","key_secret":"sk_456"}}
        """;

    String result = service.migrateToLatest("CREDENTIAL", v1);
    DocumentContext doc = JsonPath.parse(result);

    assertThat(doc.read("$.version", String.class)).isEqualTo("4");
    assertThat(doc.read("$.fields.api_key_id", String.class)).isEqualTo("rzp_123"); // renamed
    assertThat(doc.read("$.fields.gateway_mode", String.class)).isEqualTo("live");   // added
    assertThat(doc.read("$.fields.webhook_secret", String.class)).isEqualTo("");     // added
}
```

### Testing validation schemas

```java
@Test
void testValidationCatchesMissingField() {
    var schemaRegistry = new JsonSchemaRegistry(List.of(new CredentialV4Schema()));
    schemaRegistry.init();
    var validationService = new JsonValidationService(schemaRegistry);

    // Missing key_secret
    String json = """
        {"version":"4","fields":{"api_key_id":"rzp","gateway_mode":"live","webhook_secret":""}}
        """;

    JsonValidationResult result = validationService.validate("CREDENTIAL", json);

    assertThat(result.isValid()).isFalse();
    assertThat(result.getErrors()).anyMatch(e ->
        e.getCode() == JsonValidationError.ErrorCode.MISSING_REQUIRED_FIELD
        && e.getJsonPath().equals("$.fields.key_secret"));
}
```

### Testing migration + validation together

```java
@Test
void testMigrateAndValidateEndToEnd() {
    // Setup: registries + services
    var migrationRegistry = new JsonMigrationRegistry(List.of(
        new CredentialV1ToV2(), new CredentialV2ToV3(), new CredentialV3ToV4()));
    migrationRegistry.init();

    var schemaRegistry = new JsonSchemaRegistry(List.of(new CredentialV4Schema()));
    schemaRegistry.init();

    var validationService = new JsonValidationService(schemaRegistry);
    var migrationService = new JsonMigrationService(migrationRegistry);
    migrationService.setValidationService(validationService);

    // Test: v1 JSON with all required data → should pass
    String v1 = """
        {"version":"1.0","fields":{"key_id":"rzp_123","key_secret":"sk_456"}}
        """;

    MigrationResult result = migrationService.migrateAndValidate("CREDENTIAL", v1);

    assertThat(result.isValid()).isTrue();
    assertThat(result.getJson()).contains("api_key_id");
}
```

---

## Project Structure & Naming Convention

### Recommended layout in your project

```
src/main/java/com/yourcompany/migration/
├── JsonTypes.java                  # String constants for type names
├── credential/
│   ├── CredentialV1ToV2.java       # Migration: v1 → v2
│   ├── CredentialV2ToV3.java       # Migration: v2 → v3
│   ├── CredentialV3ToV4.java       # Migration: v3 → v4
│   └── CredentialV4Schema.java     # Validation: v4 schema
├── branding/
│   ├── BrandingV1ToV2.java
│   └── BrandingV2Schema.java
└── refund/
    ├── RefundV1ToV2.java
    └── RefundV2Schema.java
```

### Naming patterns

| What | Pattern | Example |
|------|---------|---------|
| Migration class | `{Type}V{from}ToV{to}.java` | `CredentialV2ToV3.java` |
| Schema class | `{Type}V{version}Schema.java` | `CredentialV4Schema.java` |
| Type constants | `JsonTypes.java` | `JsonTypes.CREDENTIAL` |
| Sub-package | `migration/{type}/` | `migration/credential/` |

---

## File Map

```
json-version-migrator/
├── pom.xml
├── README.md
├── LICENSE
├── .gitignore
├── .github/workflows/publish.yml
├── docs/index.html
│
├── src/main/java/com/simplotel/jsonmigrator/
│   │
│   │   # ── Migration ──
│   ├── JsonVersionMigration.java            # Interface: single step contract
│   ├── AbstractJsonVersionMigration.java    # Base class: auto version bump
│   ├── JsonMigrationRegistry.java           # Collects + validates migration chain
│   ├── JsonMigrationService.java            # Public API: migrate + validate
│   │
│   │   # ── Validation ──
│   ├── validation/
│   │   ├── FieldType.java                   # Enum: STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, ANY
│   │   ├── JsonFieldRule.java               # Rule: required / optional / forbidden
│   │   ├── JsonSchemaDefinition.java        # Interface: define rules per (type, version)
│   │   ├── JsonSchemaRegistry.java          # Collects + validates schema definitions
│   │   ├── JsonValidationService.java       # Public API: validate JSON
│   │   ├── JsonValidationResult.java        # Result: errors list, isValid(), throwIfInvalid()
│   │   ├── JsonValidationError.java         # Single error: code, path, message
│   │   └── JsonValidationException.java     # RuntimeException with full result
│   │
│   │   # ── Spring Boot ──
│   └── autoconfigure/
│       └── JsonMigratorAutoConfiguration.java
│
├── src/main/resources/META-INF/spring/
│   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│
└── src/test/java/com/simplotel/jsonmigrator/
    ├── JsonMigrationServiceTest.java        # 23 migration tests
    └── validation/
        └── JsonValidationServiceTest.java   # 20 validation tests
```

---

## Rules & Constraints

### Migration rules

| Rule | What happens if violated |
|------|--------------------------|
| `toVersion()` must equal `fromVersion() + 1` | **Startup failure** — `IllegalStateException` |
| No gaps in chain (e.g., v1→v2, v3→v4 but no v2→v3) | **Startup failure** — `IllegalStateException` |
| One migration class per step | Each class handles exactly one transition |
| Must be a Spring `@Component` (or passed to registry) | Not discovered otherwise |
| Types are independent | `"CREDENTIAL"` chain doesn't affect `"BRANDING"` |

### Validation rules

| Rule | What happens if violated |
|------|--------------------------|
| No duplicate schemas for same `(type, version)` | **Startup failure** — `IllegalStateException` |
| Must be a Spring `@Component` (or passed to registry) | Not discovered otherwise |
| Validation is opt-in | No schema = validation always passes |
| Null/blank JSON → `validate()` returns error | Use `migrateAndValidate()` which handles null gracefully |

### General rules

| Rule | Detail |
|------|--------|
| Null-safe | `migrateToLatest(type, null)` → returns `null`. `migrateAndValidate(type, null)` → valid result |
| Thread-safe | Registries are built once at startup. Services are stateless |
| No DB writes | Migration is read-only. Write back explicitly if needed |

---

## Error Handling

### Migration errors

| Scenario | Error type | When |
|----------|-----------|------|
| Gap in chain | `IllegalStateException` | App startup |
| Wrong version increment | `IllegalStateException` | App startup |
| Missing step at runtime | `IllegalStateException` | `migrateToLatest()` call |
| Null/blank JSON | No error — returns as-is | Always |

### Validation errors

| Error code | Meaning | Example |
|------------|---------|---------|
| `MISSING_REQUIRED_FIELD` | Required field doesn't exist or is null | `$.fields.key_secret` is missing |
| `TYPE_MISMATCH` | Field exists but has wrong type | `$.fields.api_key_id` is NUMBER, expected STRING |
| `FORBIDDEN_FIELD_PRESENT` | Deprecated field still exists | `$.fields.key_id` should have been removed |

### Handling validation failures

```java
// Option 1: Check and handle
MigrationResult result = migrationService.migrateAndValidate("CREDENTIAL", json);
if (!result.isValid()) {
    log.error("Bad credential for payee {}: {}", payeeId, result.getValidation().getErrorSummary());
    throw new BadRequestException("Invalid credential format");
}

// Option 2: Throw automatically
try {
    String migrated = migrationService.migrateValidateOrThrow("CREDENTIAL", json);
} catch (JsonValidationException e) {
    // e.getMessage() = "JSON validation failed for 'CREDENTIAL' (2 errors):\n..."
    // e.getResult().getErrors() = individual error objects
}

// Option 3: Standalone validation
validationService.validateOrThrow("CREDENTIAL", json);
```

---

## Performance

| Scenario | Cost |
|----------|------|
| JSON already at latest version | Near-zero (version check + short-circuit) |
| Migration needed (per step) | ~microseconds (JsonPath operations) |
| Validation (per rule) | ~microseconds (JsonPath read + type check) |
| No schema registered | Zero (validation skipped) |

Migration runs once per read. If you cache the DTO, migration runs once per cache miss.

---

## Publishing

### GitHub Packages (automated)

Create a release on GitHub → the Actions workflow (`.github/workflows/publish.yml`) publishes automatically.

### Manual publish

```bash
mvn clean deploy -DskipTests
```

Requires `GITHUB_TOKEN` with `write:packages` scope.

### Local install

```bash
mvn clean install
```

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Java | 17+ |
| JsonPath | 2.9+ |
| Spring Boot | 3.x (optional — works without Spring) |
| SLF4J | 2.x (consumers provide implementation) |

---

## FAQ

### What if I add a migration but forget the one before it?

The app won't start. The registry validates the chain at startup and throws `IllegalStateException` if there's a gap.

### What if the JSON has no `"version"` field at all?

Treated as version `1`. All pre-existing data (whether it has `"version": "1.0"` or no version) starts at v1.

### Does migration modify the database?

**No.** Migration is read-only (in memory). The DB row keeps its original JSON. Write back explicitly if you want to persist the migrated version.

### Is validation mandatory?

**No.** Validation is opt-in. If you don't define any `JsonSchemaDefinition`, validation always passes. Add schemas when you're ready.

### Can I validate without migrating?

Yes. Use `JsonValidationService` directly:

```java
validationService.validate("CREDENTIAL", json);       // against latest schema
validationService.validate("CREDENTIAL", 3, json);    // against specific version
validationService.validateOrThrow("CREDENTIAL", json); // throws if invalid
```

### Can I inject Spring beans into migrations/schemas?

Yes. They are `@Component` beans, so you can inject anything. But keep them simple — prefer pure JSON transformations.

### Can two types share a migration or schema?

No. Each migration/schema belongs to exactly one type. Write separate classes.

### What happens if I have migrations but no schema?

Migration works normally. Validation is skipped (returns valid). You can add schemas later.

### What happens if I have a schema but no migrations?

Validation works normally against whatever JSON is passed in. You can use validation standalone for write-path checks.

### How do I test migrations in CI without a database?

Migrations don't need a database — they work on JSON strings. Create test JSON, call `migrate()`, assert on the result. See [Testing Guide](#testing-guide).

### Can I run migrations eagerly (update DB rows)?

The library doesn't do this automatically, but you can build a batch job:

```java
// Pseudocode for eager migration
for (Row row : repository.findByVersionLessThan(latestVersion)) {
    String migrated = migrationService.migrateToLatest("CREDENTIAL", row.getJson());
    repository.updateJson(row.getId(), migrated);
}
```

---

## License

MIT License. See [LICENSE](LICENSE).
