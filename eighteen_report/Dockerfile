# =========================
# Runtime image (方案 A)
# 本地先执行：
#   ./gradlew clean bootJar -x test
# 再把 build/libs/*.jar 上传到服务器后构建镜像。
# =========================
FROM bellsoft/liberica-openjre-alpine:25

WORKDIR /app

# 直接拷贝本地已编译好的可执行 jar（非容器内编译）。
COPY build/libs/*.jar /app/app.jar

# Spring Boot 服务端口
EXPOSE 9876

# 可通过环境变量覆盖 JVM 参数
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
