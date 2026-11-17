# Sjakkportal backend. Built by Render from render.yaml; also runs anywhere Docker does.
#   docker build -t sjakkportal-api backend
#   docker run -p 8080:8080 --env-file backend/.env sjakkportal-api

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Dependencies first, so code changes reuse the cached layer.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar \
    && java -Djarmode=tools -jar build/libs/sjakkportal.jar extract --destination /app

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S sjakk && adduser -S sjakk -G sjakk
WORKDIR /app
COPY --from=build --chown=sjakk:sjakk /app ./
USER sjakk
# Tuned for small instances (Render free: 512 MB, a fraction of a CPU): serial GC, capped heap,
# C1-only JIT for a faster start. Override JAVA_TOOL_OPTIONS on bigger machines.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "sjakkportal.jar"]
