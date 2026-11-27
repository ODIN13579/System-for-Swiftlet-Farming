FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system swiftlet && useradd --system --gid swiftlet swiftlet
COPY --from=build /build/target/swiftletcare-backend-0.0.1-SNAPSHOT.jar app.jar
USER swiftlet
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
