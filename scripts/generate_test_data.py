#!/usr/bin/env python3
"""
Generate test data and publish to Kafka topics that Materialize will consume.
This simulates the upstream data sources.
"""

import json
import time
import random
from datetime import datetime
from confluent_kafka import Producer


def create_producer():
    """Create a Kafka producer connected to Redpanda."""
    conf = {
        'bootstrap.servers': 'localhost:19092',
        'client.id': 'test-data-generator'
    }
    return Producer(conf)


def generate_users(producer, count=10):
    """Generate user records."""
    print(f"Generating {count} user records...")

    names = ["Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Henry", "Ivy", "Jack"]

    for i in range(count):
        user_id = i + 1
        user = {
            "id": user_id,
            "name": names[i % len(names)],
            "email": f"user{user_id}@example.com" if random.random() > 0.2 else None,
            "created_at": int(time.time() * 1000) - random.randint(0, 86400000)
        }

        producer.produce(
            'users-source',
            key=str(user_id).encode('utf-8'),
            value=json.dumps(user).encode('utf-8')
        )
        print(f"  Sent user: {user}")
        time.sleep(0.1)

    producer.flush()
    print(f"✓ Generated {count} users")


def generate_orders(producer, count=20):
    """Generate order records."""
    print(f"Generating {count} order records...")

    statuses = ["pending", "processing", "completed", "cancelled"]

    for i in range(count):
        order_id = i + 1
        order = {
            "order_id": order_id,
            "user_id": random.randint(1, 10),
            "amount": round(random.uniform(10.0, 500.0), 2),
            "status": random.choice(statuses),
            "order_time": int(time.time() * 1000) - random.randint(0, 86400000)
        }

        producer.produce(
            'orders-source',
            key=str(order_id).encode('utf-8'),
            value=json.dumps(order).encode('utf-8')
        )
        print(f"  Sent order: {order}")
        time.sleep(0.1)

    producer.flush()
    print(f"✓ Generated {count} orders")


def generate_events(producer, count=30):
    """Generate event records."""
    print(f"Generating {count} event records...")

    event_types = ["login", "logout", "page_view", "click", "purchase", "error"]

    for i in range(count):
        event_id = i + 1
        event = {
            "event_id": event_id,
            "event_type": random.choice(event_types),
            "user_id": str(random.randint(1, 10)) if random.random() > 0.3 else None,
            "metadata": json.dumps({"page": f"/page{random.randint(1, 5)}", "duration": random.randint(1, 300)}) if random.random() > 0.4 else None,
            "timestamp": int(time.time() * 1000) - random.randint(0, 86400000)
        }

        producer.produce(
            'events-source',
            key=str(event_id).encode('utf-8'),
            value=json.dumps(event).encode('utf-8')
        )
        print(f"  Sent event: {event}")
        time.sleep(0.1)

    producer.flush()
    print(f"✓ Generated {count} events")


def generate_updates(producer, delay=2):
    """Generate updates to existing records to test upsert behavior."""
    print(f"\nWaiting {delay} seconds before generating updates...")
    time.sleep(delay)

    print("Generating updates to test upsert behavior...")

    # Update some users
    updates = [
        {"id": 1, "name": "Alice Updated", "email": "alice.new@example.com", "created_at": int(time.time() * 1000)},
        {"id": 5, "name": "Eve Modified", "email": "eve.modified@example.com", "created_at": int(time.time() * 1000)},
    ]

    for user in updates:
        producer.produce(
            'users-source',
            key=str(user["id"]).encode('utf-8'),
            value=json.dumps(user).encode('utf-8')
        )
        print(f"  Updated user: {user}")
        time.sleep(0.1)

    # Update some orders
    order_updates = [
        {"order_id": 1, "user_id": 1, "amount": 999.99, "status": "completed", "order_time": int(time.time() * 1000)},
        {"order_id": 10, "user_id": 5, "amount": 150.00, "status": "cancelled", "order_time": int(time.time() * 1000)},
    ]

    for order in order_updates:
        producer.produce(
            'orders-source',
            key=str(order["order_id"]).encode('utf-8'),
            value=json.dumps(order).encode('utf-8')
        )
        print(f"  Updated order: {order}")
        time.sleep(0.1)

    producer.flush()
    print("✓ Generated updates")


def main():
    print("=" * 60)
    print("Test Data Generator for Materialize JSON Schema Attacher")
    print("=" * 60)
    print()

    try:
        producer = create_producer()
        print("✓ Connected to Redpanda\n")

        # Generate initial data
        generate_users(producer, count=10)
        generate_orders(producer, count=20)
        generate_events(producer, count=30)

        # Generate updates to test upserts
        generate_updates(producer, delay=2)

        print("\n" + "=" * 60)
        print("✓ Test data generation complete!")
        print("=" * 60)
        print("\nYou can now:")
        print("  1. Check Redpanda Console: http://localhost:8080")
        print("  2. Query Postgres to verify data landed correctly")
        print("  3. Run this script again to generate more test data")

    except Exception as e:
        print(f"\n✗ Error: {e}")
        print("\nMake sure Redpanda is running:")
        print("  docker-compose up -d redpanda")
        return 1

    return 0


if __name__ == "__main__":
    exit(main())
