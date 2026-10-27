# ---- Build stage ----
# Roda na arquitetura de quem builda (BUILDPLATFORM): o JAR independe de arquitetura, entao o
# build multi-arch (amd64 + arm64, VPS Ampere) nao paga o Maven emulado pelo QEMU.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# Cache de dependencias: baixa antes de copiar o codigo-fonte, so refaz se o pom.xml mudar.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# fontconfig + uma fonte: o JasperReports (DANFE e relatorio de conformidade) precisa de fonte
# instalada para medir o texto, e a imagem alpine nao traz nenhuma.
RUN apk add --no-cache fontconfig ttf-dejavu \
    && addgroup -S spring && adduser -S spring -G spring
COPY --from=build --chown=spring:spring /build/target/backend-tcc-*.jar app.jar
USER spring

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
