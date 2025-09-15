# 小新API平台服务 Dockerfile
# 技术说明：为Spring Boot应用创建Docker镜像

# 1. 基础镜像选择：使用Eclipse Temurin OpenJDK 17 JRE
# 原理：eclipse-temurin:17-jre 是OpenJDK官方继承者，只包含运行时，镜像精简
FROM eclipse-temurin:17-jre

# 2. 设置维护者信息（生产环境建议加上）
LABEL maintainer="xiaoxin-team"

# 3. 安装必要的系统工具（用于调试和健康检查）
# 原理：生产环境可能需要curl做健康检查，tzdata设置时区
RUN apt-get update && apt-get install -y \
    curl \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

# 4. 设置时区（重要：避免日志时间错乱）
# 原理：Docker容器默认UTC时区，中国项目需要设置为上海时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 5. 创建应用目录和用户（安全最佳实践）
# 原理：不要用root用户运行应用，提高安全性
RUN addgroup --system app && adduser --system --group app --home /app
WORKDIR /app
RUN chown app:app /app

# 6. 创建日志目录和Dubbo缓存目录
RUN mkdir -p /app/logs && chown app:app /app/logs
RUN mkdir -p /app/.dubbo && chown app:app /app/.dubbo

# 7. 复制jar包到容器
# 技巧：*.jar 通配符，避免版本号变化导致构建失败
COPY target/*.jar app.jar
RUN chown app:app app.jar

# 8. 切换到非root用户
USER app

# 9. 暴露端口（声明性的，实际端口映射在docker run时指定）
EXPOSE 8080

# 10. 健康检查（生产环境重要特性）
# 原理：Docker可以通过健康检查自动重启不健康的容器
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/api/health || exit 1

# 11. 启动命令 - 使用docker环境配置
# 技术要点：
# -Djava.awt.headless=true：服务器环境无图形界面
# -Dspring.profiles.active=docker：使用docker环境配置
# -Xmx512m：限制内存使用，避免容器被杀死
ENTRYPOINT ["java", \
    "-Djava.awt.headless=true", \
    "-Dspring.profiles.active=docker", \
    "-Xmx512m", \
    "-Xms256m", \
    "-jar", "app.jar"]
