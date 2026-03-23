package cn.com._1820.eighteen_report.dto;

import lombok.Data;

import java.util.Map;

/**
 * 测试数据源：执行 SQL 或调用 API，返回字段列表和预览数据。
 */
@Data
public class DataSourceTestRequest {

    /** 预配置的数据源 ID */
    private String dataSourceId;
    /** SQL 或 API */
    private String type;
    /** SQL 语句（type=SQL 时） */
    private String sql;
    /** API URL（type=API 时） */
    private String apiUrl;
    /** API 请求方法 GET/POST（type=API 时） */
    private String apiMethod;
    /** API 请求头（type=API 时） */
    private Map<String, String> apiHeaders;
    /** API 请求体（type=API, method=POST 时） */
    private String apiBody;
    /** 查询参数 */
    private Map<String, Object> params;
}
