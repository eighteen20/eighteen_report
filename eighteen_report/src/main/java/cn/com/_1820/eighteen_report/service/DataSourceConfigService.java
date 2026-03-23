package cn.com._1820.eighteen_report.service;

import cn.com._1820.eighteen_report.dto.DataSourceConfigDto;
import cn.com._1820.eighteen_report.dto.DataSourceTestRequest;
import cn.com._1820.eighteen_report.dto.DataSourceTestResponse;
import cn.com._1820.eighteen_report.entity.DataSourceConfig;
import cn.com._1820.eighteen_report.repository.DataSourceConfigRepository;
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

/**
 * 数据源配置服务：管理 JDBC/API 类型的数据源，提供 SQL 执行和 API 调用能力。支持命名参数(:paramName)的 SQL 查询和 RESTful API 数据获取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceConfigService {

    /** 测试查询时的最大预览行数 */
    private static final int PREVIEW_ROW_LIMIT = 20;
    /** SQL/API 查询超时时间（秒） */
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private final DataSourceConfigRepository repository;

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
        validateSql(sql);
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();

        SqlParseResult parsed = parseNamedParams(sql, safeParams);

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
                                              Map<String, String> apiHeaders, String apiBody) {
        return doExecuteApi(apiUrl, apiMethod, apiHeaders, apiBody, 0);
    }

    private DataSourceTestResponse testApi(DataSourceTestRequest request) {
        return doExecuteApi(
                request.getApiUrl(),
                request.getApiMethod(),
                request.getApiHeaders(),
                request.getApiBody(),
                PREVIEW_ROW_LIMIT
        );
    }

    private DataSourceTestResponse doExecuteApi(String url, String method,
                                                 Map<String, String> headers, String body,
                                                 int rowLimit) {
        try {
            String httpMethod = method != null ? method.toUpperCase(Locale.ROOT) : "GET";

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(QUERY_TIMEOUT_SECONDS));

            if (headers != null) {
                headers.forEach(builder::header);
            }

            if ("POST".equals(httpMethod) && body != null) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body));
                builder.header("Content-Type", "application/json");
            } else {
                builder.GET();
            }

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            ObjectMapper om = new ObjectMapper();
            Object json = om.readValue(response.body(), Object.class);
            return parseApiResponse(json, rowLimit);
        } catch (Exception e) {
            throw new RuntimeException("API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 API 返回的 JSON：支持数组或 {data: [...]} 格式。
     */
    @SuppressWarnings("unchecked")
    private DataSourceTestResponse parseApiResponse(Object json, int rowLimit) {
        List<Map<String, Object>> records;
        if (json instanceof List<?> list) {
            records = (List<Map<String, Object>>) list;
        } else if (json instanceof Map<?, ?> map) {
            Object data = map.get("data");
            if (data instanceof List<?> list) {
                records = (List<Map<String, Object>>) list;
            } else {
                records = List.of((Map<String, Object>) map);
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
