package cn.com._1820.eighteen_report.service;

import cn.com._1820.eighteen_report.dto.DataSourceTestResponse;
import cn.com._1820.eighteen_report.dto.ReportQueryRequest;
import cn.com._1820.eighteen_report.dto.ReportQueryResponse;
import cn.com._1820.eighteen_report.entity.ReportTemplate;
import cn.com._1820.eighteen_report.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 报表数据查询服务：解析模板中定义的 datasets（SQL/API），逐一执行查询，汇总返回各数据集的字段名和数据行。为 ReportRenderService 提供数据支撑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportQueryService {
    /** 导出全部模式标记（由导出门面透传至 query 层） */
    private static final String EXPORT_ALL_SENTINEL = "__EXPORT_ALL_NO_PAGINATION__";

    private final ReportTemplateRepository templateRepository;
    private final DataSourceConfigService dataSourceConfigService;
    private final ObjectMapper objectMapper;

    /**
     * 根据模板 ID 执行所有数据集查询，合并运行时参数与数据集默认参数
     */
    @SuppressWarnings("unchecked")
    public ReportQueryResponse query(ReportQueryRequest request) {
        ReportTemplate t = templateRepository.findById(request.getTemplateId())
                .orElseThrow(() -> new IllegalArgumentException("报表模板不存在: " + request.getTemplateId()));

        Map<String, Object> content;
        try {
            content = objectMapper.readValue(t.getContent(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("模板 content 解析失败", e);
        }

        List<Map<String, Object>> datasets = (List<Map<String, Object>>) content.getOrDefault("datasets", List.of());
        Map<String, Object> runtimeParams = request.getParams() != null ? request.getParams() : Collections.emptyMap();

        Map<String, ReportQueryResponse.DatasetResult> results = new LinkedHashMap<>();
        boolean exportAllMode = EXPORT_ALL_SENTINEL.equals(request.getDatasetKey());
        for (Map<String, Object> ds : datasets) {
            String key = (String) ds.get("key");
            String type = (String) ds.get("type");
            ReportQueryResponse.DatasetResult result = executeDataset(ds, key, type, runtimeParams, request, exportAllMode);
            results.put(key, result);
        }

        return ReportQueryResponse.builder().datasets(results).build();
    }

    @SuppressWarnings("unchecked")
    private ReportQueryResponse.DatasetResult executeDataset(
            Map<String, Object> ds,
            String datasetKey,
            String type,
            Map<String, Object> runtimeParams,
            ReportQueryRequest request,
            boolean exportAllMode
    ) {
        PaginationConfig paginationConfig = parsePaginationConfig(ds);
        boolean shouldPaginate = shouldPaginateDataset(datasetKey, paginationConfig.enabled(), request);
        int page = request.getPage() != null && request.getPage() > 0 ? request.getPage() : 1;
        int pageSize = request.getPageSize() != null && request.getPageSize() > 0
                ? request.getPageSize()
                : paginationConfig.defaultPageSize();

        if ("SQL".equalsIgnoreCase(type)) {
            String dataSourceId = (String) ds.get("dataSourceId");
            String sql = (String) ds.get("sql");

            Map<String, Object> mergedParams = new HashMap<>(runtimeParams);
            List<Map<String, Object>> paramDefs = (List<Map<String, Object>>) ds.getOrDefault("params", List.of());
            for (Map<String, Object> p : paramDefs) {
                String name = (String) p.get("name");
                Object defaultVal = p.get("defaultValue");
                if (!mergedParams.containsKey(name)) {
                    mergedParams.put(name, defaultVal);
                }
            }

            if (shouldPaginate) {
                DataSourceConfigService.QueryResult resp = dataSourceConfigService.executeSqlPaged(
                        dataSourceId,
                        sql,
                        mergedParams,
                        new DataSourceConfigService.PageRequest(page, pageSize)
                );
                return ReportQueryResponse.DatasetResult.builder()
                        .columns(resp.fields())
                        .rows(resp.rows())
                        .pagination(resp.pagination())
                        .build();
            } else {
                DataSourceTestResponse resp = dataSourceConfigService.executeSql(dataSourceId, sql, mergedParams);
                return ReportQueryResponse.DatasetResult.builder()
                        .columns(resp.getFields())
                        .rows(resp.getPreviewRows())
                        .build();
            }
        }

        if ("API".equalsIgnoreCase(type)) {
            String apiUrl = (String) ds.get("url");
            String apiMethod = (String) ds.getOrDefault("method", "GET");

            Map<String, String> apiHeaders = (Map<String, String>) ds.get("headers");
            String apiBody = (String) ds.get("body");

            if (shouldPaginate) {
                DataSourceConfigService.QueryResult resp = dataSourceConfigService.executeApiPaged(
                        apiUrl,
                        apiMethod,
                        apiHeaders,
                        apiBody,
                        new DataSourceConfigService.PageRequest(page, pageSize),
                        new DataSourceConfigService.ApiPageMapping(
                                paginationConfig.pageParam(),
                                paginationConfig.pageSizeParam(),
                                paginationConfig.offsetParam(),
                                paginationConfig.limitParam(),
                                paginationConfig.recordsPath(),
                                paginationConfig.totalPath(),
                                paginationConfig.currentPagePath(),
                                paginationConfig.responsePageSizePath(),
                                paginationConfig.hasMorePath()
                        ),
                        runtimeParams
                );
                return ReportQueryResponse.DatasetResult.builder()
                        .columns(resp.fields())
                        .rows(resp.rows())
                        .pagination(resp.pagination())
                        .build();
            } else if (exportAllMode && paginationConfig.enabled()) {
                return queryAllApiPages(apiUrl, apiMethod, apiHeaders, apiBody, paginationConfig, pageSize, runtimeParams);
            } else {
                DataSourceTestResponse resp = dataSourceConfigService.executeApi(
                        apiUrl,
                        apiMethod,
                        apiHeaders,
                        apiBody,
                        runtimeParams
                );
                return ReportQueryResponse.DatasetResult.builder()
                        .columns(resp.getFields())
                        .rows(resp.getPreviewRows())
                        .build();
            }
        }

        throw new IllegalArgumentException("不支持的数据集类型: " + type);
    }

    /**
     * 导出全部（API 分页数据集）：按页循环拉取并聚合，直到 hasMore=false 或达到 total。
     */
    private ReportQueryResponse.DatasetResult queryAllApiPages(
            String apiUrl,
            String apiMethod,
            Map<String, String> apiHeaders,
            String apiBody,
            PaginationConfig cfg,
            int requestedPageSize,
            Map<String, Object> runtimeParams
    ) {
        int pageSize = Math.max(1, requestedPageSize > 0 ? requestedPageSize : cfg.defaultPageSize());
        int page = 1;
        int maxPages = 10_000; // 安全兜底，避免异常接口导致无限循环
        List<String> columns = List.of();
        List<List<Object>> allRows = new ArrayList<>();
        Long total = null;

        while (page <= maxPages) {
            DataSourceConfigService.QueryResult r = dataSourceConfigService.executeApiPaged(
                    apiUrl,
                    apiMethod,
                    apiHeaders,
                    apiBody,
                    new DataSourceConfigService.PageRequest(page, pageSize),
                    new DataSourceConfigService.ApiPageMapping(
                            cfg.pageParam(),
                            cfg.pageSizeParam(),
                            cfg.offsetParam(),
                            cfg.limitParam(),
                            cfg.recordsPath(),
                            cfg.totalPath(),
                            cfg.currentPagePath(),
                            cfg.responsePageSizePath(),
                            cfg.hasMorePath()
                    ),
                    runtimeParams
            );

            if (columns.isEmpty()) {
                columns = r.fields() != null ? r.fields() : List.of();
            }
            List<List<Object>> rows = r.rows() != null ? r.rows() : List.of();
            allRows.addAll(rows);

            ReportQueryResponse.PaginationMeta pm = r.pagination();
            if (pm != null && pm.getTotal() != null && pm.getTotal() >= 0) {
                total = pm.getTotal();
            }

            boolean hasMore = pm != null && Boolean.TRUE.equals(pm.getHasMore());
            if (!hasMore) {
                if (total == null || allRows.size() >= total) {
                    break;
                }
            }
            if (rows.isEmpty()) {
                break;
            }
            if (total != null && allRows.size() >= total) {
                break;
            }
            page++;
        }

        return ReportQueryResponse.DatasetResult.builder()
                .columns(columns)
                .rows(allRows)
                .pagination(ReportQueryResponse.PaginationMeta.builder()
                        .total(total != null ? total : (long) allRows.size())
                        .currentPage(1)
                        .pageSize(allRows.size())
                        .hasMore(false)
                        .build())
                .build();
    }

    private PaginationConfig parsePaginationConfig(Map<String, Object> ds) {
        Object po = ds.get("pagination");
        if (!(po instanceof Map<?, ?> p)) {
            return PaginationConfig.defaults();
        }
        boolean enabled = asBoolean(p.get("enabled"), false);
        int defaultPageSize = asInt(p.get("defaultPageSize"), 20);
        Map<String, Object> req = p.get("request") instanceof Map<?, ?> m ? castMap(m) : Map.of();
        Map<String, Object> resp = p.get("response") instanceof Map<?, ?> m ? castMap(m) : Map.of();
        return new PaginationConfig(
                enabled,
                defaultPageSize,
                asString(req.get("pageParam"), "page"),
                asString(req.get("pageSizeParam"), "pageSize"),
                asString(req.get("offsetParam"), "offset"),
                asString(req.get("limitParam"), "limit"),
                asString(resp.get("recordsPath"), ""),
                asString(resp.get("totalPath"), ""),
                asString(resp.get("currentPagePath"), ""),
                asString(resp.get("pageSizePath"), ""),
                asString(resp.get("hasMorePath"), "")
        );
    }

    private boolean shouldPaginateDataset(String datasetKey, boolean enabled, ReportQueryRequest req) {
        if (!enabled) return false;
        String target = req.getDatasetKey();
        if (target == null || target.isBlank()) return true;
        return target.equals(datasetKey);
    }

    private Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        raw.forEach((k, v) -> m.put(String.valueOf(k), v));
        return m;
    }

    private boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        if (value != null) {
            String s = value.toString().trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        }
        return fallback;
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number n) return Math.max(1, n.intValue());
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(value.toString().trim()));
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String asString(Object value, String fallback) {
        if (value == null) return fallback;
        String s = value.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private record PaginationConfig(
            boolean enabled,
            int defaultPageSize,
            String pageParam,
            String pageSizeParam,
            String offsetParam,
            String limitParam,
            String recordsPath,
            String totalPath,
            String currentPagePath,
            String responsePageSizePath,
            String hasMorePath
    ) {
        static PaginationConfig defaults() {
            return new PaginationConfig(
                    false, 20,
                    "page", "pageSize", "offset", "limit",
                    "", "", "", "", ""
            );
        }
    }
}
