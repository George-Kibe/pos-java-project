# syntax=docker/dockerfile:1
#
# One Dockerfile for every Spring Boot service; the module is chosen with --build-arg MODULE.
# Build context is backend/, because a module needs its sibling libraries to compile.

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /build
COPY . .
# A BuildKit cache mount keeps the local repository between builds, so a code change rebuilds in
# seconds instead of re-downloading the world. Format and coverage gates belong to CI, not to the
# image build - running them here would make every deploy slower for no extra safety.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl ${MODULE} -am package \
      -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true
RUN cp ${MODULE}/target/*.jar /build/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
# Non-root: a container escape should not start from uid 0.
RUN addgroup -S pos && adduser -S -G pos pos
WORKDIR /app
COPY --from=build --chown=pos:pos /build/app.jar app.jar
USER pos

# MaxRAMPercentage rather than a fixed -Xmx, so the heap follows the container limit instead of
# the host's memory, which is what causes a JVM to be OOM-killed inside a constrained container.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
