package cn.com._1820.eighteen_report.service.export;

import cn.com._1820.eighteen_report.config.WatermarkCallbackProperties;
import cn.com._1820.eighteen_report.dto.ReportExportRequest;
import cn.com._1820.eighteen_report.dto.ReportRenderResponse;
import cn.com._1820.eighteen_report.repository.ReportTemplateRepository;
import cn.com._1820.eighteen_report.service.ReportRenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * 导出门面：统一导出入口（渲染 + 读取模板 + 路由到对应格式 exporter）。
 *
 * <p>为什么需要门面：</p>
 * <ul>
 *   <li>对外（Controller）提供稳定入口，内部可自由拆分 exporter 与 shared 组件；</li>
 *   <li>统一处理渲染、模板读取、format 归一化、异常包装与日志。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportFacade {

    private final ReportRenderService renderService;
    private final ReportTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;
    private final WatermarkCallbackProperties callbackProperties;
    private final ExporterRegistry registry;

    /**
     * 导出报表：先渲染，再按 format 选择 exporter 输出字节流。
     *
     * @param request 导出请求
     * @return 导出结果
     */
    public ExportResult export(ReportExportRequest request) {
        String templateId = request.getTemplateId();
        Map<String, Object> queryParams = request.getQueryParams() != null ? request.getQueryParams() : Collections.emptyMap();

        boolean exportAll = "all".equalsIgnoreCase(request.getExportScope());
        Integer page = exportAll ? null : request.getPage();
        Integer pageSize = exportAll ? null : request.getPageSize();
        // 导出全部时显式传入一个不匹配任何数据集 key 的标记，
        // 让查询层 shouldPaginateDataset(...) 返回 false，从而关闭分页。
        String datasetKey = exportAll ? "__EXPORT_ALL_NO_PAGINATION__" : request.getDatasetKey();
        ReportRenderResponse rendered = renderService.render(templateId, queryParams, page, pageSize, datasetKey);

        Map<String, Object> templateContent = readTemplateContent(templateId);
        String format = request.getFormat() != null ? request.getFormat().toLowerCase(Locale.ROOT) : "xlsx";

        ReportExporter exporter = registry.get(format);
        ExportContext ctx = new ExportContext(templateId, queryParams, rendered, templateContent, callbackProperties);

        return exporter.export(ctx);
    }

    /**
     * 读取模板 content JSON，解析为 Map。
     *
     * <p>异常策略：读取失败时不打断导出，按空配置继续（与历史行为一致）。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readTemplateContent(String templateId) {
        try {
            var template = templateRepository.findById(templateId)
                    .orElseThrow(() -> new IllegalArgumentException("报表模板不存在: " + templateId));
            if (template.getContent() == null || template.getContent().isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(template.getContent(), Map.class);
        } catch (Exception e) {
            log.warn("读取模板内容失败，导出按默认配置继续: templateId={}, error={}", templateId, e.getMessage());
            return Map.of();
        }
    }
}
