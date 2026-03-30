# JSON Version Migrator

A chain-based migration framework for evolving versioned JSON structures stored in database columns. Automatically migrates any old version to the latest schema on read — zero downtime, zero batch scripts.

**Think Flyway, but for JSON documents instead of SQL schemas.**

---

## Why?

When you store JSON in database columns (credentials, configs, metadata), the schema evolves over time. Fields get added, removed, or renamed. Without a migration framework:

- Old rows don't match your current DTOs — deserialization breaks or silently drops data
- Code accumulates `if/else` branches for every historical format
- Batch migration scripts are risky and cause downtime
- No audit trail for what changed between versions

This library solves all of that with a simple pattern: **write one migration class per version step, and the framework chains them automatically.**

---

## Quick Start

### 1. Add the dependency

**Maven** (GitHub Packages):

```xml
<dependency>
    <groupId>com.simplotel</groupId>
    <artifactId>json-version-migrator-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle:**

```groovy
implementation 'com.simplotel:json-version-migrator-spring-boot-starter:1.0.0'
```

> Configure GitHub Packages registry in your `settings.xml` or `build.gradle` — see [GitHub Packages docs](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).

### 2. Write your first migration

```java
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
        // Add a new field with default value
        doc.put("$.fields", "gateway_mode", "live");
    }
}
```

### 3. Inject and use

```java
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

That's it. When a v1 JSON is read, the framework automatically applies v1→v2 (and any further steps) before deserialization.

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

### Step 1: Define your type constants

```java
public final class JsonTypes {
    private JsonTypes() {}
    public static final String CREDENTIAL = "CREDENTIAL";
}
```

### Step 2: Write one migration per version step

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

### Step 3: Use in your service

```java
@Service
public class PaymentGatewayService {

    private final JsonMigrationService migrationService;
    private final ObjectMapper objectMapper;
    private final CredentialRepository credentialRepository;

    public GatewayCredential getCredential(String hotelId) {
        // 1. Read raw JSON from database
        String rawJson = credentialRepository.findByHotelId(hotelId).getCredentialJson();
        // rawJson = {"version":"1.0","fields":{"key_id":"rzp_live_abc123","key_secret":"sk_live_xyz789"}}

        // 2. One line — migrates v1 → v2 → v3 → v4 automatically
        String migrated = migrationService.migrateToLatest(JsonTypes.CREDENTIAL, rawJson);
        // migrated = {"version":"4","fields":{"api_key_id":"rzp_live_abc123","key_secret":"sk_live_xyz789","gateway_mode":"live","webhook_secret":""}}

        // 3. Deserialize into your current DTO — no version mismatch, no missing fields
        return objectMapper.readValue(migrated, GatewayCredential.class);
    }
}
```

### What just happened?

The hotel's credential was stored 6 months ago as v1. When the service reads it today, the framework automatically:

```
DB Row                          In Memory
┌─────────────────────┐
│ version: "1.0"      │──→ V1→V2: add gateway_mode="live"
│ key_id: "rzp_..."   │──→ V2→V3: rename key_id → api_key_id
│ key_secret: "sk_..."│──→ V3→V4: add webhook_secret, remove legacy_token
└─────────────────────┘
                                ┌──────────────────────────┐
                                │ version: "4"             │
                                │ api_key_id: "rzp_..."    │
                                │ key_secret: "sk_..."     │
                                │ gateway_mode: "live"     │
                                │ webhook_secret: ""       │
                                └──────────────────────────┘
```

- **No batch migration scripts** — old rows are migrated on-the-fly when read
- **No downtime** — deploy the new code and it just works
- **No DB writes** — the original row is untouched (write back explicitly if you want)
- **New hotels** get v4 format from day one — the migration chain is only for old data

---

## How It Works

```
DB Row (v1 JSON)
    │
    ▼
JsonMigrationService.migrateToLatest("CREDENTIAL", json)
    │
    ├── reads $.version → 1
    ├── asks registry for latest → 3
    ├── gets chain: [V1→V2, V2→V3]
    │
    ▼ applies each step sequentially
Migrated JSON (v3)
    │
    ▼
Jackson / Gson deserialization → DTO
```

### Key properties

| Property | Detail |
|----------|--------|
| **Read-only** | Migration happens in memory. The DB row is NOT modified. |
| **Lazy** | Only triggers when the version is behind. Latest-version reads are a no-op. |
| **Sequential** | Each step goes from version N to N+1. Multi-version jumps are composed by chaining. |
| **Fail-fast** | Missing steps in the chain cause a startup failure (not a runtime surprise). |
| **Type-safe** | Each migration declares its `jsonType()` — types are independent and don't interfere. |

---

## Core Components

### `JsonVersionMigration` (interface)

```java
public interface JsonVersionMigration {
    String jsonType();      // e.g., "CREDENTIAL", "BRANDING_CONFIG"
    int fromVersion();      // e.g., 1
    int toVersion();        // must be fromVersion() + 1
    void migrate(DocumentContext doc);
}
```

### `AbstractJsonVersionMigration` (recommended base class)

Auto-updates `$.version` after your changes. You only implement `applyChanges(doc)`.

### `JsonMigrationRegistry`

Auto-discovers all `JsonVersionMigration` beans. Validates chain integrity at startup. Provides `getMigrationChain(type, fromVersion)`.

### `JsonMigrationService`

The public API you inject into your services:

| Method | Description |
|--------|-------------|
| `migrateToLatest(type, json)` | Migrate to the latest version |
| `migrateToVersion(type, json, targetVersion)` | Migrate to a specific version |
| `needsMigration(type, json)` | Check without migrating |
| `getCurrentVersion(json)` | Read the version from a JSON string |

---

## Recipes

### Add a field

```java
protected void applyChanges(DocumentContext doc) {
    doc.put("$.fields", "gateway_mode", "live");
}
```

### Remove a field

```java
protected void applyChanges(DocumentContext doc) {
    try { doc.delete("$.fields.legacy_token"); } catch (Exception ignored) {}
}
```

### Rename a field

```java
protected void applyChanges(DocumentContext doc) {
    Object val = doc.read("$.fields.key_id");
    if (val != null) {
        doc.put("$.fields", "api_key_id", val);
        doc.delete("$.fields.key_id");
    }
}
```

### Restructure (move fields into nested object)

```java
protected void applyChanges(DocumentContext doc) {
    Object clientId = doc.read("$.fields.client_id");
    Object secret = doc.read("$.fields.client_secret");

    Map<String, Object> oauth = new LinkedHashMap<>();
    if (clientId != null) oauth.put("client_id", clientId);
    if (secret != null) oauth.put("client_secret", secret);

    doc.put("$.fields", "oauth", oauth);
    doc.delete("$.fields.client_id");
    doc.delete("$.fields.client_secret");
}
```

---

## Version Format

The library reads `$.version` from the JSON (configurable via constructor):

| JSON value | Parsed as | Notes |
|------------|-----------|-------|
| `"1.0"` | 1 | Legacy dot format — major part extracted |
| `"3"` | 3 | Integer string |
| `5` (number) | 5 | Native JSON number |
| `null` / missing | 1 | Pre-versioning rows treated as v1 |
| `"abc"` | 1 | Unparseable → warning logged, defaults to v1 |

After migration, version is written as a plain integer string: `"2"`, `"3"`, etc.

### Custom version field

```java
// Use "$.schema_version" instead of "$.version"
new JsonMigrationService(registry, "$.schema_version");
```

---

## Non-Spring Usage

The library works without Spring Boot — just construct the objects manually:

```java
var registry = new JsonMigrationRegistry(List.of(
    new CredentialV1ToV2(),
    new CredentialV2ToV3()
));
registry.init();

var service = new JsonMigrationService(registry);
String migrated = service.migrateToLatest("CREDENTIAL", rawJson);
```

---

## Spring Boot Auto-Configuration

When used with Spring Boot, the library auto-configures:

- `JsonMigrationRegistry` — collects all `JsonVersionMigration` `@Component` beans
- `JsonMigrationService` — ready to inject

No `@EnableXxx` annotation needed. Just add the dependency and create your migration `@Component` classes.

---

## Naming Convention

```
src/main/java/com/yourcompany/migration/
├── credential/
│   ├── CredentialV1ToV2.java
│   ├── CredentialV2ToV3.java
│   └── CredentialV3ToV4.java
├── branding/
│   └── BrandingV1ToV2.java
└── JsonTypes.java          # String constants for type names
```

---

## Publishing

### GitHub Packages

Releases are automatically published via the included GitHub Actions workflow (`.github/workflows/publish.yml`).

To publish manually:

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

- Java 17+
- JsonPath 2.9+
- Spring Boot 3.x (optional — works without Spring too)
- SLF4J 2.x (consumers provide the logging implementation)

---

## License

MIT License. See [LICENSE](LICENSE).
