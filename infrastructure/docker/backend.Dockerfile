# Build context: repository root.
FROM eclipse-temurin:24-jdk-noble AS build
WORKDIR /src
COPY . .
ENV AVERYN_BACKEND_ONLY=1
RUN ./gradlew --no-daemon :backend:installDist

FROM eclipse-temurin:24-jre-noble
RUN useradd --system --uid 10001 averyn
COPY --from=build /src/backend/build/install/backend /opt/backend
USER averyn
EXPOSE 8080
ENTRYPOINT ["/opt/backend/bin/backend"]
