# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

# Copy the fat JAR (with dependencies)
COPY target/sensepitch-edge-*-with-dependencies.jar app.jar

# Expose ports if needed (uncomment and set)
# EXPOSE 8080

# Entrypoint: run the JAR, env vars are available via System.getenv()
ENTRYPOINT ["java", "-jar", "app.jar"]
