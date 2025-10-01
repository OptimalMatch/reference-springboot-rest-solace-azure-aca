# Build stage
FROM gradle:8.5-jdk17 AS build

WORKDIR /app

# Copy build files
COPY build.gradle .
COPY settings.gradle .

# Copy source code
COPY src src

# Build the application
RUN gradle build -x test

# Runtime stage
FROM openjdk:17-jdk-slim

WORKDIR /app

# Copy the built JAR from build stage
COPY --from=build /app/build/libs/solace-service-0.0.1-SNAPSHOT.jar app.jar

# Expose port
EXPOSE 8080

# Run the application
CMD ["java", "-jar", "app.jar"]