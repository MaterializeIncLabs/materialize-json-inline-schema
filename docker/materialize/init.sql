-- Initialize Materialize with sources and sinks
-- This demonstrates real streaming data transformations

-- Create connection to Redpanda
CREATE CONNECTION IF NOT EXISTS redpanda_connection TO KAFKA (
    BROKER 'redpanda:9092',
    SECURITY PROTOCOL 'PLAINTEXT'
);

-- ========================================
-- USERS PIPELINE with transformations
-- ========================================

-- Source: Raw user data from Kafka
CREATE SOURCE IF NOT EXISTS users_source
FROM KAFKA CONNECTION redpanda_connection (TOPIC 'users-source')
KEY FORMAT TEXT
VALUE FORMAT JSON
ENVELOPE UPSERT;

-- Materialized View: Process and enrich user data
-- - Filter out users without emails (data quality)
-- - Normalize names to uppercase for consistency
-- Note: Removed account_age_days as mz_now() cannot be used in materialized views
CREATE MATERIALIZED VIEW IF NOT EXISTS users_processed AS
SELECT
    key::bigint AS id,  -- Use the Kafka key as id to preserve uniqueness
    UPPER(data->>'name') AS name,  -- Normalize names to uppercase
    data->>'email' AS email,
    (data->>'created_at')::bigint AS created_at
FROM users_source
WHERE data->>'email' IS NOT NULL;  -- Filter: only users with emails

-- Sink: Write processed users to Kafka (no schema yet)
CREATE SINK IF NOT EXISTS users_json_sink
FROM users_processed
INTO KAFKA CONNECTION redpanda_connection (TOPIC 'users-json-noschema')
KEY (id) NOT ENFORCED
KEY FORMAT TEXT
VALUE FORMAT JSON
ENVELOPE UPSERT;

-- ========================================
-- ORDERS PIPELINE with transformations
-- ========================================

-- Source: Raw order data
CREATE SOURCE IF NOT EXISTS orders_source
FROM KAFKA CONNECTION redpanda_connection (TOPIC 'orders-source')
KEY FORMAT TEXT
VALUE FORMAT JSON
ENVELOPE UPSERT;

-- Materialized View: Process and categorize orders
-- - Filter to only active orders (not cancelled)
-- - Categorize order amounts as 'small', 'medium', or 'large'
-- - Round amounts to 2 decimal places
CREATE MATERIALIZED VIEW IF NOT EXISTS orders_processed AS
SELECT
    key::bigint AS order_id,  -- Use the Kafka key as order_id to preserve uniqueness
    (data->>'user_id')::bigint AS user_id,
    ROUND((data->>'amount')::numeric, 2)::double AS amount,  -- Round to 2 decimals and cast to double for JSON
    data->>'status' AS status,
    (data->>'order_time')::bigint AS order_time,
    -- Categorize orders by amount
    CASE
        WHEN (data->>'amount')::numeric < 100 THEN 'small'
        WHEN (data->>'amount')::numeric >= 100 AND (data->>'amount')::numeric < 300 THEN 'medium'
        ELSE 'large'
    END AS order_size
FROM orders_source
WHERE data->>'status' != 'cancelled';  -- Filter: exclude cancelled orders

-- Sink: Write processed orders to Kafka
CREATE SINK IF NOT EXISTS orders_json_sink
FROM orders_processed
INTO KAFKA CONNECTION redpanda_connection (TOPIC 'orders-json-noschema')
KEY (order_id) NOT ENFORCED
KEY FORMAT TEXT
VALUE FORMAT JSON
ENVELOPE UPSERT;

-- ========================================
-- EVENTS PIPELINE with transformations
-- ========================================

-- Source: Raw event data
CREATE SOURCE IF NOT EXISTS events_source
FROM KAFKA CONNECTION redpanda_connection (TOPIC 'events-source')
KEY FORMAT TEXT
VALUE FORMAT JSON
ENVELOPE UPSERT;

-- Materialized View: Process and enrich events
-- - Filter to important event types only
-- - Add categorization (user action vs system event)
-- - Parse metadata JSON when present
CREATE MATERIALIZED VIEW IF NOT EXISTS events_processed AS
SELECT
    key::bigint AS event_id,  -- Use the Kafka key as event_id to preserve uniqueness
    data->>'event_type' AS event_type,
    data->>'user_id' AS user_id,
    data->>'metadata' AS metadata,
    (data->>'timestamp')::bigint AS timestamp,
    -- Categorize events
    CASE
        WHEN data->>'event_type' IN ('login', 'logout', 'click', 'page_view', 'purchase') THEN 'user_action'
        WHEN data->>'event_type' = 'error' THEN 'system_event'
        ELSE 'other'
    END AS event_category
FROM events_source
WHERE data->>'event_type' IN ('login', 'logout', 'click', 'page_view', 'purchase', 'error');  -- Filter important events only

-- Sink: Write processed events to Kafka
CREATE SINK IF NOT EXISTS events_json_sink
FROM events_processed
INTO KAFKA CONNECTION redpanda_connection (TOPIC 'events-json-noschema')
KEY (event_id) NOT ENFORCED
KEY FORMAT TEXT
VALUE FORMAT JSON
ENVELOPE UPSERT;
