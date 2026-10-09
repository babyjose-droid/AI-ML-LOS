# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/rhythm-los-*.jar /src/app.jar

# ---- run ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S rhythm && adduser -S rhythm -G rhythm && mkdir -p /data/documents && chown -R rhythm:rhythm /data
USER rhythm
WORKDIR /app
COPY --from=build /src/app.jar app.jar
ENV STORAGE_PATH=/data/documents JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=12 CMD wget -qO- http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
