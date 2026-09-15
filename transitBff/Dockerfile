# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk-jammy@sha256:ef4374b4b6b9d813dd3f5b593a35ec9a820cfb64a55994798147cc73435a0208 AS builder

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY transitBff/build.gradle.kts transitBff/build.gradle.kts
COPY transitBff/src transitBff/src

RUN --mount=type=cache,target=/root/.gradle \
    mkdir -p androidApp shared && \
    chmod 0755 gradlew && \
    ./gradlew --no-daemon --no-build-cache --stacktrace --configure-on-demand \
    :transitBff:installDist

FROM eclipse-temurin:17-jre-jammy@sha256:ec72ba5962b45ae4e7f96bfb5ebf6eeb34a488b967f937c8e14f0aaec688954f AS runtime

RUN apt-get update && \
    apt-get install --no-install-recommends --yes gosu=1.14-1ubuntu0.1 && \
    rm -rf /var/lib/apt/lists/* && \
    usermod --unlock root && \
    install -d -o root -g root -m 0700 /root/.ssh && \
    groupadd --gid 1000 transit && \
    useradd --uid 1000 --gid transit --home-dir /app --shell /usr/sbin/nologin transit

WORKDIR /app

COPY --from=builder /workspace/transitBff/build/install/transitBff/ /app/
COPY transitBff/batumi-theta-capability-control.example.json /app/config/batumi-capabilities.json
COPY transitBff/deploy/render/start-transit-bff.sh /usr/local/bin/start-transit-bff

RUN chmod 0444 /app/config/batumi-capabilities.json && \
    chmod 0755 /usr/local/bin/start-transit-bff && \
    mkdir /app/storage

ENV BFF_STORAGE_ROOT=/app/storage

EXPOSE 10000

ENTRYPOINT ["/usr/local/bin/start-transit-bff"]
