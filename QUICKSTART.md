# Quick Start Guide

Get the entire pipeline running in 5 minutes.

## Prerequisites

```bash
# Verify you have the required tools
java -version    # Should be 17+
mvn -version     # Should be 3.6+
docker --version
python3 --version
```

## Steps

### 1. Build

```bash
mvn clean package
```

Expected output: `BUILD SUCCESS` and JAR created in `target/`

### 2. Start Services

```bash
docker compose up -d
```

Wait for services to be healthy (~30-60 seconds):

```bash
docker compose ps
# All services should show "Up" or "Up (healthy)"
```

### 3. Deploy Connectors

```bash
./scripts/deploy_connectors.sh
```

Expected output: Three connectors deployed successfully

### 4. Generate Test Data

```bash
pip install -r scripts/requirements.txt
python3 scripts/generate_test_data.py
```

Expected output:
- ✓ Generated 10 users
- ✓ Generated 20 orders
- ✓ Generated 30 events
- ✓ Generated updates

### 5. Verify Results

```bash
./scripts/verify_pipeline.sh
```

Or check Postgres directly:

```bash
docker exec -it postgres psql -U postgres -d sink_db -c "SELECT COUNT(*) FROM users;"
docker exec -it postgres psql -U postgres -d sink_db -c "SELECT COUNT(*) FROM orders;"
docker exec -it postgres psql -U postgres -d sink_db -c "SELECT COUNT(*) FROM events;"
```

Expected: 10 users, 20 orders, 30 events

### 6. View in UI

Open Redpanda Console: http://localhost:8080

You should see topics:
- `users-json-noschema` (input to schema attacher)
- `users-json-withschema` (output from schema attacher)
- `orders-json-noschema`
- `orders-json-withschema`
- `events-json-noschema`
- `events-json-withschema`

## Test Upserts

Run the data generator again to test upsert behavior:

```bash
python3 scripts/generate_test_data.py
```

Check that counts remain the same (upserts, not inserts):

```bash
docker exec -it postgres psql -U postgres -d sink_db -c "SELECT COUNT(*) FROM users;"
```

Should still be 10 (not 20).

View updated records:

```bash
docker exec -it postgres psql -U postgres -d sink_db -c "SELECT * FROM users WHERE name LIKE '%Updated%' OR name LIKE '%Modified%';"
```

## Cleanup

```bash
# Stop all services
docker compose down

# Remove volumes (to start fresh)
docker compose down -v
```

## Next Steps

- Read [README.md](README.md) for full documentation
- Modify `config/application.properties` to add your own topics
- Customize schemas in the configuration file
- Monitor the pipeline in Redpanda Console
