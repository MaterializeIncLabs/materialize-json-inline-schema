FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the fat JAR
COPY target/json-schema-attacher-1.0.0-SNAPSHOT.jar /app/app.jar

# Copy default configuration
COPY config/application.properties /app/config/application.properties

# Run the application
ENTRYPOINT ["java", "-jar", "/app/app.jar", "/app/config/application.properties"]
