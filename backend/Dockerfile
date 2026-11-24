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

# JDK 25 AOT cache (W8·D2, JEPs 483/514/515): one training run records the classes loaded and linked while the
# Spring context refreshes (+ method profiles) into /app/app.aot; production starts map it instead of redoing that
# work. spring.context.exit=onRefresh refreshes the context and exits without starting Lifecycle beans (no Tomcat,
# no Redis listener, no schedulers). The build has no Postgres/Redis/keys, so the training gets throwaway values:
# Flyway off, Hibernate without JDBC metadata, ephemeral JWT keys, AI + embeddings ON (as in prod, so their classes
# are in the cache). Best effort: a failed training only means no cache; at runtime AOTMode=auto ignores a missing
# or mismatched cache with a warning. Must run in THIS image (same JDK, same /app classpath as at runtime).
RUN java -XX:AOTCacheOutput=/app/app.aot -XX:+UseG1GC -XX:MaxRAMPercentage=70 -Duser.timezone=UTC \
        -Dspring.context.exit=onRefresh \
        -Dspring.profiles.active=prod \
        -Dspring.flyway.enabled=false \
        -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -DPOSTGRES_PASSWORD=aot-training -DGOOGLE_CLIENT_ID=aot-training -DGOOGLE_CLIENT_SECRET=aot-training \
        -DFRONTEND_BASE_URL=https://aot-training.invalid -DCORS_ALLOWED_ORIGINS=https://aot-training.invalid \
        -DJWT_PRIVATE_KEY= -DJWT_PUBLIC_KEY= -Dseatlock.jwt.allow-ephemeral-keys=true \
        -DSEATLOCK_AI_PROVIDER=google-genai -DGEMINI_API_KEY=aot-training -DSEATLOCK_EMBEDDINGS=transformers \
        -jar application.jar > /tmp/aot-training.log 2>&1; \
    status=$?; tail -n 25 /tmp/aot-training.log; rm -f /tmp/aot-training.log; \
    if [ -s /app/app.aot ]; then chmod a+r /app/app.aot; echo "AOT cache created: $(du -h /app/app.aot | cut -f1) (training exit $status)"; \
    else echo "AOT training produced no cache (exit $status): the image runs without one"; fi

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
# -XX:AOTCache here, not in JAVA_TOOL_OPTIONS: Compose overrides JAVA_TOOL_OPTIONS (heap %), the cache must stay.
ENTRYPOINT ["java", "-XX:AOTCache=/app/app.aot", "-jar", "application.jar"]
