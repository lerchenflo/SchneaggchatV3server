# Build stage (Alpine)
FROM eclipse-temurin:25-jdk-alpine AS build
ARG TZ=Europe/Vienna
WORKDIR /app

# Use apk (Alpine) and set timezone (no dpkg on Alpine)
RUN apk add --no-cache git tzdata && \
    ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo "$TZ" > /etc/timezone

# Copy only dependency-related files first
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Download dependencies (this layer will be cached)
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

# Now copy source code
COPY src src

# Build the application
RUN ./gradlew clean build -x test --no-daemon


# Runtime stage (Alpine)
FROM eclipse-temurin:25-jre-alpine
ARG TZ=Europe/Vienna
WORKDIR /app

RUN apk add --no-cache tzdata && \
    ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo "$TZ" > /etc/timezone

COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
