package cn.com._1820.eighteen_report.service;

import cn.com._1820.eighteen_report.dto.DataSourceConfigDto;
import cn.com._1820.eighteen_report.dto.ReportQueryResponse;
import cn.com._1820.eighteen_report.dto.DataSourceTestRequest;
import cn.com._1820.eighteen_report.dto.DataSourceTestResponse;
import cn.com._1820.eighteen_report.entity.DataSourceConfig;
import cn.com._1820.eighteen_report.repository.DataSourceConfigRepository;
import cn.com._1820.eighteen_report.service.datasource.sql.SqlPaginationDialect;
import cn.com._1820.eighteen_report.service.datasource.sql.SqlPaginationDialectResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据源配置服务：管理 JDBC/API 类型的数据源，提供 SQL 执行和 API 调用能力。支持命名参数(:paramName)的 SQL 查询和 RESTful API 数据获取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceConfigService {
    private static final Pattern TEMPLATE_PARAM_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern OPTIONAL_SQL_BLOCK_PATTERN = Pattern.compile("\\[\\[(.*?)]\\]", Pattern.DOTALL);

    /** 测试查询时的最大预览行数 */
    private static final int PREVIEW_ROW_LIMIT = 20;
    /** SQL/API 查询超时时间（秒） */
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private final DataSourceConfigRepository repository;
    private final SqlPaginationDialectResolver dialectResolver;

    /**
     * 查询所有数据源配置，按名称升序排列
     */
    public List<DataSourceConfigDto> list() {
        return repository.findAllByOrderByNameAsc().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * 根据 ID 查询数据源配置
     */
    public DataSourceConfigDto getById(String id) {
        DataSourceConfig c = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
        return toDto(c);
    }

    /**
     * 创建新的数据源配置
     */
    @Transactional
    public DataSourceConfigDto create(DataSourceConfigDto dto) {
        DataSourceConfig entity = toEntity(dto);
        entity.setId(null);
        entity = repository.save(entity);
        return toDto(entity);
    }

    /**
     * 更新数据源配置，密码为空时保留原密码
     */
    @Transactional
    public DataSourceConfigDto update(String id, DataSourceConfigDto dto) {
        DataSourceConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
        existing.setName(dto.getName());
        existing.setType(dto.getType());
        existing.setUrl(dto.getUrl());
        existing.setUsername(dto.getUsername());
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            existing.setPassword(dto.getPassword());
        }
        existing.setDriverClass(dto.getDriverClass());
        existing = repository.save(existing);
        return toDto(existing);
    }

    /**
     * 删除数据源配置
     */
    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("数据源不存在: " + id);
        }
        repository.deleteById(id);
    }

    /**
     * 测试数据源：执行 SQL 或调用 API，返回字段列表和前 N 行数据。
     */
    public DataSourceTestResponse test(DataSourceTestRequest request) {
        if ("SQL".equalsIgnoreCase(request.getType())) {
            return testSql(request);
        }
        if ("API".equalsIgnoreCase(request.getType())) {
            return testApi(request);
        }
        throw new IllegalArgumentException("不支持的数据源类型: " + request.getType());
    }

    /**
     * 对指定数据源执行 SQL 查询，返回字段名和行数据。
     * 供 ReportRenderService 复用。
     */
    public DataSourceTestResponse executeSql(String dataSourceId, String sql, Map<String, Object> params) {
        DataSourceConfig config = repository.findById(dataSourceId)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + dataSourceId));
        return doExecuteSql(config, sql, params, 0);
    }

    /**
     * 执行 SQL 查询（分页版本）：返回字段、行数据与分页元信息。
     */
    public QueryResult executeSqlPaged(
            String dataSourceId,
            String sql,
            Map<String, Object> params,
            PageRequest pageRequest
    ) {
        DataSourceConfig config = repository.findById(dataSourceId)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + dataSourceId));
        return doExecuteSqlPaged(config, sql, params, pageRequest);
    }

    private DataSourceTestResponse testSql(DataSourceTestRequest request) {
        DataSourceConfig config = repository.findById(request.getDataSourceId())
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + request.getDataSourceId()));
        validateSql(request.getSql());
        return doExecuteSql(config, request.getSql(), request.getParams(), PREVIEW_ROW_LIMIT);
    }

    private void validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String trimmed = sql.strip().toUpperCase(Locale.ROOT);
        if (!trimmed.startsWith("SELECT") && !trimmed.startsWith("WITH")) {
            throw new IllegalArgumentException("仅允许 SELECT 查询语句");
        }
    }

    /**
     * 执行 SQL 查询。rowLimit <= 0 表示不限制行数（使用查询超时兜底）。
     */
    private DataSourceTestResponse doExecuteSql(DataSourceConfig config, String sql,
                                                 Map<String, Object> params, int rowLimit) {
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        String resolvedSql = substituteSqlTemplate(sql, safeParams);
        validateSql(resolvedSql);

        SqlParseResult parsed = parseNamedParams(resolvedSql, safeParams);
        log.info("执行报表 SQL: {}", buildSqlLog(parsed.sql(), parsed.values()));

        try (Connection conn = DriverManager.getConnection(config.getUrl(), config.getUsername(), config.getPassword());
             PreparedStatement ps = conn.prepareStatement(parsed.sql())) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            if (rowLimit > 0) {
                ps.setMaxRows(rowLimit);
            }
            for (int i = 0; i < parsed.values().size(); i++) {
                ps.setObject(i + 1, parsed.values().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> fields = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    fields.add(meta.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return DataSourceTestResponse.builder().fields(fields).previewRows(rows).build();
            }
        } catch (SQLException e) {
            throw new RuntimeException("SQL 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 :paramName 形式的命名参数替换为 ? 占位符，同时收集参数值。
     */
    private SqlParseResult parseNamedParams(String sql, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        List<Object> values = new ArrayList<>();
        int len = sql.length();
        int i = 0;
        boolean inSingleQuote = false;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
                sb.append(c);
                i++;
            } else if (c == ':' && !inSingleQuote && i + 1 < len && Character.isLetter(sql.charAt(i + 1))) {
                int start = i + 1;
                int end = start;
                while (end < len && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                String name = sql.substring(start, end);
                sb.append('?');
                values.add(params.get(name));
                i = end;
            } else {
                sb.append(c);
                i++;
            }
        }
        return new SqlParseResult(sb.toString(), values);
    }

    private record SqlParseResult(String sql, List<Object> values) {}

    /**
     * 执行 API 调用，供 ReportQueryService 复用。
     */
    public DataSourceTestResponse executeApi(String apiUrl, String apiMethod,
                                              Map<String, String> apiHeaders, String apiBody,
                                              Map<String, Object> runtimeParams) {
        return doExecuteApi(apiUrl, apiMethod, apiHeaders, apiBody, 0, runtimeParams);
    }

    /**
     * 执行 API 调用（分页版本）：支持按配置路径提取 records/total/current/pageSize/hasMore。
     */
    public QueryResult executeApiPaged(
            String apiUrl,
            String apiMethod,
            Map<String, String> apiHeaders,
            String apiBody,
            PageRequest pageRequest,
            ApiPageMapping mapping,
            Map<String, Object> runtimeParams
    ) {
        try {
            String httpMethod = apiMethod != null ? apiMethod.toUpperCase(Locale.ROOT) : "GET";
            ObjectMapper om = new ObjectMapper();
            Map<String, Object> safeRuntimeParams = normalizeRuntimeParams(runtimeParams);

            Map<String, Object> requestParams = buildPageRequestParams(pageRequest, mapping);

            String finalUrl = appendQueryParams(apiUrl, safeRuntimeParams);
            String finalBody = substituteTemplate(apiBody, safeRuntimeParams);
            Map<String, String> finalHeaders = substituteTemplateMap(apiHeaders, safeRuntimeParams);
            if (!requestParams.isEmpty()) {
                if ("POST".equals(httpMethod)) {
                    finalBody = mergeJsonBody(finalBody, requestParams, om);
                } else {
                    finalUrl = appendQueryParams(finalUrl, requestParams);
                }
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .timeout(Duration.ofSeconds(QUERY_TIMEOUT_SECONDS));
            if (finalHeaders != null) {
                finalHeaders.forEach(builder::header);
            }
            if ("POST".equals(httpMethod)) {
                String bodyStr = finalBody != null ? finalBody : "{}";
                builder.POST(HttpRequest.BodyPublishers.ofString(bodyStr));
                builder.header("Content-Type", "application/json");
            } else {
                builder.GET();
            }

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Object json = om.readValue(response.body(), Object.class);
            return parseApiQueryResult(json, pageRequest, mapping);
        } catch (Exception e) {
            throw new RuntimeException("API 调用失败: " + e.getMessage(), e);
        }
    }

    private DataSourceTestResponse testApi(DataSourceTestRequest request) {
        return doExecuteApi(
                request.getApiUrl(),
                request.getApiMethod(),
                request.getApiHeaders(),
                request.getApiBody(),
                PREVIEW_ROW_LIMIT,
                request.getApiRecordsPath(),
                request.getParams()
        );
    }

    private DataSourceTestResponse doExecuteApi(String url, String method,
                                                 Map<String, String> headers, String body,
                                                 int rowLimit,
                                                 Map<String, Object> runtimeParams) {
        return doExecuteApi(url, method, headers, body, rowLimit, null, runtimeParams);
    }

    private DataSourceTestResponse doExecuteApi(String url, String method,
                                                 Map<String, String> headers, String body,
                                                 int rowLimit, String recordsPath) {
        return doExecuteApi(url, method, headers, body, rowLimit, recordsPath, Collections.emptyMap());
    }

    private DataSourceTestResponse doExecuteApi(String url, String method,
                                                 Map<String, String> headers, String body,
                                                 int rowLimit, String recordsPath,
                                                 Map<String, Object> runtimeParams) {
        try {
            String httpMethod = method != null ? method.toUpperCase(Locale.ROOT) : "GET";
            Map<String, Object> safeRuntimeParams = normalizeRuntimeParams(runtimeParams);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(appendQueryParams(url, safeRuntimeParams)))
                    .timeout(Duration.ofSeconds(QUERY_TIMEOUT_SECONDS));

            Map<String, String> resolvedHeaders = substituteTemplateMap(headers, safeRuntimeParams);
            if (resolvedHeaders != null) {
                resolvedHeaders.forEach(builder::header);
            }

            if ("POST".equals(httpMethod) && body != null) {
                builder.POST(HttpRequest.BodyPublishers.ofString(substituteTemplate(body, safeRuntimeParams)));
                builder.header("Content-Type", "application/json");
            } else {
                builder.GET();
            }

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            ObjectMapper om = new ObjectMapper();
            Object json = om.readValue(response.body(), Object.class);
            return parseApiResponse(json, rowLimit, recordsPath);
        } catch (Exception e) {
            throw new RuntimeException("API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 API 返回的 JSON：支持数组或 {data: [...]} 格式。
     */
    @SuppressWarnings("unchecked")
    private DataSourceTestResponse parseApiResponse(Object json, int rowLimit, String recordsPath) {
        List<Map<String, Object>> records;
        if (json instanceof List<?> list) {
            records = (List<Map<String, Object>>) list;
        } else if (json instanceof Map<?, ?> map) {
            // 1) 优先使用调用方显式配置的 recordsPath
            Object configured = readByPath(map, recordsPath);
            if (configured instanceof List<?> configuredList) {
                records = (List<Map<String, Object>>) configuredList;
            } else {
            // 优先提取“业务记录数组”，避免分页壳结构仅返回 code/message/data 三个字段。
            // 常见结构：
            // 1) { data: [...] }
            // 2) { data: { records: [...] } } / { data: { list: [...] } } / { data: { rows: [...] } }
            // 3) { records: [...] } / { list: [...] } / { rows: [...] }
            Object data = map.get("data");
            Object candidate = null;
            if (data instanceof List<?>) {
                candidate = data;
            } else if (data instanceof Map<?, ?> dataMap) {
                candidate = firstCollectionByKeys((Map<String, Object>) dataMap, List.of("records", "list", "rows", "items"));
            }
            if (candidate == null) {
                candidate = firstCollectionByKeys((Map<String, Object>) map, List.of("records", "list", "rows", "items", "data"));
            }

            if (candidate instanceof List<?> list) {
                records = (List<Map<String, Object>>) list;
            } else {
                records = List.of((Map<String, Object>) map);
            }
            }
        } else {
            return DataSourceTestResponse.builder().fields(List.of()).previewRows(List.of()).build();
        }

        if (records.isEmpty()) {
            return DataSourceTestResponse.builder().fields(List.of()).previewRows(List.of()).build();
        }

        List<String> fields = new ArrayList<>(records.getFirst().keySet());
        List<List<Object>> rows = new ArrayList<>();
        int limit = rowLimit > 0 ? Math.min(records.size(), rowLimit) : records.size();
        for (int i = 0; i < limit; i++) {
            Map<String, Object> rec = records.get(i);
            List<Object> row = new ArrayList<>(fields.size());
            for (String f : fields) {
                row.add(rec.get(f));
            }
            rows.add(row);
        }
        return DataSourceTestResponse.builder().fields(fields).previewRows(rows).build();
    }

    /**
     * 按候选 key 顺序提取第一个集合值（通常是 API 返回中的业务记录列表）。
     */
    @SuppressWarnings("unchecked")
    private Object firstCollectionByKeys(Map<String, Object> map, List<String> keys) {
        if (map == null || map.isEmpty()) return null;
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof List<?>) {
                return v;
            }
            // 兼容二级嵌套：如 data -> payload -> records
            if (v instanceof Map<?, ?> nested) {
                Object nv = firstCollectionByKeys((Map<String, Object>) nested, List.of("records", "list", "rows", "items"));
                if (nv instanceof List<?>) return nv;
            }
        }
        return null;
    }

    /**
     * SQL 分页查询：先查总数，再查当前页数据。
     */
    private QueryResult doExecuteSqlPaged(
            DataSourceConfig config,
            String sql,
            Map<String, Object> params,
            PageRequest pageRequest
    ) {
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        PageRequest safePage = pageRequest != null ? pageRequest : new PageRequest(1, 20);
        String resolvedSql = substituteSqlTemplate(sql, safeParams);
        validateSql(resolvedSql);

        SqlParseResult parsed = parseNamedParams(resolvedSql, safeParams);
        SqlPaginationDialect dialect = dialectResolver.resolve(config);

        long total = 0L;
        try (Connection conn = DriverManager.getConnection(config.getUrl(), config.getUsername(), config.getPassword())) {
            // 统计总数
            String countSql = dialect.toCountSql(parsed.sql());
            log.info("执行报表 SQL(count): {}", buildSqlLog(countSql, parsed.values()));
            try (PreparedStatement cps = conn.prepareStatement(countSql)) {
                cps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (int i = 0; i < parsed.values().size(); i++) {
                    cps.setObject(i + 1, parsed.values().get(i));
                }
                try (ResultSet crs = cps.executeQuery()) {
                    if (crs.next()) {
                        total = crs.getLong(1);
                    }
                }
            }

            // 查询分页数据
            String pageSql = dialect.toPageSql(parsed.sql());
            int page = Math.max(1, safePage.page());
            int pageSize = Math.max(1, safePage.pageSize());
            int offset = (page - 1) * pageSize;
            List<Object> pageSqlValues = new ArrayList<>(parsed.values());
            pageSqlValues.add(pageSize);
            pageSqlValues.add(offset);
            log.info("执行报表 SQL(page): {}", buildSqlLog(pageSql, pageSqlValues));

            try (PreparedStatement ps = conn.prepareStatement(pageSql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                int idx = 1;
                for (Object v : parsed.values()) {
                    ps.setObject(idx++, v);
                }
                ps.setObject(idx++, pageSize);
                ps.setObject(idx, offset);

                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    List<String> fields = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        fields.add(meta.getColumnLabel(i));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>(colCount);
                        for (int i = 1; i <= colCount; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    boolean hasMore = ((long) page * pageSize) < total;
                    return new QueryResult(
                            fields,
                            rows,
                            ReportQueryResponse.PaginationMeta.builder()
                                    .total(total)
                                    .currentPage(page)
                                    .pageSize(pageSize)
                                    .hasMore(hasMore)
                                    .build()
                    );
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("SQL 分页执行失败: " + e.getMessage(), e);
        }
    }

    private QueryResult parseApiQueryResult(Object json, PageRequest pageRequest, ApiPageMapping mapping) {
        Object recordsObj = readByPath(json, mapping.recordsPath());
        if (recordsObj == null) {
            if (json instanceof List<?> || json instanceof Map<?, ?>) {
                // 与旧逻辑兼容：未配置 recordsPath 时尝试默认 data
                recordsObj = json instanceof Map<?, ?> m ? m.get("data") : json;
                if (!(recordsObj instanceof List<?>)) {
                    recordsObj = json;
                }
            }
        }

        List<Map<String, Object>> records;
        if (recordsObj instanceof List<?> list) {
            records = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    m.forEach((k, v) -> row.put(String.valueOf(k), v));
                    records.add(row);
                }
            }
        } else if (recordsObj instanceof Map<?, ?> m) {
            Map<String, Object> row = new LinkedHashMap<>();
            m.forEach((k, v) -> row.put(String.valueOf(k), v));
            records = List.of(row);
        } else {
            records = List.of();
        }

        if (records.isEmpty()) {
            long total = asLong(readByPath(json, mapping.totalPath())).orElse(0L);
            int current = pageRequest != null ? pageRequest.page() : 1;
            int size = pageRequest != null ? pageRequest.pageSize() : 20;
            return new QueryResult(
                    List.of(),
                    List.of(),
                    ReportQueryResponse.PaginationMeta.builder()
                            .total(total)
                            .currentPage(current)
                            .pageSize(size)
                            .hasMore(false)
                            .build()
            );
        }

        List<String> fields = new ArrayList<>(records.getFirst().keySet());
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> rec : records) {
            List<Object> row = new ArrayList<>(fields.size());
            for (String f : fields) {
                row.add(rec.get(f));
            }
            rows.add(row);
        }

        int currentPage = asInt(readByPath(json, mapping.currentPagePath())).orElse(pageRequest != null ? pageRequest.page() : 1);
        int pageSize = asInt(readByPath(json, mapping.pageSizePath())).orElse(pageRequest != null ? pageRequest.pageSize() : rows.size());
        long total = asLong(readByPath(json, mapping.totalPath())).orElse((long) rows.size());
        boolean hasMore = asBoolean(readByPath(json, mapping.hasMorePath())).orElse(((long) currentPage * pageSize) < total);

        return new QueryResult(
                fields,
                rows,
                ReportQueryResponse.PaginationMeta.builder()
                        .total(total)
                        .currentPage(currentPage)
                        .pageSize(pageSize)
                        .hasMore(hasMore)
                        .build()
        );
    }

    private Object readByPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String part : parts) {
            if (!(cur instanceof Map<?, ?> map)) {
                return null;
            }
            cur = map.get(part);
        }
        return cur;
    }

    private Optional<Long> asLong(Object value) {
        if (value == null) return Optional.empty();
        if (value instanceof Number n) return Optional.of(n.longValue());
        try {
            return Optional.of(Long.parseLong(String.valueOf(value)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<Integer> asInt(Object value) {
        if (value == null) return Optional.empty();
        if (value instanceof Number n) return Optional.of(n.intValue());
        try {
            return Optional.of(Integer.parseInt(String.valueOf(value)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> asBoolean(Object value) {
        if (value == null) return Optional.empty();
        if (value instanceof Boolean b) return Optional.of(b);
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return Optional.of(true);
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return Optional.of(false);
        return Optional.empty();
    }

    private Map<String, Object> buildPageRequestParams(PageRequest pageRequest, ApiPageMapping mapping) {
        if (pageRequest == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        int page = Math.max(1, pageRequest.page());
        int size = Math.max(1, pageRequest.pageSize());
        int offset = (page - 1) * size;
        String pageParam = safe(mapping.pageParam(), "page");
        String sizeParam = safe(mapping.pageSizeParam(), "pageSize");
        String offsetParam = safe(mapping.offsetParam(), "offset");
        String limitParam = safe(mapping.limitParam(), "limit");
        m.put(pageParam, page);
        m.put(sizeParam, size);
        m.put(offsetParam, offset);
        m.put(limitParam, size);
        return m;
    }

    private String safe(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private String mergeJsonBody(String body, Map<String, Object> params, ObjectMapper om) throws Exception {
        Map<String, Object> root;
        if (body == null || body.isBlank()) {
            root = new LinkedHashMap<>();
        } else {
            Object parsed = om.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                root = new LinkedHashMap<>();
                map.forEach((k, v) -> root.put(String.valueOf(k), v));
            } else {
                root = new LinkedHashMap<>();
            }
        }
        root.putAll(params);
        return om.writeValueAsString(root);
    }

    private String appendQueryParams(String url, Map<String, Object> params) {
        if (params == null || params.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (e.getValue() instanceof Iterable<?> items) {
                for (Object item : items) {
                    if (!first) sb.append('&');
                    first = false;
                    appendQueryParam(sb, e.getKey(), item);
                }
                continue;
            }
            if (e.getValue().getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(e.getValue());
                for (int i = 0; i < len; i++) {
                    if (!first) sb.append('&');
                    first = false;
                    appendQueryParam(sb, e.getKey(), java.lang.reflect.Array.get(e.getValue(), i));
                }
                continue;
            }
            if (!first) sb.append('&');
            first = false;
            appendQueryParam(sb, e.getKey(), e.getValue());
        }
        if (first && sb.length() > 0) {
            return url;
        }
        return sb.toString();
    }

    private void appendQueryParam(StringBuilder sb, String key, Object value) {
        sb.append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8));
        sb.append('=');
        sb.append(java.net.URLEncoder.encode(String.valueOf(value), java.nio.charset.StandardCharsets.UTF_8));
    }

    private Map<String, Object> normalizeRuntimeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            normalized.put(key, value);
        });
        return normalized;
    }

    private String substituteSqlTemplate(String sql, Map<String, Object> params) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        String withOptionalBlocks = applyOptionalSqlBlocks(sql, safeParams);
        return substituteTemplate(withOptionalBlocks, safeParams, true);
    }

    private String substituteTemplate(String input, Map<String, Object> params) {
        return substituteTemplate(input, params, false);
    }

    private String substituteTemplate(String input, Map<String, Object> params, boolean sqlMode) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        Matcher matcher = TEMPLATE_PARAM_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = safeParams.get(name);
            String replacement = sqlMode ? stringifySqlTemplateValue(value) : stringifyTemplateValue(value);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Map<String, String> substituteTemplateMap(Map<String, String> source, Map<String, Object> params) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        source.forEach((key, value) -> resolved.put(key, substituteTemplate(value, params)));
        return resolved;
    }

    private String stringifyTemplateValue(Object value) {
        if (value == null) return "";
        if (value instanceof Iterable<?> items) {
            List<String> parts = new ArrayList<>();
            for (Object item : items) {
                parts.add(item == null ? "" : String.valueOf(item));
            }
            return String.join(",", parts);
        }
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            List<String> parts = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(value, i);
                parts.add(item == null ? "" : String.valueOf(item));
            }
            return String.join(",", parts);
        }
        return String.valueOf(value);
    }

    private String stringifySqlTemplateValue(Object value) {
        String raw = stringifyTemplateValue(value);
        return raw.replace("'", "''");
    }

    /**
     * 处理可选 SQL 片段：
     * [[ and name = '${name}' ]]
     * 当 name 缺失/空白时整段移除；存在时保留并继续做 ${name} 替换。
     */
    private String applyOptionalSqlBlocks(String sql, Map<String, Object> params) {
        Matcher matcher = OPTIONAL_SQL_BLOCK_PATTERN.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String block = matcher.group(1);
            String replacement = shouldKeepOptionalSqlBlock(block, params) ? block : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean shouldKeepOptionalSqlBlock(String block, Map<String, Object> params) {
        Matcher matcher = TEMPLATE_PARAM_PATTERN.matcher(block);
        boolean hasTemplateParam = false;
        while (matcher.find()) {
            hasTemplateParam = true;
            String name = matcher.group(1);
            if (isBlankRuntimeParam(params.get(name))) {
                return false;
            }
        }
        return hasTemplateParam;
    }

    private boolean isBlankRuntimeParam(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence seq) {
            return seq.toString().trim().isEmpty();
        }
        if (value instanceof Iterable<?> items) {
            return !items.iterator().hasNext();
        }
        if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value) == 0;
        }
        return false;
    }

    private String buildSqlLog(String sql, List<Object> values) {
        if (sql == null || sql.isEmpty() || values == null || values.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql.length() + values.size() * 8);
        int valueIndex = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '?' && valueIndex < values.size()) {
                sb.append(formatSqlLiteral(values.get(valueIndex++)));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private String formatSqlLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    /**
     * 通用查询结果（字段 + 行数据 + 分页信息）。
     */
    public record QueryResult(
            List<String> fields,
            List<List<Object>> rows,
            ReportQueryResponse.PaginationMeta pagination
    ) {}

    /**
     * 分页请求（1-based）。
     */
    public record PageRequest(int page, int pageSize) {}

    /**
     * API 分页配置映射（请求参数名 + 响应字段路径）。
     */
    public record ApiPageMapping(
            String pageParam,
            String pageSizeParam,
            String offsetParam,
            String limitParam,
            String recordsPath,
            String totalPath,
            String currentPagePath,
            String pageSizePath,
            String hasMorePath
    ) {}

    private DataSourceConfigDto toDto(DataSourceConfig e) {
        return DataSourceConfigDto.builder()
                .id(e.getId())
                .name(e.getName())
                .type(e.getType())
                .url(e.getUrl())
                .username(e.getUsername())
                .driverClass(e.getDriverClass())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private DataSourceConfig toEntity(DataSourceConfigDto dto) {
        return DataSourceConfig.builder()
                .id(dto.getId())
                .name(dto.getName())
                .type(dto.getType())
                .url(dto.getUrl())
                .username(dto.getUsername())
                .password(dto.getPassword())
                .driverClass(dto.getDriverClass())
                .build();
    }
}
