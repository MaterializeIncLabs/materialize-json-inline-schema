#!/bin/bash
# Deploy JDBC sink connectors to Kafka Connect

set -e

CONNECT_URL="http://localhost:8083"

echo "Waiting for Kafka Connect to be ready..."
until curl -s -f -o /dev/null "$CONNECT_URL"; do
    echo "  Kafka Connect is not ready yet. Retrying in 5 seconds..."
    sleep 5
done
echo "✓ Kafka Connect is ready"
echo

# Deploy users sink connector
echo "Deploying users-postgres-sink connector..."
curl -s -X POST "$CONNECT_URL/connectors" \
  -H "Content-Type: application/json" \
  -d @docker/connect/users-sink.json | jq '.'
echo "✓ users-postgres-sink deployed"
echo

# Deploy orders sink connector
echo "Deploying orders-postgres-sink connector..."
curl -s -X POST "$CONNECT_URL/connectors" \
  -H "Content-Type: application/json" \
  -d @docker/connect/orders-sink.json | jq '.'
echo "✓ orders-postgres-sink deployed"
echo

# Deploy events sink connector
echo "Deploying events-postgres-sink connector..."
curl -s -X POST "$CONNECT_URL/connectors" \
  -H "Content-Type: application/json" \
  -d @docker/connect/events-sink.json | jq '.'
echo "✓ events-postgres-sink deployed"
echo

# Show all connectors
echo "All deployed connectors:"
curl -s "$CONNECT_URL/connectors" | jq '.'
echo

echo "✓ All connectors deployed successfully!"
echo
echo "Check connector status:"
echo "  curl http://localhost:8083/connectors/users-postgres-sink/status | jq '.'"
