# syntax=docker/dockerfile:1
# Образ збирається з готового jar (контекст = каталог сервісу):
#   mvn -q -pl services/auth -am package -DskipTests
FROM eclipse-temurin:25-jre AS extractor
WORKDIR /builder
COPY target/app.jar application.jar
# шари Spring Boot: залежності окремо від коду → швидкі повторні збірки образу
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --user-group --create-home --home-dir /home/app app
WORKDIR /application
COPY --from=extractor /builder/extracted/dependencies/ ./
COPY --from=extractor /builder/extracted/spring-boot-loader/ ./
COPY --from=extractor /builder/extracted/snapshot-dependencies/ ./
COPY --from=extractor /builder/extracted/application/ ./
USER app
# 8085 — сервіс входу (контекст /auth), 8086 — actuator (лише healthcheck, назовні не публікується)
EXPOSE 8085 8086
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "application.jar"]
