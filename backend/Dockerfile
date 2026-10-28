FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace/backend
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --gid 10001 springrag && useradd --uid 10001 --gid springrag --no-create-home springrag
COPY --from=build --chown=springrag:springrag /workspace/backend/target/spring-rag-0.1.0.jar app.jar
USER springrag
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
