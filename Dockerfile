# syntax=docker/dockerfile:1

FROM eclipse-temurin:26-jdk AS build
WORKDIR /app

# Wrapper and build scripts first so dependency resolution is cached in its own layer,
# independent of source changes.
COPY gradlew ./
COPY gradle ./gradle
RUN ./gradlew --version

COPY build.gradle.kts settings.gradle.kts ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew dependencies --no-daemon

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

# Splits the boot jar into layers: the ~150 third-party dependency jars (which rarely
# change) land in their own filesystem layer, separate from our own compiled classes
# (which change on every build) — see the "dependencies"/"application" COPYs below, this
# is what actually answers "are the libraries in a dedicated layer".
FROM eclipse-temurin:26-jdk AS extract
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:26-jre AS runtime
WORKDIR /app

RUN groupadd --system cdrm && useradd --system --gid cdrm --no-create-home --shell /usr/sbin/nologin cdrm

COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./

USER cdrm
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
