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
    /** 当前页（1-based） */
    private Integer page;
    /** 每页条数 */
    private Integer pageSize;
    /** 分页目标数据集 key */
    private String datasetKey;
    /** xlsx | pdf */
    private String format;
    /** 导出范围：current（当前页）| all（全部） */
    private String exportScope;
}
