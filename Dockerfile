# ---------- build stage ----------
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk AS builder
WORKDIR /app

# 1) Прогреваем кеш Gradle: wrapper + каталоги + корневые скрипты + подпроекты
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY app-bot/build.gradle.kts app-bot/build.gradle.kts
COPY core-domain/build.gradle.kts core-domain/build.gradle.kts
COPY core-data/build.gradle.kts core-data/build.gradle.kts
COPY core-security/build.gradle.kts core-security/build.gradle.kts
COPY core-telemetry/build.gradle.kts core-telemetry/build.gradle.kts

RUN chmod +x ./gradlew && ./gradlew --no-daemon -v

# 2) Полная сборка дистрибутива app-бота
COPY . .
RUN ./gradlew --no-daemon :app-bot:installDist

# ---------- runtime stage ----------
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre AS runner
WORKDIR /opt/app

# Устанавливаем curl для healthcheck и создаём безопасного пользователя
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && adduser --disabled-password --gecos "" appuser \
 && chown -R appuser /opt/app
USER appuser

# Копируем self-contained дистрибутив Ktor-приложения
COPY --from=builder /app/app-bot/build/install/app-bot /opt/app

# JVM defaults (переопределяемы через env)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+AlwaysActAsServerClassMachine -Dfile.encoding=UTF-8 -XX:+ExitOnOutOfMemoryError"
ENV TZ=UTC

EXPOSE 8080

# HEALTHCHECK на /health
HEALTHCHECK --interval=20s --timeout=3s --retries=3 CMD curl -fsS http://localhost:8080/health || exit 1

# Запуск (скрипт installDist учитывает JAVA_OPTS)
ENTRYPOINT ["/opt/app/bin/app-bot"]