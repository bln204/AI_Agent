# Stage 1: Build the application
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml and download dependencies to cache them.
# Retry on transient network errors (dropped connections mid-download of large
# jars like onnxruntime) instead of failing the whole build on one bad transfer.
# The ~/.m2 cache mount persists across build re-runs, so a retry after a
# mid-build DNS/network blip only re-fetches what's still missing instead of
# re-downloading everything from scratch.
COPY pom.xml .
RUN --mount=type=cache,id=s/bca25b67-111b-44e4-9967-d5836761c338-maven-cache,target=/root/.m2 \
    mvn dependency:go-offline -B \
    -Dmaven.wagon.http.retryHandler.count=5 \
    -Dmaven.wagon.httpconnectionManager.ttlSeconds=25

# Copy the rest of the source code
COPY src ./src

# Build the application
RUN --mount=type=cache,id=s/bca25b67-111b-44e4-9967-d5836761c338-maven-cache,target=/root/.m2 \
    mvn clean package -DskipTests

# Stage 2: Create the final production image
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# LibreOffice headless — required by DocumentViewerConversionService to
# convert uploaded DOCX files to PDF for the Document Viewer feature.
# libreoffice-writer pulls in the soffice binary + its core dependencies via
# apt; --no-install-recommends avoids installing the full office suite
# (calc/impress/etc.) that isn't needed for DOCX conversion.
# curl is added for the container HEALTHCHECK below.
RUN apt-get update && \
    apt-get install -y --no-install-recommends libreoffice-writer curl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create a non-root user and group for security
RUN addgroup --system spring && adduser --system --ingroup spring spring

# Create necessary directories and set permissions
RUN mkdir -p /app/uploads && chown -R spring:spring /app/uploads
RUN mkdir -p /app/ai-cache && chown -R spring:spring /app/ai-cache

# Copy the built artifact from the build stage
COPY --from=build --chown=spring:spring /app/target/*.jar app.jar

# Expose the application port
EXPOSE 8080

# Switch to the non-root user
USER spring

# Set environment variables (can be overridden at runtime)
# Override the hardcoded transformer cache path and localhost for DB/Vector store
ENV SPRING_PROFILES_ACTIVE=production
ENV SPRING_AI_EMBEDDING_TRANSFORMER_CACHE_DIRECTORY=/app/ai-cache
ENV APP_UPLOAD_DIR=/app/uploads

# Set default timezone to UTC (or change according to needs)
ENV TZ=UTC

# Container-aware healthcheck. /actuator/health is permitAll() in
# SecurityConfig, so this works without credentials. start-period is long
# because the ONNX embedding model (~86MB, downloaded on first boot if the
# cache volume is empty) plus its warmup call can take ~25-30s before the
# app is ready — see AiEmbeddingConfig.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM memory tuning: the local ONNX embedding model + its inference buffers
# hold ~440-600MB of NATIVE memory outside the JVM heap once warmed up and
# under real XLSX/PDF embedding load (measured directly against this app's
# embedding bean), and DOCX->PDF conversion spawns a separate soffice
# process that adds further memory on top of the JVM's own footprint. Both
# count against the same container memory limit as the heap. MaxRAMPercentage
# (rather than a fixed -Xmx) keeps the heap at a fixed fraction of whatever
# container memory limit is configured on the host (Railway/Render/etc.),
# instead of hardcoding a value that silently stops making sense if that
# limit is resized later. Deploy this container with >= 2GB memory (3GB
# recommended) so the remaining ~60% comfortably covers the ONNX model,
# LibreOffice, and OS/thread overhead alongside the heap.
# Run the application
ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-XX:MaxRAMPercentage=40.0", \
    "-XX:MinRAMPercentage=20.0", \
    "-XX:MaxMetaspaceSize=256m", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "app.jar"]
