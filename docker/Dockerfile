# =========================
# Runtime image
# JDK: bellsoft/liberica-openjre-alpine:25
#
# 约定：
# - 构建镜像时（docker build）build context 以 docker/ 目录为根
# - docker/ 目录下存在生成好的可执行 jar：app.jar
# =========================
FROM bellsoft/liberica-openjre-alpine:25

WORKDIR /app

# 直接拷贝本地已编译好的可执行 jar（不在镜像内编译）
COPY app.jar /app/app.jar

# Spring Boot 服务端口
EXPOSE 9876

# 可通过环境变量覆盖 JVM 参数（可选）
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]

