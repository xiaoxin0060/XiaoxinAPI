# 小新API网关服务 Dockerfile
# 技术说明：为Spring Cloud Gateway应用创建Docker镜像

# 使用与平台服务相同的基础镜像（保持一致性）
FROM eclipse-temurin:17-jre

LABEL maintainer="xiaoxin-team"

# 安装系统依赖
RUN apt-get update && apt-get install -y \
    curl \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 创建应用用户
RUN addgroup --system app && adduser --system --group app --home /app
WORKDIR /app
RUN chown app:app /app

# 创建日志目录和Dubbo缓存目录
RUN mkdir -p /app/logs && chown app:app /app/logs
RUN mkdir -p /app/.dubbo && chown app:app /app/.dubbo

# 复制jar包
COPY target/*.jar app.jar
RUN chown app:app app.jar

# 切换用户
USER app

# 网关服务端口
EXPOSE 8090

# 网关健康检查（检查路由是否可用）
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8090/actuator/health || exit 1

# 启动命令
# 网关服务需要更多内存处理请求转发
ENTRYPOINT ["java", \
    "-Djava.awt.headless=true", \
    "-Dspring.profiles.active=docker", \
    "-Xmx768m", \
    "-Xms512m", \
    "-jar", "app.jar"]
