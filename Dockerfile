# syntax=docker/dockerfile:1

# Packages the shaded JAR that the build verified, nothing is compiled here: run
# ./mvnw verify (CI) or ./mvnw package (locally) first. The image then holds
# exactly that JAR, built and tested in one environment, see
# doc/design/oci-image.md. The build context admits only the JAR
# (.dockerignore); with more than one JAR in target/ the COPY fails, run
# ./mvnw clean package.
#
# The base is glibc-based Ubuntu, which is required by the netty-tcnative
# native libraries bundled in the shaded JAR (they ship for glibc Linux, macOS
# and Windows, not musl), see doc/design/oci-image.md.
FROM eclipse-temurin:25-jre
WORKDIR /app

# Non-root, matching the Helm chart's runAsUser/runAsGroup. The numeric IDs need
# no /etc/passwd entry and work with readOnlyRootFilesystem.
USER 65532:65532

COPY target/sensepitch-edge-*-with-dependencies.jar /app/sensepitch-edge.jar

# Exec form, no shell: java is PID 1 and receives SIGTERM directly, so the
# graceful shutdown in README.md runs. CMD is the mounted config.yml, matching
# the Helm chart and overridable per container (docker run image <config>).
ENTRYPOINT ["java", "-jar", "/app/sensepitch-edge.jar"]
CMD ["/etc/sensepitch/config/config.yml"]

# https, metrics, admin (see charts/sensepitch-edge/values.yaml).
EXPOSE 17443 9400 9401
