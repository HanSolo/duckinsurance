# Build stage: compile the WAR
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# Run stage: Payara 7 (Jakarta EE 11). Change the tag to try another Payara 7 release.
FROM payara/server-full:7.2026.7
COPY docker/post-boot-commands.asadmin $POSTBOOT_COMMANDS
COPY --from=build /build/target/duckinsurance.war $DEPLOY_DIR/
