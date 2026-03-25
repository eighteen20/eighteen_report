package cn.com._1820.eighteen_report.service.export.pdf;

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
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * PDF 导出器：直接绘制表格（含样式、合并、行高列宽与媒体图片）。
 *
 * <p>实现原则：</p>
 * <ul>
 *   <li>复用渲染服务输出（cells/styles/merges/rowHeightsPx/watermark）；</li>
 *   <li>冻结行：通过 iText Table header cell 重复显示；</li>
 *   <li>图片：媒体以单元格内嵌方式绘制，并按比例 inset 缩放避免遮挡边框。</li>
 * </ul>
 */
@Slf4j
@Component
public class PdfReportExporter implements ReportExporter {

    /**
     * 图片在单元格内的留白比例（与 Excel 导出对齐），避免贴边遮住边框线。
     */
    private static final double MEDIA_ANCHOR_FILL_FRACTION = 0.75d;

    private final GridMetaExportConfigParser gridMetaParser;
    private final MediaTypeContextParser mediaTypeParser;
    private final MergePlacementContextBuilder mergeCtxBuilder;
    private final MediaBinaryResolver mediaBinaryResolver;
    private final PdfFontProvider fontProvider;
    private final PdfWatermarkRenderer watermarkRenderer;

    public PdfReportExporter(
            GridMetaExportConfigParser gridMetaParser,
            MediaTypeContextParser mediaTypeParser,
            MergePlacementContextBuilder mergeCtxBuilder,
            MediaBinaryResolver mediaBinaryResolver,
            PdfFontProvider fontProvider,
            PdfWatermarkRenderer watermarkRenderer
    ) {
        this.gridMetaParser = gridMetaParser;
        this.mediaTypeParser = mediaTypeParser;
        this.mergeCtxBuilder = mergeCtxBuilder;
        this.mediaBinaryResolver = mediaBinaryResolver;
        this.fontProvider = fontProvider;
        this.watermarkRenderer = watermarkRenderer;
    }

    @Override
    public boolean supports(String format) {
        return "pdf".equals(format);
    }

    @Override
    public ExportResult export(ExportContext ctx) {
        return exportPdf(ctx.templateContent(), ctx.rendered(), ctx);
    }

    private ExportResult exportPdf(Map<String, Object> templateContent, ReportRenderResponse rendered, ExportContext exportCtx) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            GridMetaExportConfig cfg = gridMetaParser.parse(templateContent, rendered);
            MediaTypeContext mediaTypeCtx = mediaTypeParser.parse(templateContent);
            MergePlacementContext mergeCtx = mergeCtxBuilder.build(rendered.getMerges());

            List<List<String>> cells = rendered.getCells() != null ? rendered.getCells() : List.of();
            List<List<Map<String, String>>> styles = rendered.getStyles() != null ? rendered.getStyles() : List.of();

            int rowCount = cells.size();
            int colCount = Math.max(1, rendered.getColCount());

            // 列宽（px -> pt）与行高（px -> pt）
            double[] colWidthsPt = new double[colCount];
            for (int c = 0; c < colCount; c++) {
                int px = cfg.colWidths.getOrDefault(c, cfg.defaultColWidthPx);
                colWidthsPt[c] = pxToPoint(px);
            }

            List<Integer> rowHeightsPx = rendered.getRowHeightsPx();
            double[] rowHeightsPt = new double[rowCount];
            for (int r = 0; r < rowCount; r++) {
                int px = cfg.defaultRowHeightPx;
                if (rowHeightsPx != null && r < rowHeightsPx.size()) {
                    Integer v = rowHeightsPx.get(r);
                    if (v != null && v > 0) px = v;
                }
                rowHeightsPt[r] = pxToPoint(px);
            }

            PdfWriter writer = new PdfWriter(out);
            com.itextpdf.kernel.pdf.PdfDocument pdfDocument = new com.itextpdf.kernel.pdf.PdfDocument(writer);
            pdfDocument.setDefaultPageSize(PageSize.A4.rotate());
            Document doc = new Document(pdfDocument);
            doc.setMargins(12, 12, 12, 12);

            // 字体：优先加载 NotoSansCJK（classpath:fonts/...）；否则回退标准字体。
            PdfFont regularFont = fontProvider.regular();
            PdfFont boldFont = fontProvider.bold();

            // 水印：每页事件绘制
            String watermarkText = rendered.getWatermark();
            PdfFont watermarkFont = regularFont != null ? regularFont : boldFont;
            watermarkRenderer.installPerPageWatermark(pdfDocument, watermarkText, watermarkFont);

            // 默认边框颜色（与前端 #374151 一致）
            DeviceRgb defaultBorderColor = new DeviceRgb(55, 65, 81);
            float borderWidthPt = 0.5f;

            float[] tableColWidths = new float[colCount];
            for (int c = 0; c < colCount; c++) {
                tableColWidths[c] = (float) colWidthsPt[c];
            }
            float totalWidthPt = 0f;
            for (float w : tableColWidths) totalWidthPt += w;

            Table table = new Table(UnitValue.createPointArray(tableColWidths));
            table.setWidth(UnitValue.createPointValue(totalWidthPt));
            table.setMargin(0);

            int freezeHeaderRows = Math.max(0, cfg.freezeHeaderRows);
            int headerRows = Math.min(freezeHeaderRows, rowCount);

            // 媒体缓存：同一个媒体源只生成/下载一次，避免重复下载
            Map<String, Optional<MediaBinary>> mediaBinaryCache = new HashMap<>();

            for (int r = 0; r < rowCount; r++) {
                boolean isHeader = r < headerRows;
                List<String> rowVals = cells.get(r) != null ? cells.get(r) : List.of();

                for (int c = 0; c < colCount; ) {
                    CellKey key = new CellKey(r, c);
                    if (mergeCtx.coveredCells.contains(key)) {
                        c++;
                        continue;
                    }

                    CellRangeAddress region = mergeCtx.mergeStartRegion.get(key);
                    int colSpan = region != null ? (region.getLastColumn() - region.getFirstColumn() + 1) : 1;
                    int rowSpan = region != null ? (region.getLastRow() - region.getFirstRow() + 1) : 1;

                    double cellW = 0;
                    for (int cc = c; cc < Math.min(colCount, c + colSpan); cc++) cellW += colWidthsPt[cc];
                    double cellH = 0;
                    for (int rr = r; rr < Math.min(rowCount, r + rowSpan); rr++) cellH += rowHeightsPt[rr];

                    // 样式
                    Map<String, String> styleMap = null;
                    if (r < styles.size() && styles.get(r) != null) {
                        List<Map<String, String>> rowStyles = styles.get(r);
                        if (c < rowStyles.size()) styleMap = rowStyles.get(c);
                    }

                    // 单元格内容：锚点为空时扫描合并矩形内首个非空字符串，避免合并区域文字丢失
                    String value = c < rowVals.size() ? rowVals.get(c) : "";
                    if ((region != null) && (value == null || value.isBlank())) {
                        boolean found = false;
                        int endR = Math.min(rowCount, r + rowSpan);
                        int endC = Math.min(colCount, c + colSpan);
                        for (int rr = r; rr < endR && !found; rr++) {
                            List<String> scanRowVals = cells.get(rr);
                            if (scanRowVals == null) continue;
                            for (int cc = c; cc < endC; cc++) {
                                String sv = cc < scanRowVals.size() ? scanRowVals.get(cc) : "";
                                if (sv != null && !sv.isBlank()) {
                                    value = sv;
                                    found = true;
                                    break;
                                }
                            }
                        }
                    }

                    com.itextpdf.layout.element.Cell pdfCell = new com.itextpdf.layout.element.Cell(rowSpan, colSpan);
                    pdfCell.setPadding(1f);
                    // 只设置最小高度，避免 iText 在强制高度下裁剪多行文本（表现为“文本丢失”）
                    pdfCell.setMinHeight((float) cellH + 0.5f);
                    pdfCell.setVerticalAlignment(com.itextpdf.layout.properties.VerticalAlignment.MIDDLE);
                    pdfCell.setBorder(Border.NO_BORDER);

                    // 边框
                    if (styleMap != null && !styleMap.isEmpty()) {
                        if (hasBorder(styleMap.get("borderTop"))) pdfCell.setBorderTop(new SolidBorder(defaultBorderColor, borderWidthPt));
                        if (hasBorder(styleMap.get("borderBottom"))) pdfCell.setBorderBottom(new SolidBorder(defaultBorderColor, borderWidthPt));
                        if (hasBorder(styleMap.get("borderLeft"))) pdfCell.setBorderLeft(new SolidBorder(defaultBorderColor, borderWidthPt));
                        if (hasBorder(styleMap.get("borderRight"))) pdfCell.setBorderRight(new SolidBorder(defaultBorderColor, borderWidthPt));
                    }

                    // 背景色
                    if (styleMap != null && !styleMap.isEmpty()) {
                        String bg = styleMap.get("backgroundColor");
                        XSSFColor bgColor = CssColorParser.parseCssColor(bg);
                        if (bgColor != null) {
                            DeviceRgb rgb = xssfColorToDeviceRgb(bgColor);
                            if (rgb != null) pdfCell.setBackgroundColor(rgb);
                        }
                    }

                    // 字体/对齐
                    PdfFont cellFont = regularFont;
                    int fontSizePt = -1;
                    TextAlignment textAlign = TextAlignment.LEFT;
                    if (styleMap != null && !styleMap.isEmpty()) {
                        String fw = lower(styleMap.get("fontWeight"));
                        boolean bold = "bold".equals(fw) || "700".equals(fw) || "800".equals(fw) || "900".equals(fw);
                        if (bold && boldFont != null) cellFont = boldFont;

                        Short pt = parseFontSizePt(styleMap.get("fontSize"));
                        if (pt != null && pt > 0) fontSizePt = pt;

                        String align = lower(styleMap.get("textAlign"));
                        if ("left".equals(align)) textAlign = TextAlignment.LEFT;
                        else if ("right".equals(align)) textAlign = TextAlignment.RIGHT;
                        else if ("center".equals(align)) textAlign = TextAlignment.CENTER;

                        // 字体颜色与 italic 策略（italic 不额外下载时由 iText 做近似渲染）
                        String colorCss = styleMap.get("color");
                        XSSFColor fontColor = CssColorParser.parseCssColor(colorCss);
                        if (fontColor != null) {
                            DeviceRgb fontRgb = xssfColorToDeviceRgb(fontColor);
                            if (fontRgb != null) pdfCell.setFontColor(fontRgb);
                        }
                        String fontStyle = lower(styleMap.get("fontStyle"));
                        if (fontStyle != null && (fontStyle.contains("italic") || fontStyle.contains("oblique"))) {
                            pdfCell.setItalic();
                        }
                    }

                    // 绘制媒体（image/qrcode/barcode）
                    String mediaType = resolveMediaType(mediaTypeCtx, r, c);
                    boolean shouldTryMedia = MediaTypes.isMediaType(mediaType) && value != null && !value.isBlank();
                    if (shouldTryMedia) {
                        String mediaCacheKey = mediaType + "|" + value;
                        Optional<MediaBinary> mediaOpt = mediaBinaryCache.get(mediaCacheKey);
                        if (mediaOpt == null) {
                            mediaOpt = mediaBinaryResolver.resolve(exportCtx.callbackProperties(), mediaType, value);
                            mediaBinaryCache.put(mediaCacheKey, mediaOpt);
                        }
                        if (mediaOpt != null && mediaOpt.isPresent()) {
                            MediaBinary media = mediaOpt.get();
                            float targetW = (float) (cellW * MEDIA_ANCHOR_FILL_FRACTION);
                            float targetH = (float) (cellH * MEDIA_ANCHOR_FILL_FRACTION);
                            Image img = new Image(ImageDataFactory.create(media.bytes()));
                            img.setAutoScale(false);
                            img.scaleToFit(targetW, targetH);
                            img.setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER);
                            pdfCell.add(img);
                        } else {
                            // 降级：图片生成失败则输出原文本
                            addTextCell(pdfCell, value, cellFont, textAlign, fontSizePt);
                        }
                    } else {
                        // 普通文本
                        addTextCell(pdfCell, value, cellFont, textAlign, fontSizePt);
                    }

                    if (isHeader) {
                        table.addHeaderCell(pdfCell);
                    } else {
                        table.addCell(pdfCell);
                    }

                    c += Math.max(1, colSpan);
                }
            }

            doc.add(table);
            doc.close();

            return new ExportResult("application/pdf", "report.pdf", out.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("PDF 导出失败: " + e.getMessage(), e);
        }
    }

    private void addTextCell(
            com.itextpdf.layout.element.Cell pdfCell,
            String value,
            PdfFont font,
            TextAlignment textAlign,
            int fontSizePt
    ) {
        if (value == null || value.isBlank()) return;
        Paragraph p = new Paragraph(value).setFont(font).setMargin(0).setTextAlignment(textAlign);
        // 给一个较保守的行高，减少“强制高度下被裁剪”的概率
        p.setMultipliedLeading(1.1f);
        if (fontSizePt > 0) p.setFontSize(fontSizePt);
        pdfCell.add(p);
    }

    /** px（屏幕单位）换算为 PDF point（1pt=1/72in；96dpi -> 72pt） */
    private static double pxToPoint(int px) {
        return px * 72.0d / 96.0d;
    }

    /** XSSFColor 转 iText DeviceRgb（无透明度时取 RGB）。 */
    private DeviceRgb xssfColorToDeviceRgb(XSSFColor c) {
        if (c == null) return null;
        byte[] rgb = c.getRGB();
        if (rgb == null || rgb.length < 3) {
            rgb = c.getRGBWithTint();
        }
        if (rgb == null || rgb.length < 3) return null;
        int r = rgb[0] & 0xFF;
        int g = rgb[1] & 0xFF;
        int b = rgb[2] & 0xFF;
        return new DeviceRgb(r, g, b);
    }

    private String resolveMediaType(MediaTypeContext ctx, int row, int col) {
        CellKey key = new CellKey(row, col);
        String explicit = ctx.explicitTypes.get(key);
        if (explicit != null) return explicit;
        if (ctx.templateExists.contains(key)) return null;
        return ctx.columnTypeFallback.get(col);
    }

    private boolean hasBorder(String cssBorder) {
        if (cssBorder == null || cssBorder.isBlank()) return false;
        String s = cssBorder.toLowerCase(Locale.ROOT);
        return s.contains("solid") && (s.contains("1px") || s.contains("2px") || s.contains("3px") || s.contains("#"));
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

    private String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * CSS 颜色解析：#RGB / #RRGGBB / rgb(r,g,b)。
     *
     * <p>说明：此处复用 XSSFColor 作为颜色承载（与 Excel 侧一致），再转 iText DeviceRgb。</p>
     */
    private static final class CssColorParser {
        private static XSSFColor parseCssColor(String css) {
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
    }
}

