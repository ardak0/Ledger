# Runtime image only. The jar is built on the host first:
#
#     ./mvnw -DskipTests package && docker compose up --build
#
# Why not build inside Docker: the build generates jOOQ classes from the real schema by starting a throwaway
# Postgres container, and there is no Docker daemon inside a `docker build`. Building on the host keeps that
# design (no generated code committed, no drift) at the cost of this two-step.
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 --no-create-home ledger
WORKDIR /app
COPY target/ledger-*.jar /app/ledger.jar
USER ledger

EXPOSE 8080
# MaxRAMPercentage: size the heap from the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/ledger.jar"]
