# Schema Generator Tool

This directory contains utilities for working with the Materialize JSON Schema Attacher.

## generate_schemas.py

Automatically generate Kafka Connect schema configurations from Materialize Kafka sinks.

### Features

- Queries Materialize's system catalog to discover all Kafka sinks
- Extracts column definitions from underlying materialized views/tables
- Automatically maps Materialize types to Kafka Connect schema types
- Handles temporal types (timestamps, dates, times) with proper logical types
- Supports multiple output formats (properties, JSON)
- Flexible filtering (all sinks or specific ones)

### Installation

```bash
cd tools
pip install -r requirements.txt
```

### Usage

#### Basic Usage

Generate schemas for all Kafka sinks:

```bash
python3 generate_schemas.py --host localhost --port 6875
```

#### Specific Sinks

Generate schemas for specific sinks only:

```bash
python3 generate_schemas.py \
  --host localhost \
  --port 6875 \
  --sink users_json_sink \
  --sink orders_json_sink
```

#### Output to File

Save the generated configuration to a file:

```bash
python3 generate_schemas.py \
  --host localhost \
  --port 6875 \
  --output config/generated_schemas.properties
```

#### JSON Format

Generate schemas in JSON format for review:

```bash
python3 generate_schemas.py \
  --host localhost \
  --port 6875 \
  --format json \
  --output schemas.json
```

#### Using Environment Variables

Set connection parameters via environment variables:

```bash
export MATERIALIZE_HOST=mz.example.com
export MATERIALIZE_PORT=6875
export MATERIALIZE_USER=myuser
export MATERIALIZE_PASSWORD=mypassword

python3 generate_schemas.py
```

### Command-Line Options

```
Connection Options:
  --host TEXT           Materialize host (default: localhost, env: MATERIALIZE_HOST)
  --port INTEGER        Materialize port (default: 6875, env: MATERIALIZE_PORT)
  --user TEXT           Materialize user (default: materialize, env: MATERIALIZE_USER)
  --password TEXT       Materialize password (env: MATERIALIZE_PASSWORD)
  --database TEXT       Materialize database (default: materialize, env: MATERIALIZE_DATABASE)

Sink Selection:
  --sink TEXT           Specific sink name (can be repeated for multiple sinks)
  --all-sinks           Process all Kafka sinks (default: True)

Output Options:
  --format CHOICE       Output format: properties, json (default: properties)
  --output, -o FILE     Output file (default: stdout)

Schema Options:
  --schema-namespace TEXT    Schema namespace prefix (default: materialize)
  --output-suffix TEXT       Suffix for output topics (default: -withschema)

Other Options:
  --verbose, -v         Enable verbose logging
```

### Type Mapping

The tool automatically maps Materialize types to Kafka Connect types:

#### Basic Types
- `bigint` → `int64`
- `integer`, `int` → `int32`
- `real` → `float32`
- `double precision`, `double` → `float64`
- `numeric`, `decimal` → `float64` (simplified)
- `text`, `varchar`, `char` → `string`
- `boolean` → `boolean`
- `bytea` → `bytes`

#### Temporal Types (with logical types)
- `timestamp`, `timestamp with time zone` → `int64` with `Timestamp` logical type
- `date` → `int32` with `Date` logical type
- `time` → `int32` with `Time` logical type`

**Note**: The Schema Attacher automatically converts Materialize timestamps from microseconds to milliseconds for proper JDBC connector handling.

### Example Output

**Properties Format:**
```properties
# Sink: users_json_sink (from users_processed)
schema.topic.users-json-noschema={"type": "struct","fields": [{"type": "int64","optional": false,"field": "id"},{"type": "string","optional": true,"field": "name"},{"type": "int64","optional": true,"field": "created_timestamp","name": "org.apache.kafka.connect.data.Timestamp","version": 1}],"optional": false,"name": "materialize.users_processed"}
output.topic.users-json-noschema=users-json-withschema
```

**JSON Format:**
```json
{
  "users_json_sink": {
    "input_topic": "users-json-noschema",
    "output_topic": "users-json-withschema",
    "source_object": "users_processed",
    "schema": {
      "type": "struct",
      "fields": [
        {
          "type": "int64",
          "optional": false,
          "field": "id"
        },
        {
          "type": "string",
          "optional": true,
          "field": "name"
        },
        {
          "type": "int64",
          "optional": true,
          "field": "created_timestamp",
          "name": "org.apache.kafka.connect.data.Timestamp",
          "version": 1
        }
      ],
      "optional": false,
      "name": "materialize.users_processed"
    }
  }
}
```

### Workflow Integration

#### 1. Generate schemas from Materialize

```bash
python3 tools/generate_schemas.py \
  --host mz.example.com \
  --port 6875 \
  --output config/generated.properties
```

#### 2. Review and merge with existing configuration

```bash
# Review the generated schemas
cat config/generated.properties

# Merge with existing configuration
cat config/generated.properties >> config/application.properties
```

#### 3. Restart the Schema Attacher

```bash
docker restart schema-attacher
```

### Troubleshooting

#### Connection Issues

If you get connection errors:

1. Verify Materialize is accessible:
   ```bash
   psql -h localhost -p 6875 -U materialize
   ```

2. Check for conflicting environment variables:
   ```bash
   env | grep -i "MATERIALIZE\|PG"
   ```

3. Unset conflicting variables if needed:
   ```bash
   unset MATERIALIZE_HOST MATERIALIZE_USER MATERIALIZE_PASSWORD
   ```

#### No Sinks Found

If the script reports no sinks:

1. Verify sinks exist in Materialize:
   ```sql
   SELECT name, type FROM mz_catalog.mz_sinks WHERE type = 'kafka';
   ```

2. Check you're connected to the correct database

#### Unknown Types

If you see warnings about unknown types:
- The script will default to `string` type
- Check the SCHEMA-GUIDE.md for supported types
- Consider updating the TypeMapper class in the script

### Best Practices

1. **Always review generated schemas** before deploying to production
2. **Test with a small sink first** to verify the output
3. **Version control your schemas** by committing generated configurations
4. **Document custom type mappings** if you modify the script
5. **Regenerate after schema changes** in Materialize

### See Also

- [SCHEMA-GUIDE.md](../SCHEMA-GUIDE.md) - Comprehensive schema configuration guide
- [README.md](../README.md) - Main project documentation
- [PRODUCTION.md](../PRODUCTION.md) - Production deployment guide
