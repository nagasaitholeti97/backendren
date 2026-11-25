# SAMS backend image for Render (also works on Railway, Fly.io, etc.)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Google's mirror of Maven Central avoids "429 Too Many Requests" on shared build servers
RUN mkdir -p /root/.m2 && printf '%s\n' '<settings><mirrors><mirror><id>google-central</id><mirrorOf>central</mirrorOf><url>https://maven-central.storage-download.googleapis.com/maven2/</url></mirror></mirrors></settings>' > /root/.m2/settings.xml
COPY pom.xml .
COPY src ./src
RUN mvn -q -B -Dmaven.wagon.http.retryHandler.count=5 -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/sams-backend-1.0.0.jar app.jar
ENV SPRING_PROFILES_ACTIVE=postgres
EXPOSE 8080
# Render sets PORT; the app reads it (server.port=${PORT:8080})
ENTRYPOINT ["java","-jar","app.jar"]
