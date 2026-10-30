FROM eclipse-temurin:24-jre AS extract
WORKDIR /work
ARG SERVICE
COPY services/${SERVICE}/build/libs/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:24-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
COPY --from=extract /work/extracted/dependencies/ ./
COPY --from=extract /work/extracted/spring-boot-loader/ ./
COPY --from=extract /work/extracted/snapshot-dependencies/ ./
COPY --from=extract /work/extracted/application/ ./
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
