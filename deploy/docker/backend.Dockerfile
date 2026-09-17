# ─────────────────────────────────────────────────────────────────────────────
#  Monitor 后端生产运行镜像（多阶段：JDK 25 构建 → JRE 25 运行）
#
#  与仓库根目录 backend/Dockerfile 的区别：
#    backend/Dockerfile  = 开发镜像，容器里跑 `mvn spring-boot:run`（demo profile），
#                          带 Maven + 全量依赖缓存，体积大、不适合上生产。
#    本文件              = 生产镜像，产物是 jar，运行时只有 JRE。
#
#  构建上下文必须是仓库根目录：
#    docker build -f deploy/docker/backend.Dockerfile -t <registry>/monitor-backend:<tag> .
#
#  ⚠️ 为什么用 -P integration-bundle 而不是默认构建？
#     backend/src/main/java/com/monitor/server/integration/IntegrationConfig.java
#     直接 import org.springframework.kafka.test.EmbeddedKafkaKraftBroker，
#     该类只由 spring-kafka-test 提供，而默认的 production-integrations profile
#     不含此依赖 —— 也就是说「不带 -P 的 mvn package」当前编译不过（CI 里也从未
#     跑过这条路径，只跑了 -P integration-bundle 和 -Dspring-boot.run.profiles=demo）。
#     integration-bundle 包含全部生产中间件依赖（ClickHouse JDBC / Redis / Kafka）
#     并额外带上内嵌 broker。由于内嵌 broker 相关 Bean 全部标注 @Profile("integration")，
#     只要运行时不激活 integration profile，它们就不会被装配，生产行为不受影响。
#     代价是 jar 体积偏大（约 165MB）。想要精简见部署文档「附录 A」。
# ─────────────────────────────────────────────────────────────────────────────

# ── 构建阶段 ────────────────────────────────────────────────────────────────
FROM maven:3.9.12-eclipse-temurin-25 AS build

WORKDIR /workspace
ENV MAVEN_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

# 依赖层单独缓存：只有 pom 变化才重新下载依赖
COPY backend/pom.xml ./backend/pom.xml
RUN mvn --batch-mode --no-transfer-progress \
        -P integration-bundle \
        -f backend/pom.xml \
        dependency:go-offline

COPY backend/src ./backend/src
RUN mvn --batch-mode --no-transfer-progress \
        -P integration-bundle \
        -f backend/pom.xml \
        clean package -DskipTests \
    && cp backend/target/monitor-server-*.jar /workspace/app.jar

# ── 运行阶段 ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

LABEL org.opencontainers.image.title="Monitor backend" \
      org.opencontainers.image.description="Monitor 旁路式订单业务监控服务端（生产运行镜像）" \
      org.opencontainers.image.source="https://github.com/ivanmissu/monitor-order-monitoring-prototype" \
      org.opencontainers.image.licenses="Apache-2.0"

# 应用内部大量使用 Asia/Shanghai（toDate(..., 'Asia/Shanghai')、峰值时段判定等），
# 容器时区必须一致，否则分区裁剪与告警时段会错位。
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin monitor

WORKDIR /app
COPY --from=build --chown=monitor:monitor /workspace/app.jar /app/app.jar

USER 10001
EXPOSE 8080
STOPSIGNAL SIGTERM

# 用 exec 形式 + sh -c 以便展开 JAVA_OPTS；exec 保证 java 成为 PID 1 正常收到 SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
