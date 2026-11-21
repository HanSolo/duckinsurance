# Build stage: compile the WAR with Azul Zulu 21 (Maven from the distribution package)
FROM azul/zulu-openjdk:21 AS build
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# Run stage: Payara 7 (Jakarta EE 11). Change the tag to try another Payara 7 release.
FROM payara/server-full:7.2026.10
COPY docker/post-boot-commands.asadmin $POSTBOOT_COMMANDS
COPY --from=build /build/target/duckinsurance.war $DEPLOY_DIR/
