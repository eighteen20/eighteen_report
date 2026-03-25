package cn.com._1820.eighteen_report.service.export.shared;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单元格媒体类型解析器：读取模板 content 中的 {@code cells}，构建 {@link MediaTypeContext}。
 *
 * <p>支持：</p>
 * <ul>
 *   <li>type=image/qrcode/barcode</li>
 *   <li>image 的 export embed 配置：{@code embedImageInCell}</li>
 * </ul>
 */
@Component
public class MediaTypeContextParser {

    /** 单元格引用解析：A1、AA12... */
    private static final Pattern CELL_REF_PATTERN = Pattern.compile("^([A-Z]+)(\\d+)$");

    public MediaTypeContext parse(Map<String, Object> templateContent) {
        MediaTypeContext ctx = new MediaTypeContext();
        ctx.explicitTypes = new HashMap<>();
        ctx.templateExists = new HashSet<>();
        ctx.columnTypeFallback = new HashMap<>();
        ctx.imageEmbedByCell = new HashMap<>();
        ctx.columnImageEmbedFallback = new HashMap<>();

        Object cellsObj = templateContent != null ? templateContent.get("cells") : null;
        if (!(cellsObj instanceof Map<?, ?> cellsMap)) {
            return ctx;
        }

        for (Map.Entry<?, ?> e : cellsMap.entrySet()) {
            if (!(e.getKey() instanceof String ref)) continue;
            Matcher m = CELL_REF_PATTERN.matcher(ref.toUpperCase(Locale.ROOT));
            if (!m.matches()) continue;
            int col = colLettersToIndex(m.group(1));
            int row = Integer.parseInt(m.group(2)) - 1;
            if (row < 0 || col < 0) continue;

            CellKey key = new CellKey(row, col);
            ctx.templateExists.add(key);

            if (!(e.getValue() instanceof Map<?, ?> cellObj)) continue;
            Object typeObj = cellObj.get("type");
            if (!(typeObj instanceof String type)) continue;
            String t = lower(type);
            if (!MediaTypes.isMediaType(t)) continue;

            ctx.explicitTypes.put(key, t);
            ctx.columnTypeFallback.putIfAbsent(col, t);

            if ("image".equals(t)) {
                boolean embed = parseEmbedImageInCellFlag(cellObj);
                ctx.imageEmbedByCell.put(key, embed);
                ctx.columnImageEmbedFallback.putIfAbsent(col, embed);
            }
        }

        return ctx;
    }

    /**
     * 读取 image 的导出锚点策略：缺省为 true（嵌入单元格）。
     */
    private boolean parseEmbedImageInCellFlag(Map<?, ?> cellObj) {
        Object embedObj = cellObj.get("embedImageInCell");
        if (embedObj instanceof Boolean b) return b;
        if (embedObj instanceof Number n) return n.intValue() != 0;
        if (embedObj instanceof String s) return !"false".equalsIgnoreCase(s.trim()) && !"0".equals(s.trim());
        return true;
    }

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

    private String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}

