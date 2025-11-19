.PHONY: help build test clean start stop restart logs deploy-connectors generate-data verify status docker-build docker-build-prod docker-push

# Docker image settings
IMAGE_NAME ?= ghcr.io/materializeinclabs/mz-json-inline-schema
VERSION ?= latest
PLATFORM ?= linux/amd64,linux/arm64

help:
	@echo "Materialize JSON Schema Attacher - Makefile Commands"
	@echo ""
	@echo "Build & Test:"
	@echo "  make build              - Build the application JAR"
	@echo "  make test               - Run unit tests"
	@echo "  make clean              - Clean build artifacts"
	@echo ""
	@echo "Docker Build:"
	@echo "  make docker-build       - Build development Docker image"
	@echo "  make docker-build-prod  - Build production Docker image"
	@echo "  make docker-push        - Build and push multi-arch production image"
	@echo ""
	@echo "Docker Operations:"
	@echo "  make start              - Start all services"
	@echo "  make stop               - Stop all services"
	@echo "  make restart            - Restart all services"
	@echo "  make logs               - Tail logs from all services"
	@echo "  make status             - Show status of all services"
	@echo ""
	@echo "Pipeline Operations:"
	@echo "  make deploy-connectors  - Deploy JDBC sink connectors"
	@echo "  make generate-data      - Generate test data"
	@echo "  make verify             - Verify pipeline is working"
	@echo ""
	@echo "Complete Setup:"
	@echo "  make setup              - Build + start + deploy + generate data"
	@echo "  make teardown           - Stop and remove all volumes"

build:
	@echo "Building application..."
	mvn clean package -DskipTests

test:
	@echo "Running unit tests..."
	mvn test

clean:
	@echo "Cleaning build artifacts..."
	mvn clean

start:
	@echo "Starting all services..."
	docker compose up -d
	@echo "Waiting for services to be healthy..."
	@sleep 10
	@echo "Services started. Check status with: make status"

stop:
	@echo "Stopping all services..."
	docker compose down

restart:
	@echo "Restarting all services..."
	docker compose restart

logs:
	@echo "Tailing logs (Ctrl+C to exit)..."
	docker compose logs -f

status:
	@echo "Service Status:"
	@docker compose ps

deploy-connectors:
	@echo "Deploying JDBC sink connectors..."
	@./scripts/deploy_connectors.sh

generate-data:
	@echo "Generating test data..."
	@python3 scripts/generate_test_data.py

verify:
	@echo "Verifying pipeline..."
	@./scripts/verify_pipeline.sh

setup: build start
	@echo "Waiting for services to be fully ready (60 seconds)..."
	@sleep 60
	@make deploy-connectors
	@echo "Waiting for connectors to initialize (10 seconds)..."
	@sleep 10
	@make generate-data
	@echo ""
	@echo "=========================================="
	@echo "✓ Setup complete!"
	@echo "=========================================="
	@echo ""
	@echo "Verify with: make verify"
	@echo "View UI: http://localhost:8080"

teardown:
	@echo "Stopping and removing all volumes..."
	docker compose down -v
	@echo "✓ Teardown complete"

# Shortcuts for checking individual components
check-postgres:
	@docker exec -it postgres psql -U postgres -d sink_db -c "\
		SELECT 'users' as table_name, COUNT(*) as count FROM users \
		UNION ALL SELECT 'orders', COUNT(*) FROM orders \
		UNION ALL SELECT 'events', COUNT(*) FROM events;"

check-materialize:
	@docker exec -it materialize psql -h localhost -p 6875 -U materialize -c "\
		SELECT name, type FROM mz_objects WHERE type IN ('source', 'sink', 'materialized-view') ORDER BY type, name;"

check-connectors:
	@curl -s http://localhost:8083/connectors | jq '.'

check-topics:
	@docker exec redpanda rpk topic list

# Docker image builds
docker-build:
	@echo "Building development Docker image..."
	docker build -f Dockerfile.dev -t $(IMAGE_NAME):dev .

docker-build-prod:
	@echo "Building production Docker image..."
	@echo "Image: $(IMAGE_NAME):$(VERSION)"
	docker build -t $(IMAGE_NAME):$(VERSION) .
	@echo "✓ Production image built successfully"
	@echo "Run: docker run -v /path/to/config:/app/config $(IMAGE_NAME):$(VERSION)"

docker-push:
	@echo "Building and pushing multi-arch production image..."
	@echo "Image: $(IMAGE_NAME):$(VERSION)"
	@echo "Platforms: $(PLATFORM)"
	docker buildx build \
		--platform $(PLATFORM) \
		--push \
		-t $(IMAGE_NAME):$(VERSION) \
		-t $(IMAGE_NAME):latest \
		.
	@echo "✓ Multi-arch image pushed successfully"
	@echo "Pull: docker pull $(IMAGE_NAME):$(VERSION)"
