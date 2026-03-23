package cn.com._1820.eighteen_report.dto;

import lombok.Data;

import java.util.Map;

/**
 * 数据查询请求：按报表模板 ID 执行其所有 datasets。
 */
@Data
public class ReportQueryRequest {

    private String templateId;
    /** 运行时参数，传递给各 dataset 中的 :paramName */
    private Map<String, Object> params;
}
