# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# MedAssist Java 微服务通用镜像（gateway / auth / empi / clinical / pharmacy / tech / emr / insurance 共用）
#
# 构建（在仓库根目录执行，SERVICE 取 gateway|auth|empi|clinical|pharmacy|tech|emr|insurance）：
#   docker build --build-arg SERVICE=auth -f platform/Dockerfile platform
#
# 设计要点：
# 1) 多阶段构建：Maven 编译产物与运行时镜像分离，运行镜像只带 JRE；
# 2) 先拷 pom 再执行 dependency:go-offline，利用层缓存减少重复下载；
# 3) 运行期非 root（uid 10001），仅 /tmp 可写（配合 K8s readOnlyRootFilesystem）；
# 4) 不内置 JAVA_TOOL_OPTIONS：SkyWalking agent 与 JVM 参数由部署侧（Helm）注入，
#    镜像保持环境无关，同一镜像可在有无 APM 的环境通用。
# ---------------------------------------------------------------------------
ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${BUILD_IMAGE} AS build
WORKDIR /src

# 依赖层缓存：先只拷 POM（多模块 reactor 需要全部模块 POM 才能解析）
COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY auth/pom.xml auth/pom.xml
COPY empi/pom.xml empi/pom.xml
COPY clinical/pom.xml clinical/pom.xml
COPY pharmacy/pom.xml pharmacy/pom.xml
COPY tech/pom.xml tech/pom.xml
COPY emr/pom.xml emr/pom.xml
COPY insurance/pom.xml insurance/pom.xml
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY . .
ARG SERVICE
# -Pprod-package：物理排除 application-dev.yml（其中内部令牌是仓库公开常量，不得进生产镜像）
RUN test -n "${SERVICE}" && \
    mvn -B -pl "${SERVICE}" -am -DskipTests -Pprod-package package && \
    cp "${SERVICE}"/target/*.jar /tmp/app.jar

FROM ${RUNTIME_IMAGE}
ARG SERVICE
LABEL org.opencontainers.image.title="medassist-${SERVICE}" \
      org.opencontainers.image.description="MedAssist platform service (${SERVICE})" \
      org.opencontainers.image.licenses="Proprietary"

RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app \
    && mkdir -p /app && chown app:app /app

WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM

# 应用参数（Hook Job 的 --medassist.migrate-only=true 等）追加在 jar 之后
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
