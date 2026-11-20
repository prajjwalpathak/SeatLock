# syntax=docker/dockerfile:1
# SeatLock backend image. Build from the repo root:
#   docker build -t seatlock-backend:local backend
# Tests are NOT run here (they need Docker for Testcontainers); CI runs them before building.

# ---------- Stage 1: build the jar ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# 1) Wrapper + pom first: this layer (all dependencies) is reused until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

# 2) Sources: only this part rebuilds on a code change.
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && cp target/backend-*.jar application.jar

# 3) Split the fat jar into layers (Spring Boot "tools" jarmode):
#    dependencies / spring-boot-loader / snapshot-dependencies / application
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------- Stage 2: runtime ----------
# JRE only (no compiler, no Maven). Ubuntu-based = glibc, so no musl surprises.
FROM eclipse-temurin:25-jre

# curl for the container HEALTHCHECK; non-root user to run the app.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system spring \
 && useradd --system --gid spring --no-create-home spring

WORKDIR /app

# Least-changing layer first, so a code change only replaces the small "application" layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER spring:spring

# Git commit of this build (CI passes --build-arg GIT_SHA=...). Declared late so changing it
# never invalidates the dependency layers above.
ARG GIT_SHA=local
ENV SEATLOCK_REVISION=${GIT_SHA}

# G1 explicitly: under 2 GB (our 900/1200 MB limits) the JVM picks the single-threaded Serial collector, whose
# stop-the-world pauses are the suspected cause of Redis timeouts (503s) in the 500-user k6 flash sale (FAILURES F11).
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"

EXPOSE 8080

# Liveness only: "the JVM is up and not deadlocked". DB/Redis checks live in readiness.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

# exec form: java is PID 1 and receives SIGTERM directly -> graceful shutdown works.
ENTRYPOINT ["java", "-jar", "application.jar"]
