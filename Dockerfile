# syntax=docker/dockerfile:1

# Build stage: compile the WAR with Azul Zulu 21. Maven 3.9 is copied from the official Maven image
# (the distribution package is an older Maven that does not retry on "429 Too Many Requests").
# The build stage runs once on the build machine's own platform: the WAR/JAR is the same for amd64 and arm64,
# so a multi-platform image build does not compile (and download from Maven Central) twice, and arm64 is not emulated.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS maven-tool

FROM --platform=$BUILDPLATFORM azul/zulu-openjdk:21 AS build
COPY --from=maven-tool /usr/share/maven /usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/local/bin/mvn
# Maven Central answers "429 Too Many Requests" when many builds download at once: retry up to 10 times, 10 s apart (Maven 3.9 reads MAVEN_ARGS)
ENV MAVEN_ARGS="-Daether.connector.http.retryHandler.count=10 -Daether.connector.http.retryHandler.interval=10000"
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# Run stage: Payara 7 (Jakarta EE 11). Change the tag to try another Payara 7 release.
FROM payara/server-full:7.2026.10
COPY docker/post-boot-commands.asadmin $POSTBOOT_COMMANDS
COPY --from=build /build/target/duckinsurance.war $DEPLOY_DIR/
