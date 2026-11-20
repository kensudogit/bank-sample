# ---- Build stage: Gradle builds the WAR (compiled for Java 8) ----
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
COPY src ./src
# Unit tests run on the host (./gradlew test) because they read ../db/init
RUN gradle --no-daemon -q war copyJdbcDriver

# ---- Runtime stage: IBM WebSphere Application Server Liberty on Java 8 ----
FROM icr.io/appcafe/websphere-liberty:kernel-java8-openj9-ubi-minimal

COPY --chown=1001:0 src/main/liberty/config/server.xml /config/server.xml
# Installs only the features listed in server.xml
RUN features.sh

COPY --chown=1001:0 --from=build /src/build/liberty/jdbc/ /opt/ibm/wlp/usr/shared/resources/jdbc/
COPY --chown=1001:0 --from=build /src/build/libs/bank-api.war /config/apps/bank-api.war

RUN configure.sh

EXPOSE 9080
