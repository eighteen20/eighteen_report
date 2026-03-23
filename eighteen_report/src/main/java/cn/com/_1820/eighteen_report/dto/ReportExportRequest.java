package cn.com._1820.eighteen_report.dto;

import lombok.Data;

import java.util.Map;

/**
 * 导出请求：模板 ID + 查询参数 + 格式。
 */
@Data
public class ReportExportRequest {

    private String templateId;
    private Map<String, Object> queryParams;
    /** xlsx | pdf */
    private String format;
}
