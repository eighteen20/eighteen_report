package cn.com._1820.eighteen_report.service.export.shared;

import java.util.Map;

/**
 * gridMeta 导出配置（用于 xlsx/pdf 等多格式共享）。
 *
 * <p>来源：模板 content JSON 的 {@code gridMeta} 节点 + 渲染结果兜底。</p>
 */
public class GridMetaExportConfig {

    /** 总列数（至少 1） */
    public int colCount;
    /** 默认列宽（px） */
    public int defaultColWidthPx;
    /** 默认行高（px） */
    public int defaultRowHeightPx;
    /** 冻结表头行数 */
    public int freezeHeaderRows;
    /** 列宽（key=列索引 0-based，value=宽度 px） */
    public Map<Integer, Integer> colWidths;
}

