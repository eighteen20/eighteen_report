package cn.com._1820.eighteen_report.service.export.shared;

import cn.com._1820.eighteen_report.dto.ReportRenderResponse;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * gridMeta 解析器：将模板 content 中的 gridMeta 转为导出配置。
 *
 * <p>兼容策略：</p>
 * <ul>
 *   <li>模板缺失时：使用渲染结果 colCount 兜底；</li>
 *   <li>colWidths 以 A/B/AA 形式配置：转换为 0-based 列索引；</li>
 *   <li>默认值保持与历史行为一致，避免导出尺寸变化。</li>
 * </ul>
 */
@Component
public class GridMetaExportConfigParser {

    public GridMetaExportConfig parse(Map<String, Object> templateContent, ReportRenderResponse rendered) {
        GridMetaExportConfig cfg = new GridMetaExportConfig();
        cfg.colCount = Math.max(1, rendered.getColCount());
        cfg.defaultColWidthPx = 100;
        cfg.defaultRowHeightPx = 25;
        cfg.freezeHeaderRows = 0;
        cfg.colWidths = new HashMap<>();

        if (rendered.getColCount() <= 0 && rendered.getCells() != null && !rendered.getCells().isEmpty()) {
            cfg.colCount = Math.max(1, rendered.getCells().getFirst().size());
        }

        Object gmObj = templateContent != null ? templateContent.get("gridMeta") : null;
        if (!(gmObj instanceof Map<?, ?> gm)) {
            return cfg;
        }

        Object colCountObj = gm.get("colCount");
        if (colCountObj instanceof Number n && n.intValue() > 0) cfg.colCount = n.intValue();

        Object defaultColWidthObj = gm.get("defaultColWidth");
        if (defaultColWidthObj instanceof Number n && n.intValue() > 0) cfg.defaultColWidthPx = n.intValue();

        Object defaultRowHeightObj = gm.get("defaultRowHeight");
        if (defaultRowHeightObj instanceof Number n && n.intValue() > 0) cfg.defaultRowHeightPx = n.intValue();

        Object freezeObj = gm.get("freezeHeaderRows");
        if (freezeObj instanceof Number n && n.intValue() > 0) cfg.freezeHeaderRows = n.intValue();

        Object colWidthsObj = gm.get("colWidths");
        if (colWidthsObj instanceof Map<?, ?> cw) {
            for (Map.Entry<?, ?> e : cw.entrySet()) {
                if (!(e.getKey() instanceof String colId) || !(e.getValue() instanceof Number widthNum)) continue;
                int colIndex = colLettersToIndex(colId);
                if (colIndex >= 0) {
                    cfg.colWidths.put(colIndex, Math.max(20, widthNum.intValue()));
                }
            }
        }

        return cfg;
    }

    /**
     * 将 Excel 列字母（A、B、AA...）转换为 0-based 列索引。
     */
    private int colLettersToIndex(String colId) {
        String s = colId == null ? "" : colId.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return -1;
        int idx = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 'A' || ch > 'Z') return -1;
            idx = idx * 26 + (ch - 'A' + 1);
        }
        return idx - 1;
    }
}

