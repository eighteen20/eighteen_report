package cn.com._1820.eighteen_report.service.export.shared;

import org.apache.poi.ss.util.CellRangeAddress;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合并单元格放置上下文（用于导出阶段）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>提供合并区域列表（供 xlsx 写入 merged regions）；</li>
 *   <li>提供合并锚点（左上角）到 region 的映射；</li>
 *   <li>提供被覆盖单元格集合，用于避免重复绘制媒体。</li>
 * </ul>
 */
public class MergePlacementContext {

    public List<CellRangeAddress> regions;
    public Map<CellKey, CellRangeAddress> mergeStartRegion;
    public Set<CellKey> coveredCells;
}

