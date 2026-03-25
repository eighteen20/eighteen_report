package cn.com._1820.eighteen_report.service.export;

import cn.com._1820.eighteen_report.config.WatermarkCallbackProperties;
import cn.com._1820.eighteen_report.dto.ReportRenderResponse;

import java.util.Map;

/**
 * 导出上下文：封装一次导出链路中可复用的输入与依赖。
 *
 * <p>设计意图：</p>
 * <ul>
 *   <li>避免各格式 Exporter 重复读取模板、重复构建合并/媒体上下文；</li>
 *   <li>统一承载渲染结果（cells/styles/merges/rowHeightsPx/watermark 等）与模板内容（content JSON 解析后的 Map）；</li>
 *   <li>为后续扩展 csv/json/image 等导出格式提供稳定输入结构。</li>
 * </ul>
 */
public final class ExportContext {

    /** 模板 ID */
    private final String templateId;
    /** 导出时使用的查询参数（用于需要二次调用或日志审计等场景） */
    private final Map<String, Object> queryParams;
    /** 渲染结果（渲染服务输出） */
    private final ReportRenderResponse rendered;
    /** 模板 content JSON 解析后的 Map（可能为空 Map） */
    private final Map<String, Object> templateContent;
    /** SSRF/白名单等安全配置（媒体下载等场景复用） */
    private final WatermarkCallbackProperties callbackProperties;

    public ExportContext(
            String templateId,
            Map<String, Object> queryParams,
            ReportRenderResponse rendered,
            Map<String, Object> templateContent,
            WatermarkCallbackProperties callbackProperties
    ) {
        this.templateId = templateId;
        this.queryParams = queryParams;
        this.rendered = rendered;
        this.templateContent = templateContent;
        this.callbackProperties = callbackProperties;
    }

    public String templateId() {
        return templateId;
    }

    public Map<String, Object> queryParams() {
        return queryParams;
    }

    public ReportRenderResponse rendered() {
        return rendered;
    }

    public Map<String, Object> templateContent() {
        return templateContent;
    }

    public WatermarkCallbackProperties callbackProperties() {
        return callbackProperties;
    }
}

