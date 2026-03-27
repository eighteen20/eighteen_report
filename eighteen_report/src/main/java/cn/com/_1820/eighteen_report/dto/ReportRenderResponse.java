package cn.com._1820.eighteen_report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 渲染结果：二维单元格矩阵（已变量替换、数据行展开）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportRenderResponse {

    /** 渲染后的二维单元格值矩阵（变量已替换、数据行已展开） */
    private List<List<String>> cells;
    /** 与 cells 对应的样式矩阵（每个元素为 CSS 属性键值对） */
    private List<List<Map<String, String>>> styles;
    /** 调整后的合并区域列表（行索引已映射到展开后的输出行） */
    private List<MergeRegion> merges;
    /** 输出总行数 */
    private int rowCount;
    /** 输出总列数 */
    private int colCount;
    /**
     * 与 {@link #cells} 每一输出行对应的行高（像素，与设计器 gridMeta 一致）。
     * 数据模板行展开为多行时，每一行复用该模板行配置的高度。
     */
    private List<Integer> rowHeightsPx;
    /**
     * 动态水印文本（可选）
     *
     * 前端预览页水印优先级：本字段优先于模板 content.gridMeta.watermark（固定文案）。
     * 来源说明：
     * - 若模板配置了 {@code gridMeta.watermarkCallbackUrl}：由本服务在渲染时服务端请求该地址解析得到（浏览器无法伪造）；
     * - 若未配置回调：可由渲染请求 {@code params.watermark} 传入（兼容旧版；安全性依赖调用链）。
     */
    private String watermark;
    /**
     * 按数据集返回分页元信息（key=datasetKey），供预览页页码展示与翻页控制使用。
     */
    private Map<String, ReportQueryResponse.PaginationMeta> paginationByDataset;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MergeRegion {
        /** 合并起始行索引（0-based） */
        private int row;
        /** 合并起始列索引（0-based） */
        private int col;
        /** 合并行数 */
        private int rowSpan;
        /** 合并列数 */
        private int colSpan;
    }
}
