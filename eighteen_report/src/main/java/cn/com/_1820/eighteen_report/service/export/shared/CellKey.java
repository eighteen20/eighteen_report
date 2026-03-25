package cn.com._1820.eighteen_report.service.export.shared;

/**
 * 单元格坐标键（0-based）。
 *
 * <p>说明：渲染结果与导出内部均以 0-based 行列索引对齐。</p>
 *
 * @param row 行号（0-based）
 * @param col 列号（0-based）
 */
public record CellKey(int row, int col) {}

