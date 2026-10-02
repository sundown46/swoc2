# syntax=docker/dockerfile:1
#
# Single image: the backend serves the API, the realtime endpoints and the built SPA from one
# port (ARCHITECTURE §2). Build context must be the repository root:
#   docker build -f deploy/docker/Dockerfile -t swoc2:dev .
#
# Versions pinned per docs/adr/0001-technology-versions.md.

FROM node:24-alpine AS frontend-build
WORKDIR /src/frontend
COPY frontend/ .
RUN corepack enable \
  && corepack use pnpm@12.8.1 \
  && pnpm install --frozen-lockfile \
  && pnpm --filter @swoc2/web run build

FROM eclipse-temurin:25-jdk AS backend-build
WORKDIR /src/backend
COPY backend/ .
# The static-resource wiring (config.json, base-path, forwarded headers) is ROADMAP P0 item 4;
# placing the built SPA on the classpath here only gives Spring Boot's default static-resource
# serving for free, it does not implement that item.
COPY --from=frontend-build /src/frontend/apps/web/dist swoc2-app/src/main/resources/static
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=backend-build /src/backend/swoc2-app/target/swoc2-app.jar app.jar
EXPOSE 5080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
