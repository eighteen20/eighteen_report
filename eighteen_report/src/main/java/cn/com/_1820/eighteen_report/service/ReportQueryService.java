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
        for (Map<String, Object> ds : datasets) {
            String key = (String) ds.get("key");
            String type = (String) ds.get("type");
            ReportQueryResponse.DatasetResult result = executeDataset(ds, type, runtimeParams);
            results.put(key, result);
        }

        return ReportQueryResponse.builder().datasets(results).build();
    }

    @SuppressWarnings("unchecked")
    private ReportQueryResponse.DatasetResult executeDataset(Map<String, Object> ds, String type,
                                                              Map<String, Object> runtimeParams) {
        if ("SQL".equalsIgnoreCase(type)) {
            String dataSourceId = (String) ds.get("dataSourceId");
            String sql = (String) ds.get("sql");

            Map<String, Object> mergedParams = new HashMap<>();
            List<Map<String, Object>> paramDefs = (List<Map<String, Object>>) ds.getOrDefault("params", List.of());
            for (Map<String, Object> p : paramDefs) {
                String name = (String) p.get("name");
                Object defaultVal = p.get("defaultValue");
                mergedParams.put(name, runtimeParams.getOrDefault(name, defaultVal));
            }

            DataSourceTestResponse resp = dataSourceConfigService.executeSql(dataSourceId, sql, mergedParams);
            return ReportQueryResponse.DatasetResult.builder()
                    .columns(resp.getFields())
                    .rows(resp.getPreviewRows())
                    .build();
        }

        if ("API".equalsIgnoreCase(type)) {
            String apiUrl = (String) ds.get("url");
            String apiMethod = (String) ds.getOrDefault("method", "GET");

            @SuppressWarnings("unchecked")
            Map<String, String> apiHeaders = (Map<String, String>) ds.get("headers");
            String apiBody = (String) ds.get("body");

            DataSourceTestResponse resp = dataSourceConfigService.executeApi(apiUrl, apiMethod, apiHeaders, apiBody);
            return ReportQueryResponse.DatasetResult.builder()
                    .columns(resp.getFields())
                    .rows(resp.getPreviewRows())
                    .build();
        }

        throw new IllegalArgumentException("不支持的数据集类型: " + type);
    }
}
