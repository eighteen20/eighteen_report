package cn.com._1820.eighteen_report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 数据查询响应：key 为 dataset key，value 为该 dataset 的 columns + rows。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportQueryResponse {

    /** key 为数据集标识，value 为该数据集的查询结果 */
    private Map<String, DatasetResult> datasets;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DatasetResult {
        /** 字段名列表 */
        private List<String> columns;
        /** 数据行列表（每行为与 columns 对应的值列表） */
        private List<List<Object>> rows;
        /** 分页元信息（未分页时可为空） */
        private PaginationMeta pagination;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PaginationMeta {
        /** 总条数（可选） */
        private Long total;
        /** 当前页（1-based） */
        private Integer currentPage;
        /** 每页条数 */
        private Integer pageSize;
        /** 是否还有下一页（可选） */
        private Boolean hasMore;
    }
}
