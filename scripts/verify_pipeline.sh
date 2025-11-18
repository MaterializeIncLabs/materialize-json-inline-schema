#!/bin/bash
# Verify that data is flowing through the entire pipeline

set -e

echo "=========================================="
echo "Pipeline Verification Script"
echo "=========================================="
echo

# Check Postgres
echo "1. Checking Postgres tables..."
docker exec -i postgres psql -U postgres -d sink_db << EOF
SELECT 'users' as table_name, COUNT(*) as row_count FROM users
UNION ALL
SELECT 'orders', COUNT(*) FROM orders
UNION ALL
SELECT 'events', COUNT(*) FROM events;
EOF
echo "✓ Postgres check complete"
echo

# Check Materialize
echo "2. Checking Materialize views..."
docker exec -i materialize psql -h localhost -p 6875 -U materialize -d materialize << EOF
SELECT name, type FROM mz_objects WHERE type IN ('source', 'sink', 'materialized-view') ORDER BY type, name;
EOF
echo "✓ Materialize check complete"
echo

# Check Kafka Connect connectors
echo "3. Checking Kafka Connect connectors..."
curl -s http://localhost:8083/connectors | jq -r '.[]' | while read connector; do
    echo "  Connector: $connector"
    curl -s "http://localhost:8083/connectors/$connector/status" | jq -r '.connector.state'
done
echo "✓ Kafka Connect check complete"
echo

# Check topics in Redpanda
echo "4. Checking Redpanda topics..."
docker exec redpanda rpk topic list
echo "✓ Redpanda check complete"
echo

echo "=========================================="
echo "✓ Pipeline verification complete!"
echo "=========================================="
echo
echo "To view detailed data in Postgres:"
echo "  docker exec -it postgres psql -U postgres -d sink_db"
echo "  > SELECT * FROM users LIMIT 5;"
echo
echo "To view Redpanda Console:"
echo "  http://localhost:8080"
