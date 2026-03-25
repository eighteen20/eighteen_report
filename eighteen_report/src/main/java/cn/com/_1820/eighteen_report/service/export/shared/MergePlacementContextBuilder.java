package cn.com._1820.eighteen_report.service.export.shared;

import cn.com._1820.eighteen_report.dto.ReportRenderResponse;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * 合并单元格上下文构建器：将渲染结果 merges 转成导出使用的索引结构。
 */
@Component
public class MergePlacementContextBuilder {

    public MergePlacementContext build(List<ReportRenderResponse.MergeRegion> merges) {
        MergePlacementContext ctx = new MergePlacementContext();
        ctx.regions = new ArrayList<>();
        ctx.mergeStartRegion = new HashMap<>();
        ctx.coveredCells = new HashSet<>();
        if (merges == null) return ctx;

        for (ReportRenderResponse.MergeRegion m : merges) {
            int r1 = m.getRow();
            int c1 = m.getCol();
            int r2 = r1 + Math.max(1, m.getRowSpan()) - 1;
            int c2 = c1 + Math.max(1, m.getColSpan()) - 1;
            if (r1 < 0 || c1 < 0 || r2 < r1 || c2 < c1) continue;

            CellRangeAddress region = new CellRangeAddress(r1, r2, c1, c2);
            ctx.regions.add(region);
            ctx.mergeStartRegion.put(new CellKey(r1, c1), region);

            for (int r = r1; r <= r2; r++) {
                for (int c = c1; c <= c2; c++) {
                    if (r == r1 && c == c1) continue;
                    ctx.coveredCells.add(new CellKey(r, c));
                }
            }
        }

        return ctx;
    }
}

