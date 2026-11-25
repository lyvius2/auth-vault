# 1) GraalVM native build
FROM ghcr.io/graalvm/native-image-community:21 AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null || true
COPY src ./src
RUN ./gradlew --no-daemon nativeCompile

# 2) runtime
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 vault && mkdir -p /data /recipes && chown vault /data
COPY --from=build /src/build/native/nativeCompile/auth-vault /app/auth-vault
COPY recipes /recipes
USER vault
EXPOSE 8020
ENTRYPOINT ["/app/auth-vault", "-Djdk.httpclient.allowRestrictedHeaders=host"]