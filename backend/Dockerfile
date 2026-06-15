# netatlas backend — slim runtime image.
#
# The fat jar is built on the HOST (./gradlew :backend:buildFatJar) rather than inside
# this image, because the Gradle build includes the :composeApp Android module which
# needs the Android SDK — unavailable in a plain JRE/Cloud Build environment. So this
# Dockerfile just packages the pre-built jar + the SQL migrations the app applies on boot.
#
# Build context = repo root:  docker build -f backend/Dockerfile -t netatlas-backend .
FROM eclipse-temurin:17-jre

WORKDIR /app

# Pre-built fat jar (run `./gradlew :backend:buildFatJar` first).
COPY backend/build/libs/backend-all.jar /app/app.jar

# Database.applyMigrations() reads these from disk at startup; findMigrationsDir()
# resolves "db/migrations" relative to the working dir (/app).
COPY backend/db/migrations /app/db/migrations

# Cloud Run injects PORT; Application.kt reads it (defaults to 8080).
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -jar /app/app.jar"]
