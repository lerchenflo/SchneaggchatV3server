# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-alpine AS build
ARG TZ=Europe/Vienna
WORKDIR /app

# Install git and set timezone
RUN apk add --no-cache git tzdata && \
    ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo "$TZ" > /etc/timezone

# Clone the repository
ARG GIT_REPO_URL="https://github.com/lerchenflo/SchneaggchatV3server.git"
ARG GIT_BRANCH=main
RUN git clone --branch ${GIT_BRANCH} ${GIT_REPO_URL} . && \
    rm -rf .git

# Build the runnable jar. Gradle's home (wrapper distribution + downloaded dependencies) lives in a
# BuildKit cache mount that is kept between builds, so Gradle only downloads new or changed
# dependencies. BuildKit creates the cache folder on the first build if it does not exist;
# `docker builder prune` clears it. sharing=locked: parallel builds wait instead of sharing it.
RUN --mount=type=cache,id=schneaggchat-gradle,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && \
    ./gradlew bootJar --no-daemon

# Production runtime stage
FROM eclipse-temurin:25-jre-alpine
ARG TZ=Europe/Vienna
WORKDIR /app

RUN apk add --no-cache tzdata && \
    ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo "$TZ" > /etc/timezone
# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

RUN mkdir -p /app/images && \
    chown -R spring:spring /app/images && \
    chmod -R 755 /app/images

RUN mkdir -p /app/images_messages && \
    chown -R spring:spring /app/images_messages && \
    chmod -R 755 /app/images_messages

RUN mkdir -p /app/audio_messages && \
    chown -R spring:spring /app/audio_messages && \
    chmod -R 755 /app/audio_messages


# Copy the built artifact
COPY --from=build --chown=spring:spring /app/build/libs/*.jar app.jar

#Switch to non root user after directory setup
USER spring:spring


EXPOSE 8080

# Production JVM settings
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseG1GC", \
    "-XX:+OptimizeStringConcat", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=prod", \
    "-jar", \
    "app.jar"]