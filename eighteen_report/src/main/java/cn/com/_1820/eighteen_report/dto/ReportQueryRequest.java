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
    /** 当前页（1-based） */
    private Integer page;
    /** 每页条数 */
    private Integer pageSize;
    /** 分页目标数据集 key（为空时按数据集配置自行判断） */
    private String datasetKey;
}
