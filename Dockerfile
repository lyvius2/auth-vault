# 1) GraalVM native build
FROM ghcr.io/graalvm/native-image-community:21 AS build
WORKDIR /src
# gradlew 스크립트가 xargs를 쓰는데 이 이미지에는 findutils가 없다
RUN microdnf install -y findutils && microdnf clean all
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