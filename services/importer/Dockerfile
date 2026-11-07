# syntax=docker/dockerfile:1
# Образ збирається з готового jar (контекст = каталог сервісу):
#   mvn -q -pl services/importer -am package -DskipTests
FROM eclipse-temurin:25-jre-noble AS extractor
WORKDIR /builder
COPY target/app.jar application.jar
# шари Spring Boot: залежності окремо від коду → швидкі повторні збірки образу
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-noble
# LibreOffice без GUI для DOC → DOCX (старі робочі програми); шрифти з кирилицею
RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-writer-nogui fonts-liberation fonts-dejavu \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --user-group --create-home --home-dir /home/app app
ENV SOFFICE_PATH=/usr/bin/soffice \
    HOME=/home/app
WORKDIR /application
COPY --from=extractor /builder/extracted/dependencies/ ./
COPY --from=extractor /builder/extracted/spring-boot-loader/ ./
COPY --from=extractor /builder/extracted/snapshot-dependencies/ ./
COPY --from=extractor /builder/extracted/application/ ./
USER app
EXPOSE 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "application.jar"]
