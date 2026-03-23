package cn.com._1820.eighteen_report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 数据源测试结果：字段名列表 + 预览行数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSourceTestResponse {

    private List<String> fields;
    private List<List<Object>> previewRows;
}
