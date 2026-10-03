# syntax=docker/dockerfile:1
FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system vinylmatch \
    && useradd --system --gid vinylmatch --home-dir /app vinylmatch \
    && mkdir -p cache logs \
    && chown vinylmatch:vinylmatch cache logs
COPY --from=build --chown=vinylmatch:vinylmatch /build/target/VinylMatch.jar ./target/VinylMatch.jar
COPY --from=build --chown=vinylmatch:vinylmatch /build/target/frontend ./frontend
COPY --chown=vinylmatch:vinylmatch --chmod=755 scripts/start-render.sh ./start-render.sh
ENV PORT=10000 \
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+ExitOnOutOfMemoryError"
USER vinylmatch
EXPOSE 10000
ENTRYPOINT ["./start-render.sh"]
