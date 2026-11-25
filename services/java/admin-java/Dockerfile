# syntax=docker/dockerfile:1.7

# Multi-module build from context services/java (used by infra/docker-compose.yml)
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY .mvn/ .mvn/
COPY pom.xml .
COPY foliarc-common/pom.xml foliarc-common/
COPY biz-java/pom.xml biz-java/
COPY admin-java/pom.xml admin-java/
COPY foliarc-common/src/ foliarc-common/src/
COPY admin-java/src/ admin-java/src/

RUN --mount=type=cache,id=m2-foliarc,target=/root/.m2/repository \
    mvn -B -s .mvn/settings.xml -Daether.syncContext.named.factory=noop \
        -pl admin-java -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S foliarc && adduser -S -G foliarc foliarc
WORKDIR /app
COPY --from=builder --chown=foliarc:foliarc /build/admin-java/target/admin-java-*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
USER foliarc
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD wget -qO- "http://127.0.0.1:${ADMIN_JAVA_PORT:-8081}/api/v1/admin/health" >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
