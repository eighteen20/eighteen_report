# Eighteen Report Docker 部署（仅应用 + 外部数据库）

本方案为“最小可用部署”：容器只运行应用本身，数据库由你在外部环境单独部署（例如 MySQL 独立服务）。

## 使用流程（推荐）

1. 本机打包并产出 `docker/app.jar`：

```bash
./docker/package-all.sh
```

2. 构建镜像并运行：

```bash
./docker/run.sh
```

其中运行依赖以下环境变量（`run.sh` 会校验是否存在）：
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

例如：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://<db-host>:3306/eighteen_report?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true'
export SPRING_DATASOURCE_USERNAME='<user>'
export SPRING_DATASOURCE_PASSWORD='<password>'

./docker/run.sh
```

应用端口默认为 `9876`，容器内同样监听 `9876`（可通过 `SERVER_PORT` 覆盖）。

## 镜像构建上下文约定

- 镜像构建使用 `docker/` 目录作为 build context
- `docker/` 目录下必须存在生成好的 `app.jar`

