package cn.com._1820.eighteen_report.service.export.excel;

import cn.com._1820.eighteen_report.config.WatermarkCallbackProperties;
import cn.com._1820.eighteen_report.dto.ReportRenderResponse;
import cn.com._1820.eighteen_report.service.export.ExportContext;
import cn.com._1820.eighteen_report.service.export.ExportResult;
import cn.com._1820.eighteen_report.service.export.ReportExporter;
import cn.com._1820.eighteen_report.service.export.shared.CellKey;
import cn.com._1820.eighteen_report.service.export.shared.GridMetaExportConfig;
import cn.com._1820.eighteen_report.service.export.shared.GridMetaExportConfigParser;
import cn.com._1820.eighteen_report.service.export.shared.MediaBinary;
import cn.com._1820.eighteen_report.service.export.shared.MediaBinaryResolver;
import cn.com._1820.eighteen_report.service.export.shared.MediaTypeContext;
import cn.com._1820.eighteen_report.service.export.shared.MediaTypeContextParser;
import cn.com._1820.eighteen_report.service.export.shared.MediaTypes;
import cn.com._1820.eighteen_report.service.export.shared.MergePlacementContext;
import cn.com._1820.eighteen_report.service.export.shared.MergePlacementContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Excel 导出器（xlsx）：写入文本+样式后，再按类型嵌入媒体图片。
 *
 * <p>实现说明：</p>
 * <ul>
 *   <li>复用渲染服务输出的 cells/styles/merges/rowHeightsPx；</li>
 *   <li>模板 content 仅用于读取 gridMeta 与单元格媒体类型（image/qrcode/barcode）及列兜底；</li>
 *   <li>媒体容错：下载/生成失败时降级为文本，不中断导出。</li>
 * </ul>
 */
@Slf4j
@Component
public class ExcelReportExporter implements ReportExporter {
    private final GridMetaExportConfigParser gridMetaParser;
    private final MediaTypeContextParser mediaTypeParser;
    private final MergePlacementContextBuilder mergeCtxBuilder;
    private final MediaBinaryResolver mediaBinaryResolver;

    /**
     * 媒体图在单元格/合并区内所占比例（相对宽与高），略小于 1 可留出边距，避免贴边遮住边框线。
     */
    private static final double MEDIA_ANCHOR_FILL_FRACTION = 0.75d;

    public ExcelReportExporter(
            GridMetaExportConfigParser gridMetaParser,
            MediaTypeContextParser mediaTypeParser,
            MergePlacementContextBuilder mergeCtxBuilder,
            MediaBinaryResolver mediaBinaryResolver
    ) {
        this.gridMetaParser = gridMetaParser;
        this.mediaTypeParser = mediaTypeParser;
        this.mergeCtxBuilder = mergeCtxBuilder;
        this.mediaBinaryResolver = mediaBinaryResolver;
    }

    @Override
    public boolean supports(String format) {
        return "xlsx".equals(format);
    }

    @Override
    public ExportResult export(ExportContext ctx) {
        return exportExcel(ctx.templateContent(), ctx.rendered(), ctx.callbackProperties());
    }

    /**
     * 导出 Excel：写入文本+样式后，再按类型嵌入媒体图片。
     *
     * @param templateContent 模板 content JSON Map
     * @param rendered        渲染结果
     * @param callbackProps   SSRF/超时等安全配置（用于远程图片下载）
     */
    private ExportResult exportExcel(
            Map<String, Object> templateContent,
            ReportRenderResponse rendered,
            WatermarkCallbackProperties callbackProps
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("报表");
            CreationHelper creationHelper = workbook.getCreationHelper();
            Drawing<?> drawing = sheet.createDrawingPatriarch();

            GridMetaExportConfig cfg = gridMetaParser.parse(templateContent, rendered);
            MediaTypeContext mediaTypeCtx = mediaTypeParser.parse(templateContent);
            applySheetMeta(sheet, cfg);

            // 合并区域先建索引，供“媒体绘图位置判定”使用
            MergePlacementContext mergeCtx = mergeCtxBuilder.build(rendered.getMerges());

            // 样式缓存：相同样式复用
            Map<String, CellStyle> styleCache = new HashMap<>();
            // 媒体缓存：同一个媒体源只生成/下载一次，复用 workbook picture index
            Map<String, Optional<MediaBinary>> mediaBinaryCache = new HashMap<>();
            Map<String, Integer> pictureIndexCache = new HashMap<>();

            List<List<String>> cells = rendered.getCells() != null ? rendered.getCells() : List.of();
            List<List<Map<String, String>>> styles = rendered.getStyles() != null ? rendered.getStyles() : List.of();
            // 行高：与 ReportRenderService 输出矩阵对齐（含变量行展开后的每一行）
            List<Integer> renderedRowHeightsPx = rendered.getRowHeightsPx();

            for (int r = 0; r < cells.size(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) row = sheet.createRow(r);

                int px = -1;
                if (renderedRowHeightsPx != null && r < renderedRowHeightsPx.size()) {
                    Integer v = renderedRowHeightsPx.get(r);
                    if (v != null && v > 0) px = v;
                }
                if (px <= 0 && cfg.defaultRowHeightPx > 0) px = cfg.defaultRowHeightPx;
                if (px > 0) {
                    short h = pxToPointTwips(px);
                    if (h > 0) row.setHeight(h);
                }

                List<String> rowVals = cells.get(r) != null ? cells.get(r) : List.of();
                List<Map<String, String>> rowStyles =
                        (r < styles.size() && styles.get(r) != null) ? styles.get(r) : List.of();

                for (int c = 0; c < rowVals.size(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) cell = row.createCell(c);

                    String value = rowVals.get(c) != null ? rowVals.get(c) : "";
                    cell.setCellValue(value);

                    // 单元格样式
                    Map<String, String> styleMap = c < rowStyles.size() ? rowStyles.get(c) : null;
                    if (styleMap != null && !styleMap.isEmpty()) {
                        String key = canonicalStyleKey(styleMap);
                        CellStyle poiStyle = styleCache.get(key);
                        if (poiStyle == null) {
                            poiStyle = buildCellStyle(workbook, styleMap);
                            styleCache.put(key, poiStyle);
                        }
                        cell.setCellStyle(poiStyle);
                    }

                    // 媒体类型解析（显式类型 > 非模板原始格子的列兜底）
                    String mediaType = resolveMediaType(mediaTypeCtx, r, c);
                    if (!MediaTypes.isMediaType(mediaType) || value.isBlank()) continue;

                    CellKey key = new CellKey(r, c);
                    // 合并覆盖区（非左上角）禁止重复绘图
                    if (mergeCtx.coveredCells.contains(key)) continue;

                    String mediaCacheKey = mediaType + "|" + value;
                    Optional<MediaBinary> mediaOpt = mediaBinaryCache.get(mediaCacheKey);
                    if (mediaOpt == null) {
                        mediaOpt = mediaBinaryResolver.resolve(callbackProps, mediaType, value);
                        mediaBinaryCache.put(mediaCacheKey, mediaOpt);
                    }
                    if (mediaOpt.isEmpty()) continue; // 降级保留原文本

                    MediaBinary media = mediaOpt.get();
                    boolean embedInCell = resolveEmbedImageInCell(mediaTypeCtx, r, c, mediaType);

                    Integer pictureIndex = pictureIndexCache.get(mediaCacheKey);
                    if (pictureIndex == null) {
                        pictureIndex = workbook.addPicture(media.bytes(), media.pictureType());
                        pictureIndexCache.put(mediaCacheKey, pictureIndex);
                    }

                    CellRangeAddress region = mergeCtx.mergeStartRegion.get(key);
                    addPictureToSheet(
                            sheet,
                            creationHelper,
                            drawing,
                            pictureIndex,
                            r,
                            c,
                            region,
                            embedInCell
                    );

                    // 绘图成功后清空 URL/编码文本，避免在图片旁出现原文
                    cell.setCellValue("");
                }
            }

            // 最后写合并区域
            for (CellRangeAddress region : mergeCtx.regions) {
                sheet.addMergedRegion(region);
            }

            workbook.write(out);
            return new ExportResult(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "report.xlsx",
                    out.toByteArray()
            );
        } catch (Exception e) {
            throw new RuntimeException("Excel 导出失败: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // gridMeta / media / merge context（与旧实现保持一致）
    // ============================================================

    private boolean resolveEmbedImageInCell(MediaTypeContext ctx, int row, int col, String mediaType) {
        if (!"image".equals(mediaType)) return true;
        CellKey key = new CellKey(row, col);
        Boolean explicit = ctx.imageEmbedByCell.get(key);
        if (explicit != null) return explicit;
        // 数据展开行：按列上首个图片列配置的 embed 兜底
        if (!ctx.templateExists.contains(key)) {
            Boolean colFallback = ctx.columnImageEmbedFallback.get(col);
            if (colFallback != null) return colFallback;
        }
        return true;
    }

    private String resolveMediaType(MediaTypeContext ctx, int row, int col) {
        CellKey key = new CellKey(row, col);
        String explicit = ctx.explicitTypes.get(key);
        if (explicit != null) return explicit;
        if (ctx.templateExists.contains(key)) return null;
        return ctx.columnTypeFallback.get(col);
    }

    // ============================================================
    // POI picture / sheet meta
    // ============================================================

    private void addPictureToSheet(
            Sheet sheet,
            CreationHelper creationHelper,
            Drawing<?> drawing,
            int pictureIndex,
            int row,
            int col,
            CellRangeAddress mergedRegion,
            boolean embedInCell
    ) {
        ClientAnchor anchor = creationHelper.createClientAnchor();
        anchor.setCol1(col);
        anchor.setRow1(row);
        int lastCol;
        int lastRow;
        if (mergedRegion != null) {
            lastCol = mergedRegion.getLastColumn();
            lastRow = mergedRegion.getLastRow();
            anchor.setCol2(lastCol + 1);
            anchor.setRow2(lastRow + 1);
        } else {
            lastCol = col;
            lastRow = row;
            anchor.setCol2(col + 1);
            anchor.setRow2(row + 1);
        }
        applyPictureAnchorInsetWithinCells(anchor, sheet, row, col, lastRow, lastCol, MEDIA_ANCHOR_FILL_FRACTION);
        anchor.setAnchorType(embedInCell ? ClientAnchor.AnchorType.MOVE_AND_RESIZE : ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
        Picture picture = drawing.createPicture(anchor, pictureIndex);
        if (picture == null) throw new IllegalStateException("插入图片失败");
    }

    private void applyPictureAnchorInsetWithinCells(
            ClientAnchor anchor,
            Sheet sheet,
            int firstRow,
            int firstCol,
            int lastRow,
            int lastCol,
            double fillFraction
    ) {
        if (!(anchor instanceof XSSFClientAnchor xssf) || !(sheet instanceof XSSFSheet xsh)) return;
        if (fillFraction <= 0 || fillFraction >= 1) return;
        double margin = (1.0d - fillFraction) / 2.0d;

        int wPx = 0;
        for (int c = firstCol; c <= lastCol; c++) {
            wPx += Math.max(1, xsh.getColumnWidthInPixels(c));
        }
        int hPx = 0;
        float defaultPt = sheet.getDefaultRowHeightInPoints();
        for (int r = firstRow; r <= lastRow; r++) {
            Row rowObj = sheet.getRow(r);
            float pt = defaultPt;
            if (rowObj != null) pt = rowObj.getHeightInPoints();
            hPx += Math.max(1, pointsToPx(pt));
        }

        int wEmu = Math.max(1, pxToEmu(wPx));
        int hEmu = Math.max(1, pxToEmu(hPx));
        int dx1 = (int) Math.round(margin * wEmu);
        int dy1 = (int) Math.round(margin * hEmu);
        int dx2 = (int) Math.round(-margin * wEmu);
        int dy2 = (int) Math.round(-margin * hEmu);

        xssf.setDx1(dx1);
        xssf.setDy1(dy1);
        xssf.setDx2(dx2);
        xssf.setDy2(dy2);
    }

    private static int pointsToPx(float points) {
        return Math.max(1, (int) Math.round(points * 96.0d / 72.0d));
    }

    private static int pxToEmu(int px) {
        return Math.max(1, (int) Math.round(px * 914400.0d / 96.0d));
    }

    private void applySheetMeta(Sheet sheet, GridMetaExportConfig cfg) {
        for (int c = 0; c < Math.max(1, cfg.colCount); c++) {
            int px = cfg.colWidths.getOrDefault(c, cfg.defaultColWidthPx);
            sheet.setColumnWidth(c, pxToExcelColumnWidth(px));
        }
        if (cfg.freezeHeaderRows > 0) {
            sheet.createFreezePane(0, cfg.freezeHeaderRows);
        }
    }

    // ============================================================
    // POI style mapping
    // ============================================================

    private CellStyle buildCellStyle(Workbook workbook, Map<String, String> cssStyle) {
        XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        String bg = cssStyle.get("backgroundColor");
        XSSFColor bgColor = parseCssColor(bg);
        if (bgColor != null) {
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setFillForegroundColor(bgColor);
        }

        String align = lower(cssStyle.get("textAlign"));
        if ("left".equals(align)) style.setAlignment(HorizontalAlignment.LEFT);
        else if ("right".equals(align)) style.setAlignment(HorizontalAlignment.RIGHT);
        else if ("center".equals(align)) style.setAlignment(HorizontalAlignment.CENTER);

        if (hasBorder(cssStyle.get("borderTop"))) style.setBorderTop(BorderStyle.THIN);
        if (hasBorder(cssStyle.get("borderRight"))) style.setBorderRight(BorderStyle.THIN);
        if (hasBorder(cssStyle.get("borderBottom"))) style.setBorderBottom(BorderStyle.THIN);
        if (hasBorder(cssStyle.get("borderLeft"))) style.setBorderLeft(BorderStyle.THIN);

        Font font = workbook.createFont();
        String color = cssStyle.get("color");
        XSSFColor fontColor = parseCssColor(color);
        if (fontColor != null && font instanceof XSSFFont xssfFont) {
            xssfFont.setColor(fontColor);
        }
        String family = cssStyle.get("fontFamily");
        if (family != null && !family.isBlank()) {
            font.setFontName(family);
        }
        Short pt = parseFontSizePt(cssStyle.get("fontSize"));
        if (pt != null && pt > 0) font.setFontHeightInPoints(pt);

        String weight = lower(cssStyle.get("fontWeight"));
        if ("bold".equals(weight) || "700".equals(weight) || "800".equals(weight) || "900".equals(weight)) {
            font.setBold(true);
        }
        String italic = lower(cssStyle.get("fontStyle"));
        if ("italic".equals(italic)) font.setItalic(true);

        style.setFont(font);
        return style;
    }

    private String canonicalStyleKey(Map<String, String> cssStyle) {
        List<String> keys = new ArrayList<>(cssStyle.keySet());
        keys.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append(k).append('=').append(cssStyle.get(k)).append(';');
        }
        return sb.toString();
    }

    private XSSFColor parseCssColor(String css) {
        if (css == null || css.isBlank()) return null;
        String c = css.trim().toLowerCase(Locale.ROOT);
        try {
            if (c.startsWith("#")) {
                String hex = c.substring(1);
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1) + hex.substring(0, 1), 16);
                    int g = Integer.parseInt(hex.substring(1, 2) + hex.substring(1, 2), 16);
                    int b = Integer.parseInt(hex.substring(2, 3) + hex.substring(2, 3), 16);
                    return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
                }
                if (hex.length() == 6) {
                    int r = Integer.parseInt(hex.substring(0, 2), 16);
                    int g = Integer.parseInt(hex.substring(2, 4), 16);
                    int b = Integer.parseInt(hex.substring(4, 6), 16);
                    return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
                }
            }
            if (c.startsWith("rgb(") && c.endsWith(")")) {
                String[] arr = c.substring(4, c.length() - 1).split(",");
                if (arr.length >= 3) {
                    int r = Integer.parseInt(arr[0].trim());
                    int g = Integer.parseInt(arr[1].trim());
                    int b = Integer.parseInt(arr[2].trim());
                    return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private Short parseFontSizePt(String cssFontSize) {
        if (cssFontSize == null || cssFontSize.isBlank()) return null;
        String s = cssFontSize.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("pt")) {
                return (short) Math.max(1, Math.round(Float.parseFloat(s.substring(0, s.length() - 2).trim())));
            }
            if (s.endsWith("px")) {
                float px = Float.parseFloat(s.substring(0, s.length() - 2).trim());
                return (short) Math.max(1, Math.round(px * 72f / 96f));
            }
            return (short) Math.max(1, Math.round(Float.parseFloat(s)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasBorder(String cssBorder) {
        if (cssBorder == null || cssBorder.isBlank()) return false;
        String s = cssBorder.toLowerCase(Locale.ROOT);
        return s.contains("solid") && (s.contains("1px") || s.contains("2px") || s.contains("3px") || s.contains("#"));
    }


    private String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private int pxToExcelColumnWidth(int px) {
        int p = Math.max(1, px);
        // 经验换算：Excel 列宽单位约为字符宽度*256，这里沿用旧实现的近似策略
        return Math.max(256, (int) Math.round(p * 256.0d / 7.0d));
    }

    private short pxToPointTwips(int px) {
        // 1pt=1/72in, 96dpi: px -> pt = px*72/96, twips=pt*20
        int twips = (int) Math.round(px * 72.0d / 96.0d * 20.0d);
        return (short) Math.min(Short.MAX_VALUE, Math.max(0, twips));
    }

}

