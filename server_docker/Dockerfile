FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

# Install git
RUN apk add --no-cache git

RUN apk add --no-cache git tzdata && \
    ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo "$TZ" > /etc/timezone

# Clone the repository
ARG GIT_REPO_URL="https://github.com/lerchenflo/SchneaggchatV3server.git"
ARG GIT_BRANCH=main
RUN git clone --branch ${GIT_BRANCH} ${GIT_REPO_URL} . && \
    rm -rf .git

# Download dependencies (cached layer)
RUN chmod +x gradlew && \
    ./gradlew dependencies --no-daemon

# Build the application with production settings
RUN ./gradlew clean build -x test --no-daemon

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