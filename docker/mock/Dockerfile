# 小新API模拟服务 Dockerfile
# 技术说明：为Mock服务创建轻量级Docker镜像

FROM eclipse-temurin:17-jre

LABEL maintainer="xiaoxin-team"

# Mock服务比较简单，只需要基本的系统工具
RUN apt-get update && apt-get install -y \
    curl \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 创建应用用户
RUN addgroup --system app && adduser --system --group app
WORKDIR /app
RUN chown app:app /app

# 复制jar包
COPY target/*.jar app.jar
RUN chown app:app app.jar

# 切换用户
USER app

# Mock服务端口
EXPOSE 8081

# 健康检查（使用actuator健康检查端点，注意 mock 的 context-path 为 /api）
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8081/api/actuator/health || exit 1

# 启动命令
# Mock服务资源需求较小
ENTRYPOINT ["java", \
    "-Djava.awt.headless=true", \
    "-Dspring.profiles.active=docker", \
    "-Xmx256m", \
    "-Xms128m", \
    "-jar", "app.jar"]
