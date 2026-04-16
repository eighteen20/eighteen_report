# Eighteen Report（报表工具）

![License](https://img.shields.io/badge/License-AGPL%20v3%20%28or%20later%29-blue?style=flat-square&logo=gnu&logoColor=white)
![Vue](https://img.shields.io/badge/Vue%203-42b883?style=flat-square&logo=vue.js&logoColor=white)
![Vite](https://img.shields.io/badge/Vite-646CFF?style=flat-square&logo=vite&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=flat-square&logo=mysql&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white)
![AG Grid](https://img.shields.io/badge/AG%20Grid-008CBA?style=flat-square)
![EasyExcel](https://img.shields.io/badge/EasyExcel-217346?style=flat-square)

> 目标：在浏览器中完成报表模板设计，并支持渲染预览、Excel/PDF 导出，以及按数据集配置的服务端分页与「导出当前页 / 导出全部」。
>
> 开发背景：在我司业务中发现对报表工具的依赖不算重，但现有开源/免费方案往往在冻结表头、移除水印等能力上存在限制，因此使用 `Cursor` 开发并沉淀了该工具(所有代码均由AI 100%生成，也是对AI使用的一次全面尝试)。
> 
> 名称由来：因为我的猫叫18.

## 项目基本信息

**Eighteen Report** 是一个开箱即用的报表模板设计与渲染工具，支持在浏览器中编辑报表模板（单元格内容/样式/合并/冻结等），并在只读预览页中基于模板数据集进行渲染与导出。

项目采用 **Vue 3 + Spring Boot（非分离部署）** 的集成方式：

- `frontend/`：Vue 3（Vite + TypeScript）报表设计器与预览前端
- `eighteen_report/`：Spring Boot 后端（提供模板/渲染/导出/数据源等 API，并将前端构建产物作为静态资源）
- 构建输出：前端通过 `npm run build` 将静态资源输出到后端的 `src/main/resources/static`，最终以 `JAR` 形式运行

运行端口（默认）：`http://localhost:9876`

## 开源协议

本项目使用 `AGPL-3.0-or-later`（GNU Affero General Public License v3 或更高版本）授权。

- 你可以免费使用本项目（包括个人/商业）。
- 若你对本项目进行二次开发，并通过网络对外提供服务（例如部署到公网让他人访问），则需要向使用者提供你的“对应源代码（Corresponding Source）”，并以相同的 AGPL 许可方式开源衍生作品。

完整授权文本见根目录的 `LICENSE`。

## 已实现的基本功能

1. **报表模板管理**
   - 模板列表、创建、编辑、删除
   - 模板内容以 `content` 字段保存为 JSON 字符串（包含数据集、单元格矩阵、合并区域、网格元数据等）

2. **报表设计器（可交互编辑）**
   - 顶部工具栏：返回、报表名称、保存、预览
   - 支持拖拽数据集字段到表格单元格：自动生成变量表达式（例如 `${dsKey.field}`）
   - 单元格编辑与格式设置：颜色、字体、对齐等（由格式工具栏驱动）
   - 合并单元格：支持基于选区创建/取消合并块
   - 冻结表头行：在设计器内切换冻结行数
   - 列宽/行高重置与网格刷新：与预览渲染保持一致
   - 右键菜单与上下文交互：用于“插入页边留白”等功能（用于在模板内生成顶部留白行/左右留白列）

3. **报表预览（只读展示）**
   - 按模板请求后端渲染接口，得到数据矩阵、样式矩阵、合并区域配置
   - 预览页使用 AG Grid 直接渲染：
     - 冻结顶行（pinned）
     - 合并区域（rowSpan/colSpan）
     - 单元格样式回填
     - 预览页网格线显示由 `gridMeta.renderShowGridLines` 控制
   - 支持通过预览页 URL 传入动态查询参数（如 `?name=张三`），并透传给报表数据源
   - 支持双击图片单元格弹出大图预览
   - 支持 Excel 导出（`.xlsx`）与 PDF 导出
   - 若模板中某数据集启用了分页，预览顶栏可翻页、改每页条数，并可选择导出「当前页」或「全部」

4. **动态水印（服务端回调）**
   - 模板可配置 `gridMeta.watermarkCallbackUrl` 用于动态水印
   - 动态水印在 **后端** 发起回调获取展示文案，避免仅靠前端参数导致的可篡改问题
   - 回调请求具备超时、响应长度上限与 SSRF 相关约束（见 `application.yaml` 中 `eighteen.report.watermark-callback` 配置）

5. **图片上传与渲染（模板级回调中转）**
   - 报表工具本身不存储图片：仅作为“中转代理”，将业务方返回的图片 URL 写入单元格
   - 支持两种入口：
     - 本地图片：前端上传到报表后端，再由后端回调业务方接收接口
     - 网络图片：用户填写 URL，前端将 URL 发送到报表后端，再由后端拉取并回调业务方
   - 预览页根据单元格类型（`type === 'image'`）与 URL 形态渲染 `<img>`；失败时显示占位文本
  
6. **表格导出和 PDF 导出**
   - 支持导出 `.xlsx` 与 `.pdf`
   - 图片 / 二维码 / 条形码在导出时与预览风格一致地渲染

7. **数据集分页（按数据集配置）**
   - **设计器**：在「数据集」弹窗中为每个数据集单独开启分页，配置默认每页条数、请求参数名（`page` / `pageSize` / `offset` / `limit`）以及 API 返回体的字段路径（如数据列表、总数、当前页、`hasMore` 等，支持点路径）。
   - **预览**：顶栏在导出按钮左侧显示分页控件（页码、上一页 / 下一页、每页条数）；仅当至少有一个数据集开启分页时出现。
   - **SQL 数据源**：后端对 SQL 做 count + 分页查询；分页方言抽象在 `service/datasource/sql`，当前实现 MySQL（`LIMIT ? OFFSET ?`），可按库扩展。
   - **API 数据源**：按配置将分页参数写入 GET query 或 POST JSON body，并按路径解析列表与总数；测试连接时会把「数据列表字段」路径传给后端，便于左侧字段树展示业务列而非仅顶层 `code/message/data`。
   - **导出范围**：`exportScope` 为 `current` 时导出当前预览页数据；为 `all` 时对该次导出关闭预览分页并在服务端拉取全量（SQL 一次查询；API 在分页开启时会按页循环请求直至无更多数据，带安全页数上限）。详见 `技术文档.md` 第 7.5 节。

8. **动态查询参数（预览 URL 驱动）**
   - 预览页支持直接通过 URL query 传入业务查询参数，例如：
     - `/report/preview/{templateId}?name=张三`
     - `/report/preview/{templateId}?name=张三&department=研发部`
   - **SQL 数据源**：
     - 支持在 SQL 中使用 `${paramName}` 文本占位符，例如：
       - `select * from demo_user where name like '%${name}%'`
     - 当 URL 中传入 `name=张三` 时，后端实际执行：
       - `select * from demo_user where name like '%张三%'`
     - 当 URL 未传该参数时，占位符会被替换为空串，例如：
       - `select * from demo_user where name like '%%'`
     - 若某个查询条件希望在“未传参数时整段忽略”，可使用 **可选 SQL 条件块** 语法：
       - `select * from demo_user where 1=1 [[ and name = '${name}' ]] [[ and age >= ${age} ]]`
     - 规则：
       - 传了 `name` 时：保留并替换 `[[ and name = '${name}' ]]`
       - 没传 `name` 时：整段 `[[ ... ]]` 会被删除，不再退化成 `name = ''`
   - **API 数据源**：
     - 会把 URL query 参数自动追加到 API 数据源请求地址后；
     - 若参数值包含中文等特殊字符，会自动进行 URL 编码。
   - **导出一致性**：
     - Excel / PDF 导出会沿用预览页的同一套动态查询参数，保证导出结果与当前预览一致。

## 项目用法

### 1. 环境准备

- **Java**：用于运行 Spring Boot 后端（项目默认配置为开发环境的 MySQL + JPA）
- **Node.js**：用于前端开发与构建
- **MySQL**：后端需要数据库连接（默认在 `eighteen_report/src/main/resources/application.yaml` 配置）

> 你需要根据实际环境修改 `application.yaml` 中的数据库账号密码与库名，或确保本机数据库已按默认配置就绪。

### 2. 本地开发（非分离部署）

1. 启动前端开发服务器：
   - 在 `frontend/` 目录运行：`npm run dev`
2. 同时启动后端：
   - 在 `eighteen_report/` 目录运行：`./gradlew bootRun`
3. 访问：
   - `http://localhost:9876`

说明：前端在开发模式下会将 `/api` 请求代理到后端端口（避免跨域）。

### 3. 生产构建与部署

1. 构建前端静态资源：
   - `frontend/` 下执行：`npm run build`
   - 构建产物会输出到 `eighteen_report/src/main/resources/static`
2. 打包并运行后端：
   - `eighteen_report/` 下执行：`./gradlew bootJar`
   - 运行生成的 jar：`java -jar build/libs/eighteen_report-*.jar`
3. 访问：
   - `http://localhost:9876`

### 3.1 Docker 部署

Docker 相关文件集中在仓库根目录 **`docker/`**。推荐在本机执行 **`./docker/package-all.sh`** 构建前端、`bootJar` 并把 jar 复制为 **`docker/app.jar`**，将 **`docker/`** 目录（含 `app.jar`）上传到服务器后，按环境变量配置数据库连接并执行 **`./docker/run.sh`** 构建镜像并启动容器。详见 **`docker/README.md`**。

### 4. 页面路由（前端）

- `/report`：报表列表
- `/report/design`：新建报表设计器
- `/report/design/:id`：编辑已有报表
- `/report/preview/:id`：报表预览（只读）

> 注意：前端采用 Vue Router 的 History 模式，后端通过 `SpaController` 将非 `/api` 路由转发到 `index.html`，避免刷新直接 404。

### 5. 后端 API 概览

后端接口统一以 `/api/` 为前缀：

- `GET /api/report/templates`：分页查询模板列表
- `GET /api/report/template/{id}`：查询模板详情（含 `content` JSON）
- `POST /api/report/template`：创建模板
- `PUT /api/report/template/{id}`：更新模板
- `DELETE /api/report/template/{id}`：删除模板
- `POST /api/report/render`：渲染报表（变量替换 + 行展开）。请求体为 `templateId`、`params`（运行时命名参数），可选 **`page`**（1-based）、**`pageSize`**、**`datasetKey`**；响应中含 **`paginationByDataset`**。
- `POST /api/report/export`：导出文件（**`format`**：`xlsx` / `pdf`）。请求体含 `templateId`、**`queryParams`**（业务动态查询参数）、可选 **`page`**、**`pageSize`**、**`datasetKey`**、**`exportScope`**：`current` | `all`（全量导出时服务端会关闭预览分页并对 API 数据源按需逐页拉齐）。
- `GET /api/datasource/list`：数据源列表
- `POST /api/datasource/test`：测试数据集连接

### 5.1 首次运行可用的内置演示接口（`DemoUserController`）

为了帮助第一次启动项目时快速验证后端联通性、动态水印和图片回调流程，项目内置了演示接口：

- 控制器位置：`eighteen_report/src/main/java/cn/com/_1820/eighteen_report/controller/DemoUserController.java`
- 接口基础路径：`/api/demo/user`

1. **分页查询演示用户**
   - `GET /api/demo/user/list?page=1&size=20&name=张`（`page` 为 **1-based**，`size` 默认 20，`name` 为可选模糊查询）
   - 用途：作为 **API 数据集分页** 联调样例；响应为 `code/message/data`，其中 `data` 内含 `records`、`total`、`currentPage`、`pageSize`、`hasMore`，可与数据集弹窗中的响应路径映射一一对应。
   - 示例：
     - `http://localhost:9876/api/demo/user/list?page=1&size=20`
     - `http://localhost:9876/api/demo/user/list?page=1&size=20&name=张三`

2. **动态水印测试**
   - `GET /api/demo/user/watermark`
   - 用途：用于联调“动态水印回调”，返回带时间戳的水印文案。
   - 示例：
     - 浏览器直接访问：`http://localhost:9876/api/demo/user/watermark`
   - 返回示例：
     - `900821用户: 1711111111111`

3. **模拟业务方图片上传回调（本地联调）**
   - `POST /api/demo/user/image/upload`
   - `Content-Type: multipart/form-data`
   - 表单参数：`imgFile`（必须与报表工具约定一致）
   - 用途：模拟业务系统接收图片并返回可访问 URL，供报表工具将 URL 写回单元格。
   - 在报表设计器中配置：
     - 「报表设置 → 图片上传回调地址」填写 `http://localhost:9876/api/demo/user/image/upload`
   - `curl` 示例：
     - `curl -X POST "http://localhost:9876/api/demo/user/image/upload" -F "imgFile=@/绝对路径/测试图片.png"`
   - 返回示例：
     - `{"url":"http://localhost:9876/temp/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.png"}`

> 说明：
> - 该接口会把上传文件保存到工作区根目录的 `temp/` 目录；
> - 返回 URL 依赖后端的 `/temp/**` 静态资源映射（项目已包含相关配置）；
> - 以上接口主要用于本地联调与首次体验，生产环境建议替换为真实业务接口。

### 6. 报表模板 `content` 结构（关键）

模板的 `content` 保存为 JSON 字符串，核心字段包括：

- `datasets`：数据集列表（字段、数据源 ID、SQL / API、以及可选的 **`pagination`** 配置）
- `cells`：单元格矩阵（例如 `A1`、`B2` 这种引用，包含 `value/style/type` 等）
- `merges`：合并区域列表（`row/col/rowSpan/colSpan`）
- `gridMeta`：网格元数据（行列数、冻结行、列宽、留白/水印/网格线等渲染控制项）

如果你需要直接调整模板的 JSON 内容，可以参考根目录 `技术文档.md` 中给出的示例结构。
