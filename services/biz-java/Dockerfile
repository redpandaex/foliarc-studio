# syntax=docker/dockerfile:1.7

# ==============================================================================
# Stage 1: Build Spring Boot 4 fat JAR with Java 21 + project HTTPS Maven mirrors
# ==============================================================================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

COPY .mvn/settings.xml .mvn/settings.xml
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s .mvn/settings.xml dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s .mvn/settings.xml package -DskipTests && \
    find target -maxdepth 1 -name 'biz-java-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -exec cp {} /build/app.jar \;

# ==============================================================================
# Stage 2: Minimal non-root Java 21 JRE runtime image
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S foliarc && adduser -S -G foliarc foliarc

WORKDIR /app

COPY --from=builder --chown=foliarc:foliarc /build/app.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

USER foliarc

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/api/v1/health >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
