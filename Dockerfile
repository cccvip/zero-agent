# Agentic RAG 演示项目 — 多阶段构建
# 构建阶段：JDK 21 + Maven 打包；运行阶段：仅 JRE
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# 先拷 pom 预热依赖缓存（改依赖才触发重下）
COPY pom.xml .
# 本地仓库在 F:\maven\repository（自定义 settings），容器内用默认 ~/.m2，首次会从中央仓库下载
COPY src ./src
# 语料（loadDocuments 读的是工作目录下的 md 文件）
COPY ReAct01.md ReAct02.md ./
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY --from=build /app/ReAct01.md /app/ReAct02.md ./
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
