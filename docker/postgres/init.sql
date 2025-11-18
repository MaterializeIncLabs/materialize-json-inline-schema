-- Initialize Postgres database for JDBC sink connector

-- Create tables for our test topics
-- These match the schemas we'll be testing

-- Table 1: Users - Processed user data with transformations
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY,
    name TEXT NOT NULL,  -- Uppercase normalized
    email TEXT NOT NULL,  -- Filtered: only users with emails
    created_at BIGINT NOT NULL
);

-- Table 2: Orders - Processed orders with categorization
CREATE TABLE IF NOT EXISTS orders (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    amount NUMERIC(10,2) NOT NULL,  -- Rounded to 2 decimals
    status TEXT NOT NULL,  -- Filtered: no cancelled orders
    order_time BIGINT NOT NULL,
    order_size TEXT NOT NULL  -- Computed: small/medium/large
);

-- Table 3: Events - Filtered and categorized events
CREATE TABLE IF NOT EXISTS events (
    event_id BIGINT PRIMARY KEY,
    event_type TEXT NOT NULL,  -- Filtered: important types only
    user_id TEXT,
    metadata TEXT,
    timestamp BIGINT NOT NULL,
    event_category TEXT NOT NULL  -- Computed: user_action/system_event
);

-- Create indexes for common queries
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_events_type ON events(event_type);
CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp);

-- Grant permissions
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgres;

-- Display table info
\dt
