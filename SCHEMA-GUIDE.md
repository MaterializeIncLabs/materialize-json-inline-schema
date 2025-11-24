# Schema Configuration Guide

This guide explains how to create schema configurations for the Materialize JSON Schema Attacher.

## Overview

The Schema Attacher requires two pieces of information for each topic:

1. **Input topic name** - The topic from Materialize (without schema)
2. **JSON schema definition** - Kafka Connect format schema describing the message structure
3. **Output topic name** - Where to write schema-wrapped messages

## Configuration Format

In `application.properties`:

```properties
schema.topic.<input-topic-name>=<json-schema>
output.topic.<input-topic-name>=<output-topic-name>
```

## Schema Structure

Kafka Connect schemas follow this structure:

```json
{
  "type": "struct",
  "fields": [
    {
      "type": "<field-type>",
      "optional": <true|false>,
      "field": "<field-name>"
    }
  ],
  "optional": false,
  "name": "<schema-name>"
}
```

### Required Properties

- **type**: Always `"struct"` for the root schema
- **fields**: Array of field definitions
- **optional**: Whether the entire struct can be null (usually `false`)
- **name**: Fully qualified schema name (e.g., `"materialize.users"`)

### Field Properties

Each field requires:

- **type**: Data type (see supported types below)
- **optional**: Whether field can be null
- **field**: Field name (must match JSON key from Materialize)

## Supported Data Types

### Basic Types

| Kafka Connect Type | Materialize/SQL Type | Example Value |
|-------------------|---------------------|---------------|
| `int32` | `INTEGER`, `INT` | `42` |
| `int64` | `BIGINT` | `9223372036854775807` |
| `float32` | `REAL` | `3.14` |
| `float64` | `DOUBLE`, `NUMERIC` | `3.141592653589793` |
| `string` | `TEXT`, `VARCHAR` | `"hello"` |
| `boolean` | `BOOLEAN` | `true` |
| `bytes` | `BYTEA` | Base64 encoded |

### Logical Types

Logical types add semantic meaning to primitive types:

#### Timestamp

```json
{
  "type": "int64",
  "optional": false,
  "field": "created_at",
  "name": "org.apache.kafka.connect.data.Timestamp",
  "version": 1
}
```

Represents milliseconds since Unix epoch.

**IMPORTANT**: Materialize's native timestamp format is **microseconds** since epoch. The Schema Attacher automatically converts:
- **Materialize microseconds** (16-17 digit numbers) → milliseconds (divides by 1000)
- Example: `1762525914902712` microseconds → `1762525914902` milliseconds

This conversion is critical for proper timestamp handling in JDBC sinks. Without it, timestamps appear as dates thousands of years in the future.

#### Date

```json
{
  "type": "int32",
  "optional": false,
  "field": "birth_date",
  "name": "org.apache.kafka.connect.data.Date",
  "version": 1
}
```

Represents days since Unix epoch (January 1, 1970).

#### Time

```json
{
  "type": "int32",
  "optional": false,
  "field": "event_time",
  "name": "org.apache.kafka.connect.data.Time",
  "version": 1
}
```

Represents milliseconds since midnight.

#### Decimal

```json
{
  "type": "bytes",
  "optional": false,
  "field": "price",
  "name": "org.apache.kafka.connect.data.Decimal",
  "version": 1,
  "parameters": {
    "scale": "2"
  }
}
```

## Creating Schemas from Materialize Views

### Step 1: Identify Your Materialize View

```sql
-- Example view in Materialize
CREATE MATERIALIZED VIEW users_processed AS
SELECT
    key::bigint AS id,
    data->>'name' AS name,
    data->>'email' AS email,
    (data->>'age')::int AS age,
    (data->>'created_at')::bigint AS created_at,
    to_timestamp((data->>'created_at')::bigint / 1000) AS created_timestamp
FROM users_source;
```

### Step 2: Map Column Types

| Column | Materialize Type | Kafka Connect Type | Optional |
|--------|-----------------|-------------------|----------|
| `id` | `bigint` | `int64` | `false` (PK) |
| `name` | `text` | `string` | `false` |
| `email` | `text` | `string` | `true` |
| `age` | `integer` | `int32` | `true` |
| `created_at` | `bigint` | `int64` | `false` |
| `created_timestamp` | `timestamp` | `int64` (Timestamp) | `false` |

### Step 3: Construct the Schema

```json
{
  "type": "struct",
  "fields": [
    {
      "type": "int64",
      "optional": false,
      "field": "id"
    },
    {
      "type": "string",
      "optional": false,
      "field": "name"
    },
    {
      "type": "string",
      "optional": true,
      "field": "email"
    },
    {
      "type": "int32",
      "optional": true,
      "field": "age"
    },
    {
      "type": "int64",
      "optional": false,
      "field": "created_at"
    },
    {
      "type": "int64",
      "optional": false,
      "field": "created_timestamp",
      "name": "org.apache.kafka.connect.data.Timestamp",
      "version": 1
    }
  ],
  "optional": false,
  "name": "materialize.users"
}
```

### Step 4: Format for application.properties

Convert to single line and escape as needed:

```properties
schema.topic.users-json-noschema={"type": "struct", "fields": [{"type": "int64", "optional": false, "field": "id"}, {"type": "string", "optional": false, "field": "name"}, {"type": "string", "optional": true, "field": "email"}, {"type": "int32", "optional": true, "field": "age"}, {"type": "int64", "optional": false, "field": "created_at"}, {"type": "int64", "optional": false, "field": "created_timestamp", "name": "org.apache.kafka.connect.data.Timestamp", "version": 1}], "optional": false, "name": "materialize.users"}
output.topic.users-json-noschema=users-json-withschema
```

## Common Patterns

### Pattern 1: Simple Table with Primary Key

**Materialize View:**
```sql
CREATE MATERIALIZED VIEW products AS
SELECT
    id::bigint,
    name::text,
    price::numeric(10,2)
FROM products_source;
```

**Schema:**
```json
{
  "type": "struct",
  "fields": [
    {"type": "int64", "optional": false, "field": "id"},
    {"type": "string", "optional": false, "field": "name"},
    {"type": "double", "optional": false, "field": "price"}
  ],
  "optional": false,
  "name": "materialize.products"
}
```

### Pattern 2: Table with Timestamps

**Materialize View:**
```sql
CREATE MATERIALIZED VIEW orders AS
SELECT
    order_id::bigint,
    customer_id::bigint,
    amount::numeric,
    to_timestamp(order_time::bigint / 1000) AS order_timestamp
FROM orders_source;
```

**Schema:**
```json
{
  "type": "struct",
  "fields": [
    {"type": "int64", "optional": false, "field": "order_id"},
    {"type": "int64", "optional": false, "field": "customer_id"},
    {"type": "double", "optional": false, "field": "amount"},
    {
      "type": "int64",
      "optional": false,
      "field": "order_timestamp",
      "name": "org.apache.kafka.connect.data.Timestamp",
      "version": 1
    }
  ],
  "optional": false,
  "name": "materialize.orders"
}
```

### Pattern 3: Table with Nullable Fields

**Materialize View:**
```sql
CREATE MATERIALIZED VIEW users AS
SELECT
    id::bigint,
    email::text,
    phone::text,  -- nullable
    verified_at::bigint  -- nullable
FROM users_source
WHERE email IS NOT NULL;
```

**Schema:**
```json
{
  "type": "struct",
  "fields": [
    {"type": "int64", "optional": false, "field": "id"},
    {"type": "string", "optional": false, "field": "email"},
    {"type": "string", "optional": true, "field": "phone"},
    {"type": "int64", "optional": true, "field": "verified_at"}
  ],
  "optional": false,
  "name": "materialize.users"
}
```

## Type Conversion Behavior

The Schema Attacher automatically converts values from Materialize's string output to the appropriate types:

### Numeric Conversions

```
Materialize Output: {"id": "123", "amount": "99.99"}
Schema Type: int64 for id, double for amount
Result: {"id": 123, "amount": 99.99}
```

### Timestamp Conversions

```
Materialize Output: {"created_at": "1699564800.123"}
Schema Type: int64 with Timestamp logical type
Result: {"created_at": 1699564800123}  (milliseconds)
```

## Testing Your Schema

### 1. Check Materialize Output

Verify the JSON structure Materialize produces:

```bash
docker exec redpanda rpk topic consume your-topic-noschema \
  --num 1 --format '%v\n'
```

Example output:
```json
{"id":"123","name":"Alice","email":"alice@example.com","age":"30"}
```

### 2. Verify Schema-Wrapped Output

After configuring the schema, check the output:

```bash
docker exec redpanda rpk topic consume your-topic-withschema \
  --num 1 --format '%v\n'
```

Expected output:
```json
{
  "schema": {
    "type": "struct",
    "fields": [...],
    "name": "materialize.users"
  },
  "payload": {
    "id": 123,
    "name": "Alice",
    "email": "alice@example.com",
    "age": 30
  }
}
```

### 3. Verify in JDBC Connector

Check that data lands correctly in your database:

```sql
SELECT * FROM your_table LIMIT 5;
```

## Common Issues and Solutions

### Issue 1: Type Mismatch Errors

**Error:** `Invalid type for field_name, expected X but got Y`

**Solution:** Ensure schema types match Materialize output:
- If Materialize outputs strings, Schema Attacher converts them based on schema
- Check your Materialize view casts types correctly

### Issue 2: Missing Fields

**Error:** `Field not found in payload`

**Solution:**
- Verify field names in schema match Materialize output exactly (case-sensitive)
- Check that all fields in schema exist in Materialize view

### Issue 3: Timestamp Conversion Failures

**Error:** `Could not convert timestamp field`

**Solution:**
- Ensure Materialize uses `to_timestamp()` for timestamp fields
- Verify timestamp values are numeric (not ISO strings)
- Use correct logical type: `"name": "org.apache.kafka.connect.data.Timestamp"`

### Issue 4: JDBC Connector Rejects Messages

**Error:** `Invalid type for Timestamp, underlying representation should be integral but was STRING`

**Solution:**
- Confirm timestamp field uses logical type in schema
- Restart Schema Attacher after schema changes
- Clear old messages or use a new topic for testing

### Issue 5: Timestamps Show Dates in Distant Future

**Error:** Postgres shows timestamps like `57822-03-23 11:55:02.712` instead of `2025-11-07 14:31:54.902`

**Root Cause:** Materialize sends timestamps as **microseconds** (e.g., `1762525914902712`), but JDBC connector expects **milliseconds**. The large number is interpreted as milliseconds, resulting in a date thousands of years in the future.

**Solution:**
- The Schema Attacher automatically handles this conversion
- Use the `Timestamp` logical type in your schema:
  ```json
  {
    "type": "int64",
    "field": "timestamp_field",
    "name": "org.apache.kafka.connect.data.Timestamp",
    "version": 1
  }
  ```
- The Schema Attacher will divide microseconds by 1000 to convert to milliseconds
- Restart Schema Attacher if you just added the logical type

## Schema Naming Conventions

Choose meaningful, hierarchical names:

**Good:**
- `materialize.production.users`
- `materialize.analytics.daily_stats`
- `yourcompany.domain.entity`

**Avoid:**
- Generic names: `record`, `data`, `message`
- Version numbers in name: `users_v2` (use parameters instead)
- Special characters: `users-schema`, `users.schema!`

## Advanced Topics

### Nested Structures

For complex JSON fields, use nested structs:

```json
{
  "type": "struct",
  "fields": [
    {"type": "int64", "optional": false, "field": "id"},
    {
      "type": "struct",
      "optional": true,
      "field": "address",
      "fields": [
        {"type": "string", "optional": true, "field": "street"},
        {"type": "string", "optional": true, "field": "city"},
        {"type": "string", "optional": true, "field": "country"}
      ]
    }
  ],
  "optional": false,
  "name": "materialize.users_with_address"
}
```

### Arrays

For array fields:

```json
{
  "type": "array",
  "items": {
    "type": "string",
    "optional": false
  },
  "optional": false,
  "field": "tags"
}
```

## Tools and Utilities

### JSON Formatting

Use `jq` to format and validate your schema:

```bash
echo '{"type":"struct","fields":[...]}' | jq .
```

### Schema Generation Script

Create a helper script to generate schemas from Materialize views:

```bash
#!/bin/bash
# generate_schema.sh
cat <<EOF
{
  "type": "struct",
  "fields": [
    $(echo "$FIELDS" | jq -c)
  ],
  "optional": false,
  "name": "$SCHEMA_NAME"
}
EOF
```

## Best Practices

1. **Always specify optional correctly**
   - Use `false` for NOT NULL columns and primary keys
   - Use `true` for nullable columns

2. **Use logical types for timestamps**
   - This ensures proper JDBC connector handling
   - Enables correct time-based queries

3. **Match Materialize output exactly**
   - Field names are case-sensitive
   - All fields in Materialize view must be in schema

4. **Test incrementally**
   - Start with a simple schema
   - Add fields one at a time
   - Verify each change works before continuing

5. **Document your schemas**
   - Add comments in application.properties
   - Keep a separate schema registry or documentation

6. **Version your configuration**
   - Commit application.properties to git
   - Tag releases when changing schemas
   - Document breaking changes

## References

- [Kafka Connect Schema Documentation](https://kafka.apache.org/documentation/#connect_schemas)
- [Materialize Data Types](https://materialize.com/docs/sql/types/)
- [JDBC Connector Configuration](https://docs.confluent.io/kafka-connect-jdbc/current/)

## Support

For issues with schema configuration:
- Check logs: `docker logs schema-attacher`
- Validate JSON: Use online JSON validators
- Test with minimal example first
- Consult GitHub issues: https://github.com/MaterializeIncLabs/materialize-json-inline-schema/issues
