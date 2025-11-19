# Production Deployment Guide

This guide covers deploying the Materialize JSON Schema Attacher in production environments.

## Architecture Overview

```
Materialize → Kafka (no schema) → Schema Attacher → Kafka (with schema) → JDBC Sink → Target DB
```

The Schema Attacher acts as a streaming transformation layer that:
- Consumes messages from Materialize sink output topics
- Attaches inline JSON schemas to each message
- Converts data types (strings to numbers, timestamps)
- Preserves tombstones for proper delete handling
- Produces schema-wrapped messages for Kafka Connect

## Prerequisites

- **Kafka/Redpanda Cluster**: 3+ brokers recommended for production
- **Java Runtime**: JRE 17 or higher
- **Resource Requirements**:
  - CPU: 2+ cores recommended
  - Memory: 2-4 GB heap (configurable via JVM options)
  - Disk: Minimal (state stored in Kafka)
- **Network**: Access to Kafka brokers

## Deployment Options

### Option 1: Docker Container (Recommended)

Use the production-ready Docker image from GitHub Container Registry:

```bash
docker pull ghcr.io/materializeinclabs/materialize-json-inline-schema:latest
```

**Step 1: Prepare Configuration Directory**

Create a configuration directory with your `application.properties`:

```bash
mkdir -p /opt/schema-attacher/config
cat > /opt/schema-attacher/config/application.properties <<EOF
bootstrap.servers=your-kafka-broker:9092
application.id=schema-attacher-prod
processing.guarantee=exactly_once_v2

# Your topic mappings
schema.topic.your-input={"type": "struct", "fields": [...], "name": "your.schema"}
output.topic.your-input=your-output
EOF
```

**Step 2: Run the Container**

```bash
docker run -d \
  --name schema-attacher \
  --restart unless-stopped \
  --network your-kafka-network \
  -v /opt/schema-attacher/config:/app/config \
  -e JAVA_OPTS="-Xmx2g -Xms2g" \
  ghcr.io/materializeinclabs/materialize-json-inline-schema:latest
```

**Step 3: Verify Startup**

```bash
# Check logs
docker logs -f schema-attacher

# Verify RUNNING state
docker logs schema-attacher | grep "State transition.*RUNNING"
```

**Updating Configuration:**

To update the configuration without rebuilding:

1. Edit `/opt/schema-attacher/config/application.properties`
2. Restart the container: `docker restart schema-attacher`
3. Monitor logs: `docker logs -f schema-attacher`

### Option 2: Kubernetes Deployment

Deploy as a Kubernetes Deployment for high availability:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: schema-attacher
spec:
  replicas: 1  # Note: Kafka Streams handles partitioning internally
  selector:
    matchLabels:
      app: schema-attacher
  template:
    metadata:
      labels:
        app: schema-attacher
    spec:
      containers:
      - name: schema-attacher
        image: ghcr.io/materializeinclabs/materialize-json-inline-schema:latest
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        volumeMounts:
        - name: config
          mountPath: /app/config
        env:
        - name: JAVA_OPTS
          value: "-Xmx2g -Xms2g -XX:+UseG1GC"
      volumes:
      - name: config
        configMap:
          name: schema-attacher-config
```

### Option 3: Standalone Java Application

Run directly with Java:

```bash
java -jar -Xmx2g -Xms2g \
  target/json-schema-attacher-1.0.0-SNAPSHOT.jar \
  config/application.properties
```

## Configuration

### Kafka Connection Settings

```properties
# Bootstrap servers - use all broker addresses for production
bootstrap.servers=kafka-1:9092,kafka-2:9092,kafka-3:9092

# Application ID - must be unique per deployment
application.id=schema-attacher-prod

# Consumer and producer settings
default.key.serde=org.apache.kafka.common.serialization.Serdes$StringSerde
default.value.serde=org.apache.kafka.common.serialization.Serdes$StringSerde
```

### Exactly-Once Processing

Enable exactly-once semantics for production:

```properties
processing.guarantee=exactly_once_v2
```

This ensures:
- No duplicate messages
- No message loss
- Transactional state updates

### Performance Tuning

**For high throughput**:
```properties
# Increase batch size and linger time
producer.batch.size=32768
producer.linger.ms=100

# Increase buffer memory
producer.buffer.memory=67108864

# Compression
compression.type=snappy
```

**For low latency**:
```properties
# Reduce linger time
producer.linger.ms=0

# Smaller batch size
producer.batch.size=16384
```

### Topic Configuration

Define your pipeline topics:

```properties
# Topic mapping: input → output
schema.topic.materialize-users={"type": "struct", ...}
output.topic.materialize-users=users-with-schema
```

**Creating Schemas:** See [SCHEMA-GUIDE.md](SCHEMA-GUIDE.md) for comprehensive instructions on:
- Mapping Materialize data types to Kafka Connect schemas
- Handling timestamps and logical types
- Type conversion behavior
- Testing and validation
- Common patterns and troubleshooting

## Monitoring and Observability

### JMX Metrics

The application exposes JMX metrics for monitoring:

```bash
# Enable JMX
JAVA_OPTS="-Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false"
```

**Key Metrics to Monitor**:
- `kafka.streams:type=stream-metrics,client-id=*` - Throughput and latency
- `kafka.streams:type=stream-thread-metrics,thread-id=*` - Thread health
- `kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*` - Consumer lag
- `kafka.producer:type=producer-metrics,client-id=*` - Producer health

### Health Checks

**Application State**:
The Kafka Streams application goes through these states:
- `CREATED` → `REBALANCING` → `RUNNING` (healthy)
- `ERROR` (unhealthy - requires restart)

Monitor logs for state transitions:
```
INFO org.apache.kafka.streams.KafkaStreams - stream-client [schema-attacher] State transition from REBALANCING to RUNNING
```

**Consumer Lag**:
Monitor consumer group lag to detect processing issues:

```bash
# Check consumer group lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group schema-attacher-prod \
  --describe
```

Alert if lag exceeds threshold (e.g., 10,000 messages).

### Logging

**Production Logging Configuration**:

Use SLF4J with Logback for structured logging:

```xml
<!-- logback.xml -->
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeContext>false</includeContext>
    </encoder>
  </appender>
  
  <logger name="com.materialize.schema" level="INFO"/>
  <logger name="org.apache.kafka" level="WARN"/>
  
  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

**Log Aggregation**:
- Forward logs to centralized system (ELK, Splunk, CloudWatch)
- Set up alerts for ERROR and WARN level messages
- Monitor for rebalance events

## Security

### Network Security

- **Kafka TLS**: Use encrypted connections to Kafka
  ```properties
  security.protocol=SSL
  ssl.truststore.location=/path/to/truststore.jks
  ssl.truststore.password=<password>
  ```

- **mTLS Authentication**:
  ```properties
  security.protocol=SSL
  ssl.keystore.location=/path/to/keystore.jks
  ssl.keystore.password=<password>
  ssl.key.password=<password>
  ```

- **SASL Authentication**:
  ```properties
  security.protocol=SASL_SSL
  sasl.mechanism=PLAIN
  sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
    username="<username>" \
    password="<password>";
  ```

### Container Security

- Run as non-root user (production image uses UID 1000)
- Use read-only root filesystem
- Drop unnecessary capabilities
- Scan images for vulnerabilities

### Secrets Management

**DO NOT** store credentials in configuration files. Use:

- **Kubernetes Secrets**:
  ```yaml
  env:
  - name: KAFKA_PASSWORD
    valueFrom:
      secretKeyRef:
        name: kafka-credentials
        key: password
  ```

- **Docker Secrets**:
  ```bash
  docker secret create kafka_password password.txt
  ```

- **Environment Variables**:
  ```bash
  export KAFKA_PASSWORD=<secret>
  ```

Reference in properties:
```properties
sasl.jaas.config=... password="${env:KAFKA_PASSWORD}";
```

## High Availability

### Stateless Design

The Schema Attacher is stateless - all state is stored in Kafka. Benefits:
- Easy horizontal scaling
- Fast recovery from failures
- No data loss on pod/container restart

### Rebalancing

When multiple instances run:
- Kafka Streams automatically distributes partitions
- Exactly-once guarantees prevent duplicate processing
- Rebalancing occurs automatically on instance addition/removal

**Note**: Multiple instances must have the **same `application.id`**

### Disaster Recovery

**State Restoration**:
- State automatically restored from changelog topics
- Recovery time depends on state size and network speed

**Backup Strategy**:
- Kafka topic retention: 7+ days recommended
- Regular backups of configuration files
- Document schema definitions

## Performance Tuning

### Scaling Guidelines

**Throughput Targets**:
- Single instance: 10,000-50,000 msg/sec (depends on message size)
- Multiple instances: Linear scaling up to partition count

**Bottleneck Identification**:
1. **CPU-bound**: Add more instances
2. **Network-bound**: Increase `producer.buffer.memory`
3. **Consumer lag growing**: Check downstream systems

### JVM Tuning

**Garbage Collection**:
```bash
JAVA_OPTS="-Xmx4g -Xms4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+PrintGCDetails \
  -XX:+PrintGCDateStamps"
```

**Memory Settings**:
- Heap size: 2-4 GB for typical workloads
- Leave 1-2 GB for OS and off-heap memory

### Kafka Streams Tuning

```properties
# Buffer cache for state stores (if using)
cache.max.bytes.buffering=10485760

# Number of threads per instance
num.stream.threads=1  # Increase for more CPU cores

# Commit interval (tradeoff: throughput vs. recovery time)
commit.interval.ms=30000
```

## Troubleshooting

### Common Issues

**1. Consumer Group in REBALANCING State**

Symptoms: Constant rebalancing, no message processing

Solutions:
- Check network connectivity to Kafka
- Increase `session.timeout.ms` and `heartbeat.interval.ms`
- Verify all instances have same `application.id`

**2. High Memory Usage**

Symptoms: OutOfMemoryError, frequent GC pauses

Solutions:
- Reduce `cache.max.bytes.buffering`
- Increase heap size
- Check for message size issues

**3. Consumer Lag Growing**

Symptoms: Lag increases over time

Solutions:
- Add more instances
- Tune producer settings for throughput
- Check downstream system capacity (JDBC Sink)

**4. Duplicate Messages**

Symptoms: Same records processed multiple times

Solutions:
- Verify `processing.guarantee=exactly_once_v2`
- Check for multiple deployments with different `application.id`
- Review Kafka transactional state

### Debug Mode

Enable debug logging:

```properties
# Add to application.properties or via environment
log4j.logger.com.materialize.schema=DEBUG
log4j.logger.org.apache.kafka.streams=DEBUG
```

Or via JVM option:
```bash
JAVA_OPTS="-Dlog.level=DEBUG"
```

## Production Checklist

Before deploying to production:

- [ ] Configure exactly-once semantics
- [ ] Enable TLS/SASL for Kafka connections
- [ ] Set up monitoring and alerting
- [ ] Configure resource limits (CPU, memory)
- [ ] Test failover scenarios
- [ ] Document runbooks for common issues
- [ ] Set up log aggregation
- [ ] Configure health checks
- [ ] Review security settings
- [ ] Backup configuration files
- [ ] Test with production-like data volumes
- [ ] Validate schema definitions
- [ ] Set up consumer lag alerts

## Support

For issues and questions:
- GitHub Issues: https://github.com/MaterializeIncLabs/materialize-json-inline-schema/issues
- Documentation: See README.md and QUICKSTART.md

## License

Apache License 2.0 - See LICENSE file for details
