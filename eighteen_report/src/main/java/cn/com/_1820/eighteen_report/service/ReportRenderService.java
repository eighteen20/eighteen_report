package cn.com._1820.eighteen_report.service;

import cn.com._1820.eighteen_report.dto.ReportQueryRequest;
import cn.com._1820.eighteen_report.dto.ReportQueryResponse;
import cn.com._1820.eighteen_report.dto.ReportRenderResponse;
import cn.com._1820.eighteen_report.entity.ReportTemplate;
import cn.com._1820.eighteen_report.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报表渲染核心服务：解析模板单元格定义，执行数据集查询，进行 ${dsKey.field} 变量替换，将包含变量的行按数据集行数展开为多行，
 * 同时传播单元格样式、为每个输出行生成与模板行一致的行高（rowHeightsPx），并调整合并区域的行索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportRenderService {

    /** 变量表达式正则：匹配 ${datasetKey.fieldName} 格式 */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)\\.(\\w+)}");

    private final ReportTemplateRepository templateRepository;
    private final ReportQueryService queryService;
    private final ObjectMapper objectMapper;
    private final WatermarkCallbackService watermarkCallbackService;

    /**
     * 渲染报表：解析模板 → 查询数据 → 变量替换 → 数据行展开 → 样式传播 → 合并区域调整，返回最终的二维单元格矩阵、样式矩阵和合并区域
     */
    public ReportRenderResponse render(String templateId, Map<String, Object> params) {
        return render(templateId, params, null, null, null);
    }

    /**
     * 渲染报表（支持分页上下文）：当 page/pageSize 非空时，仅对开启分页的数据集查询对应页数据。
     *
     * @param templateId 模板 ID
     * @param params 运行时参数
     * @param page 当前页（1-based，可空）
     * @param pageSize 每页条数（可空）
     * @param datasetKey 分页目标数据集 key（可空）
     */
    @SuppressWarnings("unchecked")
    public ReportRenderResponse render(
            String templateId,
            Map<String, Object> params,
            Integer page,
            Integer pageSize,
            String datasetKey
    ) {
        ReportTemplate t = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("报表模板不存在: " + templateId));

        Map<String, Object> content;
        try {
            content = objectMapper.readValue(t.getContent(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("模板 content 解析失败", e);
        }

        Map<String, Object> gridMeta = (Map<String, Object>) content.getOrDefault("gridMeta",
                Map.of("rowCount", 50, "colCount", 10));
        int rowCount = ((Number) gridMeta.getOrDefault("rowCount", 50)).intValue();
        int colCount = ((Number) gridMeta.getOrDefault("colCount", 10)).intValue();
        Map<Integer, Integer> templateRowHeightsPx = parseTemplateRowHeightsFromGridMeta(gridMeta);
        int defaultRowHeightPx = parseDefaultRowHeightPx(gridMeta);

        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) content.getOrDefault("cells", Map.of());

        // Execute all datasets
        ReportQueryRequest qr = new ReportQueryRequest();
        qr.setTemplateId(templateId);
        qr.setParams(params != null ? params : Collections.emptyMap());
        qr.setPage(page);
        qr.setPageSize(pageSize);
        qr.setDatasetKey(datasetKey);
        ReportQueryResponse queryResp = queryService.query(qr);
        Map<String, ReportQueryResponse.DatasetResult> dsResults = queryResp.getDatasets() != null
                ? queryResp.getDatasets() : Collections.emptyMap();
        Map<String, ReportQueryResponse.PaginationMeta> paginationByDataset = new LinkedHashMap<>();
        for (Map.Entry<String, ReportQueryResponse.DatasetResult> e : dsResults.entrySet()) {
            if (e.getValue() != null && e.getValue().getPagination() != null) {
                paginationByDataset.put(e.getKey(), e.getValue().getPagination());
            }
        }

        // Build template rows (sparse → dense) with styles
        String[][] templateGrid = new String[rowCount][colCount];
        Map<String, String>[][] templateStyles = new Map[rowCount][colCount];
        boolean[] isDataRow = new boolean[rowCount];
        String[] dataRowDsKey = new String[rowCount];

        for (Map.Entry<String, Map<String, Object>> entry : cells.entrySet()) {
            String cellRef = entry.getKey();
            Map<String, Object> cellData = entry.getValue();
            int[] rc = parseCellRef(cellRef);
            if (rc == null || rc[0] >= rowCount || rc[1] >= colCount) continue;

            String value = cellData.get("value") != null ? cellData.get("value").toString() : "";
            templateGrid[rc[0]][rc[1]] = value;

            Object styleObj = cellData.get("style");
            if (styleObj instanceof Map<?, ?> rawStyle && !rawStyle.isEmpty()) {
                Map<String, String> styleMap = new LinkedHashMap<>();
                rawStyle.forEach((k, v) -> { if (k != null && v != null) styleMap.put(k.toString(), v.toString()); });
                templateStyles[rc[0]][rc[1]] = styleMap;
            }

            if (hasVariable(value)) {
                isDataRow[rc[0]] = true;
                Matcher m = VAR_PATTERN.matcher(value);
                if (m.find()) {
                    dataRowDsKey[rc[0]] = m.group(1);
                }
            }
        }

        List<Map<String, Object>> templateMerges =
                (List<Map<String, Object>>) content.getOrDefault("merges", List.of());

        // Build output rows with row-index tracking for merge adjustment
        List<List<String>> outputRows = new ArrayList<>();
        List<List<Map<String, String>>> outputStyles = new ArrayList<>();
        /** 与 outputRows 一一对应：该输出行对应的模板行在设计器中配置的行高（px） */
        List<Integer> outputRowHeightsPx = new ArrayList<>();
        int[] outRowStart = new int[rowCount];
        int[] outRowExpand = new int[rowCount];
        Arrays.fill(outRowStart, -1);

        for (int r = 0; r < rowCount; r++) {
            int before = outputRows.size();
            int rowHeightThisTemplate = templateRowHeightsPx.getOrDefault(r, defaultRowHeightPx);

            if (isDataRow[r]) {
                String dsKey = dataRowDsKey[r];
                ReportQueryResponse.DatasetResult ds = dsResults.get(dsKey);
                if (ds != null && ds.getRows() != null) {
                    List<String> columns = ds.getColumns();
                    int totalDataRows = ds.getRows().size();
                    int dataIdx = 0;
                    for (List<Object> dataRow : ds.getRows()) {
                        Map<String, Object> rowMap = new LinkedHashMap<>();
                        for (int ci = 0; ci < columns.size() && ci < dataRow.size(); ci++) {
                            rowMap.put(columns.get(ci), dataRow.get(ci));
                        }
                        boolean isLastDataRow = (dataIdx == totalDataRows - 1);
                        List<String> outRow = new ArrayList<>(colCount);
                        List<Map<String, String>> outStyle = new ArrayList<>(colCount);
                        for (int c = 0; c < colCount; c++) {
                            outRow.add(resolveVariables(templateGrid[r][c], dsKey, rowMap));
                            outStyle.add(deduplicateBorders(templateStyles[r][c], isLastDataRow, c == colCount - 1));
                        }
                        outputRows.add(outRow);
                        outputStyles.add(outStyle);
                        outputRowHeightsPx.add(rowHeightThisTemplate);
                        dataIdx++;
                    }
                }
            } else {
                // 静态行（非数据集展开行）：原先在「尚未输出任何行且本行全空」时整行跳过，
                // 会把模板顶部留白行（页边插入的无内容行）从渲染结果中丢掉，预览与设计器不一致。
                // 改为始终输出静态行；尾部连续空行仍由后面「Trim trailing empty rows」收敛。
                List<String> outRow = new ArrayList<>(colCount);
                List<Map<String, String>> outStyle = new ArrayList<>(colCount);
                for (int c = 0; c < colCount; c++) {
                    outRow.add(templateGrid[r][c] != null ? templateGrid[r][c] : "");
                    outStyle.add(templateStyles[r][c]);
                }
                outputRows.add(outRow);
                outputStyles.add(outStyle);
                outputRowHeightsPx.add(rowHeightThisTemplate);
            }

            int added = outputRows.size() - before;
            if (added > 0) {
                outRowStart[r] = before;
                outRowExpand[r] = added;
            }
        }

        // Trim trailing empty rows
        while (!outputRows.isEmpty()) {
            List<String> last = outputRows.getLast();
            if (last.stream().allMatch(s -> s == null || s.isEmpty())) {
                outputRows.removeLast();
                outputStyles.removeLast();
                if (!outputRowHeightsPx.isEmpty()) {
                    outputRowHeightsPx.removeLast();
                }
            } else {
                break;
            }
        }

        // Adjust merge regions to output row indices
        int finalSize = outputRows.size();
        List<ReportRenderResponse.MergeRegion> outputMerges = new ArrayList<>();
        for (Map<String, Object> tm : templateMerges) {
            int mRow = ((Number) tm.get("row")).intValue();
            int mCol = ((Number) tm.get("col")).intValue();
            int mRowSpan = ((Number) tm.getOrDefault("rowSpan", 1)).intValue();
            int mColSpan = ((Number) tm.getOrDefault("colSpan", 1)).intValue();

            if (mRowSpan == 1) {
                if (mRow < rowCount && outRowStart[mRow] >= 0) {
                    for (int i = 0; i < outRowExpand[mRow]; i++) {
                        int idx = outRowStart[mRow] + i;
                        if (idx < finalSize) {
                            outputMerges.add(new ReportRenderResponse.MergeRegion(idx, mCol, 1, mColSpan));
                        }
                    }
                }
            } else {
                if (mRow < rowCount && outRowStart[mRow] >= 0) {
                    int first = outRowStart[mRow];
                    int total = 0;
                    for (int rr = mRow; rr < mRow + mRowSpan && rr < rowCount; rr++) {
                        if (outRowStart[rr] >= 0) total += outRowExpand[rr];
                    }
                    if (total > 0 && first + total <= finalSize) {
                        outputMerges.add(new ReportRenderResponse.MergeRegion(first, mCol, total, mColSpan));
                    }
                }
            }
        }

        int finalColCount = outputRows.isEmpty() ? colCount : outputRows.getFirst().size();
        // 动态水印：
        // 1）若 gridMeta.watermarkCallbackUrl 已配置：由服务端请求业务地址，结果写入 watermark（浏览器不可伪造）；
        //    此种情况下忽略 params.watermark，防止 URI/请求体篡改。
        // 2）否则兼容旧行为：允许 params.watermark 传入（仅限未配置回调时）。
        String dynamicWatermark = null;
        String watermarkCallbackUrl = readWatermarkCallbackUrl(gridMeta);
        if (watermarkCallbackUrl != null && !watermarkCallbackUrl.isBlank()) {
            dynamicWatermark = watermarkCallbackService
                    .fetchWatermark(watermarkCallbackUrl.trim(), templateId, params != null ? params : Collections.emptyMap())
                    .orElse(null);
        } else if (params != null) {
            Object wm = params.get("watermark");
            if (wm != null) {
                String s = wm.toString().trim();
                if (!s.isEmpty()) {
                    dynamicWatermark = s;
                }
            }
        }
        return ReportRenderResponse.builder()
                .cells(outputRows)
                .styles(outputStyles)
                .merges(outputMerges)
                .rowCount(outputRows.size())
                .colCount(finalColCount)
                .rowHeightsPx(outputRowHeightsPx)
                .watermark(dynamicWatermark)
                .paginationByDataset(paginationByDataset)
                .build();
    }

    /**
     * 解析 gridMeta.rowHeights：键可为字符串或数字（JSON 兼容），值为像素高度。
     */
    private Map<Integer, Integer> parseTemplateRowHeightsFromGridMeta(Map<String, Object> gridMeta) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        if (gridMeta == null) {
            return map;
        }
        Object rhObj = gridMeta.get("rowHeights");
        if (!(rhObj instanceof Map<?, ?> rh)) {
            return map;
        }
        for (Map.Entry<?, ?> e : rh.entrySet()) {
            if (!(e.getValue() instanceof Number heightNum)) {
                continue;
            }
            int rowIndex = -1;
            Object k = e.getKey();
            if (k instanceof Number kn) {
                rowIndex = kn.intValue();
            } else if (k != null) {
                try {
                    rowIndex = Integer.parseInt(k.toString().trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
            if (rowIndex >= 0) {
                map.put(rowIndex, Math.max(1, heightNum.intValue()));
            }
        }
        return map;
    }

    private int parseDefaultRowHeightPx(Map<String, Object> gridMeta) {
        if (gridMeta == null) {
            return 25;
        }
        Object o = gridMeta.get("defaultRowHeight");
        if (o instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        return 25;
    }

    /**
     * 数据行展开时去除重复的纵向边框：非末行移除 borderBottom，避免与下一行的 borderTop 形成双线。
     * borderRight 保持原样（设计端已确保只有最右列才有 borderRight，不会横向重复）。
     */
    private Map<String, String> deduplicateBorders(Map<String, String> src, boolean lastRow, boolean lastCol) {
        if (src == null || src.isEmpty()) return src;
        if (lastRow || !src.containsKey("borderBottom")) return src;

        Map<String, String> copy = new LinkedHashMap<>(src);
        copy.remove("borderBottom");
        return copy;
    }

    private boolean hasVariable(String value) {
        return value != null && VAR_PATTERN.matcher(value).find();
    }

    private String resolveVariables(String template, String dsKey, Map<String, Object> rowData) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varDs = m.group(1);
            String varField = m.group(2);
            if (varDs.equals(dsKey) && rowData.containsKey(varField)) {
                Object val = rowData.get(varField);
                m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val.toString() : ""));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Parse cell reference like "A1" → [row=0, col=0], "B3" → [row=2, col=1].
     */
    /**
     * 读取模板中配置的水印回调基地址（业务系统提供，由本服务服务端拉取水印文案）。
     */
    private String readWatermarkCallbackUrl(Map<String, Object> gridMeta) {
        if (gridMeta == null) {
            return null;
        }
        Object u = gridMeta.get("watermarkCallbackUrl");
        return u != null ? u.toString() : null;
    }

    private int[] parseCellRef(String ref) {
        if (ref == null || ref.isEmpty()) return null;
        int i = 0;
        int col = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        col--;
        if (i >= ref.length()) return null;
        try {
            int row = Integer.parseInt(ref.substring(i)) - 1;
            return new int[]{row, col};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
