# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build          # compile + test
./gradlew bootRun        # run locally
./gradlew test           # run all tests
./gradlew test --tests "dev.juergenreiss.cdrm.SomeTest"  # run single test class
./gradlew clean          # wipe build artifacts
```

## Architecture

**cdrm** is a Kotlin/Spring Boot 4 backend service. Package root: `dev.juergenreiss.cdrm`.

Key dependencies already wired in `build.gradle.kts`:
- **Spring Web MVC** — REST controllers
- **Spring Data JPA + PostgreSQL** — persistence layer
- **Spring Security** — authentication/authorization
- **Quartz Scheduler** — background jobs
- **Spring AI MCP Server WebMVC** — Model Context Protocol server integration
- **SpringDoc OpenAPI** — Swagger UI at `/swagger-ui.html`
- **Actuator + Micrometer/Prometheus** — metrics at `/actuator/prometheus`

Configuration lives in `src/main/resources/application.yaml`. The app requires a PostgreSQL instance at runtime — datasource settings must be provided there or via environment variables.

Kotlin compiler targets JVM 24 (toolchain targets Java 26 but is constrained by Kotlin compatibility).
