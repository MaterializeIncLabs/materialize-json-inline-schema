# Production-ready multi-stage build for Materialize JSON Schema Attacher
# This Dockerfile creates a secure, optimized container image

# Stage 1: Build stage
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /build

# Copy Maven files for dependency caching
COPY pom.xml .
COPY src ./src

# Build the application (skip tests for faster builds - run tests in CI)
RUN apt-get update && apt-get install -y maven && \
    mvn clean package -DskipTests && \
    mv target/json-schema-attacher-*.jar target/app.jar

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre

# Metadata labels
LABEL org.opencontainers.image.title="Materialize JSON Schema Attacher" \
      org.opencontainers.image.description="Kafka Streams app to attach inline JSON schemas to Materialize sink output" \
      org.opencontainers.image.vendor="Materialize Inc" \
      org.opencontainers.image.source="https://github.com/MaterializeIncLabs/materialize-json-inline-schema" \
      org.opencontainers.image.licenses="Apache-2.0"

# Install required packages and create non-root user
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    curl \
    tini && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd -g 1001 appuser 2>/dev/null || true && \
    useradd -u 1001 -g 1001 -m appuser 2>/dev/null || true && \
    mkdir -p /app /app/config /app/logs && \
    chown -R 1001:1001 /app

# Set working directory
WORKDIR /app

# Copy JAR from builder stage
COPY --from=builder --chown=1001:1001 /build/target/app.jar /app/app.jar

# Copy default configuration
COPY --chown=1001:1001 config/application.properties /app/config/application.properties

# Switch to non-root user
USER 1001

# Expose JMX port for monitoring (optional)
EXPOSE 9999

# Health check - verify the JVM process is running
# In production, you may want a more sophisticated health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD ps aux | grep -v grep | grep "java.*app.jar" || exit 1

# Environment variables for JVM tuning
ENV JAVA_OPTS="-Xmx2g -Xms2g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs/heapdump.hprof \
    -Djava.security.egd=file:/dev/./urandom"

# Use tini for proper signal handling
ENTRYPOINT ["/usr/bin/tini", "--"]

# Run the application
CMD java ${JAVA_OPTS} -jar /app/app.jar /app/config/application.properties
