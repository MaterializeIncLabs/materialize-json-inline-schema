# Materialize JSON Schema Attacher

A Kafka Streams application that attaches inline JSON schemas to messages from Materialize sinks, enabling seamless integration with the Confluent JDBC Sink Connector.

## Architecture

```
Materialize (exactly-once)
    → Redpanda (JSON without schema)
    → Kafka Streams App (attaches schema, exactly-once)
    → Redpanda (JSON with inline schema)
    → JDBC Sink Connector (upsert mode)
    → Postgres
```

## Features

- **Exactly-once semantics** throughout the pipeline
- **Simple configuration** - no code changes needed to add new topics
- **Upsert support** - handles updates correctly using primary keys
- **Multiple topic support** - transform multiple streams simultaneously
- **Fully tested** - comprehensive unit tests included
- **Docker-based** - entire pipeline runs in Docker Compose

## Prerequisites

- Java 17+
- Maven 3.6+
- Docker & Docker Compose
- Python 3.8+ (for test data generation)

## Quick Start

### 1. Build the application

```bash
mvn clean package
```

This creates a fat JAR: `target/json-schema-attacher-1.0.0-SNAPSHOT.jar`

### 2. Start the infrastructure

```bash
docker compose up -d
```

This starts:
- Redpanda (Kafka-compatible broker)
- Redpanda Console (Web UI on http://localhost:8080)
- Postgres (data destination)
- Materialize (streaming database)
- Kafka Connect (JDBC sink connector)
- Schema Attacher App (this application)

Wait for all services to be healthy (~30-60 seconds):

```bash
docker compose ps
```

### 3. Deploy JDBC sink connectors

```bash
./scripts/deploy_connectors.sh
```

This deploys three JDBC sink connectors with upsert mode enabled.

### 4. Generate test data

Install Python dependencies:

```bash
pip install -r scripts/requirements.txt
```

Run the data generator:

```bash
python3 scripts/generate_test_data.py
```

This generates:
- 10 user records
- 20 order records
- 30 event records
- Updates to test upsert behavior

### 5. Verify the pipeline

```bash
./scripts/verify_pipeline.sh
```

Or manually check Postgres:

```bash
docker exec -it postgres psql -U postgres -d sink_db
```

```sql
SELECT * FROM users;
SELECT * FROM orders;
SELECT * FROM events;
```

## Configuration

### Adding New Topics

Edit `config/application.properties`:

```properties
# Pattern: schema.topic.<input-topic>=<schema-json>
#          output.topic.<input-topic>=<output-topic>

schema.topic.my-new-topic={"type": "struct", "fields": [...], "name": "..."}
output.topic.my-new-topic=my-new-topic-with-schema
```

Restart the schema attacher:

```bash
docker compose restart schema-attacher
```

### Exactly-Once Semantics

The application is configured for exactly-once processing:

```properties
processing.guarantee=exactly_once_v2
```

Combined with:
- Materialize's exactly-once sinks
- JDBC connector's upsert mode

This provides end-to-end exactly-once delivery.

### Schema Format

Schemas must be in Kafka Connect JSON format:

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
      "optional": true,
      "field": "name"
    }
  ],
  "optional": false,
  "name": "schema.name"
}
```

Supported types:
- `int32`, `int64`
- `float32`, `float64`
- `string`
- `boolean`
- `bytes`
- Nested `struct`
- `array`

## Project Structure

```
.
├── src/
│   ├── main/java/com/materialize/schema/
│   │   ├── SchemaAttacherApp.java      # Main application
│   │   ├── SchemaWrapper.java          # Schema wrapping logic
│   │   ├── ConfigParser.java           # Configuration parser
│   │   └── TopicConfig.java            # Topic configuration model
│   └── test/java/com/materialize/schema/
│       ├── SchemaWrapperTest.java
│       ├── ConfigParserTest.java
│       └── TopicConfigTest.java
├── docker/
│   ├── materialize/init.sql            # Materialize setup
│   ├── postgres/init.sql               # Postgres schema
│   └── connect/                        # JDBC connector configs
│       ├── users-sink.json
│       ├── orders-sink.json
│       └── events-sink.json
├── scripts/
│   ├── generate_test_data.py           # Test data generator
│   ├── deploy_connectors.sh            # Deploy JDBC connectors
│   └── verify_pipeline.sh              # Verify data flow
├── config/
│   └── application.properties          # Kafka Streams config
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

## Testing

### Unit Tests

```bash
mvn test
```

### Integration Testing

The entire pipeline serves as an integration test:

1. Start the infrastructure
2. Generate test data
3. Verify data landed in Postgres correctly
4. Check for duplicates (should be none with exactly-once)
5. Generate updates and verify upserts work

### Manual Testing

Monitor topics in Redpanda Console:
- http://localhost:8080

Check Materialize views:

```bash
docker exec -it materialize psql -h localhost -p 6875 -U materialize
```

```sql
SHOW SOURCES;
SHOW SINKS;
SELECT * FROM users_processed LIMIT 5;
```

View connector status:

```bash
curl http://localhost:8083/connectors/users-postgres-sink/status | jq '.'
```

## Troubleshooting

### Schema Attacher not starting

Check logs:
```bash
docker compose logs schema-attacher
```

Common issues:
- Redpanda not ready yet (wait and restart)
- Invalid schema JSON in configuration

### Data not appearing in Postgres

1. Check if data is in Materialize:
   ```bash
   docker exec -it materialize psql -h localhost -p 6875 -U materialize
   SELECT * FROM users_processed;
   ```

2. Check if schema attacher is processing:
   ```bash
   docker-compose logs schema-attacher
   ```

3. Check JDBC connector status:
   ```bash
   curl http://localhost:8083/connectors/users-postgres-sink/status
   ```

4. Check connector logs:
   ```bash
   docker compose logs kafka-connect
   ```

### Topics not created

Topics are auto-created by Redpanda. If needed, manually create:

```bash
docker exec redpanda rpk topic create users-json-noschema
docker exec redpanda rpk topic create users-json-withschema
```

## Performance Tuning

For production deployments:

1. **Kafka Streams**:
   - Increase `num.stream.threads` for parallelism
   - Tune `cache.max.bytes.buffering` for batching

2. **Redpanda**:
   - Increase resources (memory, CPU)
   - Adjust `--smp` and `--memory` flags

3. **Postgres**:
   - Add appropriate indexes
   - Tune connection pool size in JDBC connector
   - Consider partitioning for large tables

## Development

### Running Locally (without Docker)

1. Start only infrastructure:
   ```bash
   docker compose up -d redpanda postgres materialize kafka-connect
   ```

2. Update `config/application.properties`:
   ```properties
   bootstrap.servers=localhost:19092
   ```

3. Run the application:
   ```bash
   java -jar target/json-schema-attacher-1.0.0-SNAPSHOT.jar config/application.properties
   ```

### Adding New Features

The codebase is structured for easy extension:

- **SchemaWrapper**: Modify to support different schema formats
- **ConfigParser**: Add support for external schema files
- **SchemaAttacherApp**: Add custom transformations or filtering

## License

[Your License Here]

## Contributing

[Your Contributing Guidelines Here]
