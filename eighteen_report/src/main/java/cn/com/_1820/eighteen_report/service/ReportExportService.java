package cn.com._1820.eighteen_report.service;

import cn.com._1820.eighteen_report.config.WatermarkCallbackProperties;
import cn.com._1820.eighteen_report.dto.ReportExportRequest;
import cn.com._1820.eighteen_report.dto.ReportRenderResponse;
import cn.com._1820.eighteen_report.entity.ReportTemplate;
import cn.com._1820.eighteen_report.repository.ReportTemplateRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报表导出服务：先渲染，再按样式与媒体类型写入 Excel（xlsx）。
 *
 * <p>当前导出能力：</p>
 * <ul>
 *   <li>文本值、列宽、行高、冻结、合并、基础样式（背景/字体/对齐/边框）；</li>
 *   <li>媒体：image / qrcode / barcode；POI 绘图锚点在单元格调为约 75% 宽高并居中（dx/dy 内缩），减轻贴边遮挡边框；{@code embedImageInCell} 控制 {@code MOVE_AND_RESIZE} / {@code MOVE_DONT_RESIZE}；码图同理；</li>
 *   <li>媒体容错：下载/生成失败时降级为文本，不中断导出。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportService {

    /** 单元格引用解析：A1、AA12... */
    private static final Pattern CELL_REF_PATTERN = Pattern.compile("^([A-Z]+)(\\d+)$");
    /** data:image/...;base64,... 解析 */
    private static final Pattern DATA_URL_PATTERN = Pattern.compile("^data:(image/[\\w+.-]+);base64,(.+)$", Pattern.CASE_INSENSITIVE);

    /** 远程图片读取大小上限（字节） */
    private static final int MAX_REMOTE_IMAGE_BYTES = 5 * 1024 * 1024;
    /** dataURL 长度上限（字符） */
    private static final int MAX_DATA_URL_CHARS = 8 * 1024 * 1024;

    /**
     * 媒体图在单元格/合并区内所占比例（相对宽与高），略小于 1 可留出边距，避免贴边遮住边框线。
     */
    private static final double MEDIA_ANCHOR_FILL_FRACTION = 0.75d;

    private final ReportRenderService renderService;
    private final ReportTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;
    /** 复用水印回调的 SSRF 相关配置（白名单、私网限制、超时） */
    private final WatermarkCallbackProperties callbackProperties;

    /**
     * 根据导出请求渲染报表并导出为指定格式字节流。
     *
     * @param request 导出请求（模板 ID、参数、格式）
     * @return 导出结果（contentType、文件名、二进制）
     */
    public ExportResult export(ReportExportRequest request) {
        ReportRenderResponse rendered = renderService.render(
                request.getTemplateId(),
                request.getQueryParams() != null ? request.getQueryParams() : Collections.emptyMap()
        );

        String format = request.getFormat() != null ? request.getFormat().toLowerCase(Locale.ROOT) : "xlsx";
        if ("xlsx".equals(format)) {
            return exportExcel(request.getTemplateId(), rendered);
        }
        if ("pdf".equals(format)) {
            return exportPdf(request.getTemplateId(), rendered);
        }
        throw new IllegalArgumentException("不支持的导出格式: " + request.getFormat());
    }

    /**
     * 导出 Excel：写入文本+样式后，再按类型嵌入媒体图片。
     *
     * @param templateId 模板 ID
     * @param rendered   渲染结果（文本矩阵、样式矩阵、合并区域）
     * @return xlsx 二进制
     */
    private ExportResult exportExcel(String templateId, ReportRenderResponse rendered) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("报表");
            CreationHelper creationHelper = workbook.getCreationHelper();
            Drawing<?> drawing = sheet.createDrawingPatriarch();

            // 一次读取模板 content，供 gridMeta + 单元格类型共用
            Map<String, Object> templateContent = readTemplateContent(templateId);
            GridMetaExportConfig cfg = parseGridMetaExportConfig(templateContent, rendered);
            MediaTypeContext mediaTypeCtx = parseMediaTypeContext(templateContent);
            applySheetMeta(sheet, cfg);

            // 合并区域先建索引，供“媒体绘图位置判定”使用
            MergePlacementContext mergeCtx = buildMergePlacementContext(rendered.getMerges());

            // 样式缓存：相同样式复用
            Map<String, CellStyle> styleCache = new HashMap<>();
            // 媒体缓存：同一个媒体源只生成/下载一次，复用 workbook picture index
            Map<String, Optional<MediaBinary>> mediaBinaryCache = new HashMap<>();
            Map<String, Integer> pictureIndexCache = new HashMap<>();

            List<List<String>> cells = rendered.getCells() != null ? rendered.getCells() : List.of();
            List<List<Map<String, String>>> styles = rendered.getStyles() != null ? rendered.getStyles() : List.of();
            // 行高：与 ReportRenderService 输出矩阵对齐（含变量行展开后的每一行）；勿再用模板行号索引 cfg.rowHeights
            List<Integer> renderedRowHeightsPx = rendered.getRowHeightsPx();

            for (int r = 0; r < cells.size(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    row = sheet.createRow(r);
                }
                int px = -1;
                if (renderedRowHeightsPx != null && r < renderedRowHeightsPx.size()) {
                    Integer v = renderedRowHeightsPx.get(r);
                    if (v != null && v > 0) {
                        px = v;
                    }
                }
                if (px <= 0 && cfg.defaultRowHeightPx > 0) {
                    px = cfg.defaultRowHeightPx;
                }
                if (px > 0) {
                    short h = pxToPointTwips(px);
                    if (h > 0) {
                        row.setHeight(h);
                    }
                }

                List<String> rowVals = cells.get(r) != null ? cells.get(r) : List.of();
                List<Map<String, String>> rowStyles =
                        (r < styles.size() && styles.get(r) != null) ? styles.get(r) : List.of();

                for (int c = 0; c < rowVals.size(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) {
                        cell = row.createCell(c);
                    }
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
                    if (!isMediaType(mediaType) || value.isBlank()) {
                        continue;
                    }

                    CellKey key = new CellKey(r, c);
                    // 合并覆盖区（非左上角）禁止重复绘图
                    if (mergeCtx.coveredCells.contains(key)) {
                        continue;
                    }

                    String mediaCacheKey = mediaType + "|" + value;
                    Optional<MediaBinary> mediaOpt = mediaBinaryCache.get(mediaCacheKey);
                    if (mediaOpt == null) {
                        mediaOpt = resolveMediaBinary(mediaType, value);
                        mediaBinaryCache.put(mediaCacheKey, mediaOpt);
                    }
                    if (mediaOpt.isEmpty()) {
                        continue; // 降级保留原文本
                    }

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

    /**
     * 导出 PDF：直接绘制表格（含样式、合并、行高列宽与媒体图片）。
     *
     * <p>当前实现目标：</p>
     * <ul>
     *   <li>尽量复用现有渲染结果与媒体下载/生成逻辑；</li>
     *   <li>冻结行：通过 iText Table header cell 重复显示（视觉上类似冻结表头）；</li>
     *   <li>图片：媒体以单元格内嵌方式绘制，并按 75% inset 缩放避免遮挡边框线。</li>
     * </ul>
     *
     * @param templateId 模板 ID
     * @param rendered   渲染结果（已变量替换与行展开）
     * @return PDF 文件结果
     */
    private ExportResult exportPdf(String templateId, ReportRenderResponse rendered) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Map<String, Object> templateContent = readTemplateContent(templateId);
            GridMetaExportConfig cfg = parseGridMetaExportConfig(templateContent, rendered);
            MediaTypeContext mediaTypeCtx = parseMediaTypeContext(templateContent);
            MergePlacementContext mergeCtx = buildMergePlacementContext(rendered.getMerges());

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
            PdfDocument pdfDocument = new PdfDocument(writer);
            pdfDocument.setDefaultPageSize(PageSize.A4.rotate());
            Document doc = new Document(pdfDocument);
            doc.setMargins(12, 12, 12, 12);

            // 字体：优先加载 NotoSansCJK（classpath:fonts/...）；否则回退到标准字体。
            // 这里不依赖资源路径的前导 "/"，loadNotoOrFallback 内部会做双路径兜底。
            PdfFont regularFont = loadNotoOrFallback("fonts/NotoSansCJKsc-Regular.otf");
            PdfFont boldFont = loadNotoOrFallback("fonts/NotoSansCJKsc-Bold.otf");

            // 水印：使用渲染接口返回的 dynamic/fixed watermark，在每页以低透明度斜向平铺绘制。
            // 注意：前端是通过 SVG pattern 平铺实现，这里用 iText 的图形状态 + 文字矩阵近似复现。
            String watermarkText = rendered.getWatermark();
            PdfFont watermarkFont = regularFont != null ? regularFont : boldFont;
            if (watermarkText != null && !watermarkText.isBlank() && watermarkFont != null) {
                final float watermarkOpacity = 0.12f; // 对齐前端 rgba(...,0.12)
                final float watermarkFontSizePt = 12f; // 16px * 0.75（96dpi -> 72pt）
                final float angleRad = (float) Math.toRadians(-30);
                final PdfExtGState extGState = new PdfExtGState().setFillOpacity(watermarkOpacity);

                pdfDocument.addEventHandler(PdfDocumentEvent.START_PAGE, new com.itextpdf.kernel.events.IEventHandler() {
                    @Override
                    public void handleEvent(com.itextpdf.kernel.events.Event event) {
                        try {
                            PdfDocumentEvent pdfEvent = (PdfDocumentEvent) event;
                            var page = pdfEvent.getPage();
                        PdfCanvas canvas = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDocument);
                        canvas.saveState();
                        canvas.setFillColor(new DeviceRgb(17, 24, 39));
                        canvas.setExtGState(extGState);
                        canvas.beginText();
                        canvas.setFontAndSize(watermarkFont, watermarkFontSizePt);

                        // 与前端 watermarkDensity=1 时的 SVG pattern 尺寸对齐（仅用于近似平铺节奏）
                        float density = 1f;
                        float tileWPx = 360f * density;
                        float tileHPx = 240f * density;
                        float tileWPt = tileWPx * 72f / 96f;
                        float tileHPt = tileHPx * 72f / 96f;

                        float cos = (float) Math.cos(angleRad);
                        float sin = (float) Math.sin(angleRad);

                        float pageW = page.getPageSize().getWidth();
                        float pageH = page.getPageSize().getHeight();

                        for (float x = -tileWPt; x < pageW + tileWPt; x += tileWPt) {
                            for (float y = -tileHPt; y < pageH + tileHPt; y += tileHPt) {
                                // 这里用 x/y 作为基线中心点（配合 setTextMatrix 的旋转矩阵）
                                float cx = x + tileWPt / 2f;
                                float cy = y + tileHPt / 2f;
                                canvas.setTextMatrix(cos, sin, -sin, cos, cx, cy);
                                canvas.showText(watermarkText);
                            }
                        }

                        canvas.endText();
                        canvas.restoreState();
                        } catch (Exception ignored) {
                            // 水印失败时不影响主内容导出可用性
                        }
                    }
                });
            }

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

                    // 单元格内容：优先取合并锚点 value；若锚点为空（但合并覆盖区可能仍有文本），
                    // 则在合并矩形范围内扫描第一个非空字符串，避免合并区域文字丢失。
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
                        XSSFColor bgColor = parseCssColor(bg);
                        if (bgColor != null) {
                            DeviceRgb rgb = xssfColorToDeviceRgb(bgColor);
                            if (rgb != null) pdfCell.setBackgroundColor(rgb);
                        }
                    }

                    // 字体/对齐
                    PdfFont cellFont = regularFont;
                    DeviceRgb fontColorRgb = null;
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
                        XSSFColor fontColor = parseCssColor(colorCss);
                        if (fontColor != null) {
                            fontColorRgb = xssfColorToDeviceRgb(fontColor);
                            if (fontColorRgb != null) pdfCell.setFontColor(fontColorRgb);
                        }
                        String fontStyle = lower(styleMap.get("fontStyle"));
                        if (fontStyle != null && (fontStyle.contains("italic") || fontStyle.contains("oblique"))) {
                            pdfCell.setItalic();
                        }
                    }

                    // 绘制媒体（image/qrcode/barcode）
                    String mediaType = resolveMediaType(mediaTypeCtx, r, c);
                    boolean shouldTryMedia = isMediaType(mediaType) && value != null && !value.isBlank();
                    if (shouldTryMedia) {
                        String mediaCacheKey = mediaType + "|" + value;
                        Optional<MediaBinary> mediaOpt = mediaBinaryCache.get(mediaCacheKey);
                        if (mediaOpt == null) {
                            mediaOpt = resolveMediaBinary(mediaType, value);
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
                            if (value != null && !value.isBlank()) {
                                Paragraph p = new Paragraph(value).setFont(cellFont).setMargin(0).setTextAlignment(textAlign);
                                // 给一个较保守的行高，减少“强制高度下被裁剪”的概率
                                p.setMultipliedLeading(1.1f);
                                if (fontSizePt > 0) p.setFontSize(fontSizePt);
                                pdfCell.add(p);
                            }
                        }
                    } else {
                        // 普通文本
                        if (value != null && !value.isBlank()) {
                            Paragraph p = new Paragraph(value).setFont(cellFont).setMargin(0).setTextAlignment(textAlign);
                            // 给一个较保守的行高，减少“强制高度下被裁剪”的概率
                            p.setMultipliedLeading(1.1f);
                            if (fontSizePt > 0) p.setFontSize(fontSizePt);
                            pdfCell.add(p);
                        }
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

            return new ExportResult(
                    "application/pdf",
                    "report.pdf",
                    out.toByteArray()
            );
        } catch (Exception e) {
            throw new RuntimeException("PDF 导出失败: " + e.getMessage(), e);
        }
    }

    /** px（屏幕单位）换算为 PDF point（1pt=1/72in；96dpi -> 72pt） */
    private static double pxToPoint(int px) {
        return px * 72.0d / 96.0d;
    }

    /**
     * 加载 NotoSansCJK 字体：按 classpath 路径读取并嵌入；失败则回退标准字体（中文可能显示方块）。
     *
     * @param classpathResource 类路径资源，如 {@code /fonts/NotoSansCJKsc-Regular.otf}
     */
    private PdfFont loadNotoOrFallback(String classpathResource) {
        boolean wantBold = classpathResource != null && classpathResource.toLowerCase(Locale.ROOT).contains("bold");

        // 1) 优先加载 Noto OTF（你当前 Regular/Bold 均已成功下载到大文件体积时，这一步应能覆盖中文）
        try {
            String p1 = classpathResource;
            String p2 = classpathResource.startsWith("/") ? classpathResource.substring(1) : "/" + classpathResource;

            java.io.InputStream is = getClass().getResourceAsStream(p1);
            if (is == null) is = getClass().getResourceAsStream(p2);
            if (is == null) is = Thread.currentThread().getContextClassLoader().getResourceAsStream(p1);
            if (is == null) is = Thread.currentThread().getContextClassLoader().getResourceAsStream(p2);

            if (is != null) {
                try (java.io.InputStream in = is) {
                    byte[] bytes = in.readAllBytes();
                    PdfFont font = PdfFontFactory.createFont(
                            bytes,
                            PdfEncodings.IDENTITY_H,
                            PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED,
                            true
                    );
                    if (font != null) {
                        log.warn("PDF 中文字体加载：使用 Noto OTF: {}", classpathResource);
                        return font;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载 Noto OTF 失败: resource={}, error={}", classpathResource, e.getMessage());
        }

        // 2) bold 加载失败时，用 regular OTF 兜底（避免 Helvetica 导致方块字）
        if (wantBold) {
            try {
                String regularRes = "fonts/NotoSansCJKsc-Regular.otf";
                String p1 = regularRes;
                String p2 = regularRes.startsWith("/") ? regularRes.substring(1) : "/" + regularRes;

                java.io.InputStream is = getClass().getResourceAsStream(p1);
                if (is == null) is = getClass().getResourceAsStream(p2);
                if (is == null) is = Thread.currentThread().getContextClassLoader().getResourceAsStream(p1);
                if (is == null) is = Thread.currentThread().getContextClassLoader().getResourceAsStream(p2);

                if (is != null) {
                    try (java.io.InputStream in = is) {
                        byte[] bytes = in.readAllBytes();
                        PdfFont font = PdfFontFactory.createFont(
                                bytes,
                                PdfEncodings.IDENTITY_H,
                                PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED,
                                true
                        );
                        if (font != null) {
                            log.warn("PDF 中文字体加载：bold Noto OTF 失败，回退到 Noto regular");
                            return font;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("bold Noto regular 回退失败: error={}", e.getMessage());
            }
            // bold OTF + regular OTF 都失败时，不再回退 Helvetica（避免方块字）
            return null;
        }

        // 3) Noto OTF 都失败，最后尝试 TTC（可能仍因映射/face 导致 containsGlyph=0，因此仅作为兜底）
        try {
            PdfFont ttcFont = loadTtcFontOrNull(
                    wantBold ? "fonts/STHeiti-Medium.ttc" : "fonts/STHeiti-Light.ttc",
                    0
            );
            if (ttcFont != null) {
                log.warn("PDF 中文字体加载：使用 TTC兜底");
                return ttcFont;
            }
        } catch (Exception ignored) {
            // ignore
        }

        // 4) 最终兜底：regular 回退 Helvetica，bold 返回 null
        try {
            PdfFont f = PdfFontFactory.createFont(StandardFonts.HELVETICA, PdfEncodings.WINANSI);
            log.warn("PDF 中文字体加载：回退 Helvetica（可能不支持中文，但避免导出中断）");
            return f;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 加载 TTC 并创建 PdfFont：失败返回 null。
     *
     * @param classpathTtcResource classpath 资源，如 {@code fonts/STHeiti-Light.ttc}
     * @param ttcIndex             TTC 内字体索引（通常 0）
     */
    private PdfFont loadTtcFontOrNull(String classpathTtcResource, int ttcIndex) {
        try {
            String p1 = classpathTtcResource;
            String p2 = classpathTtcResource.startsWith("/") ? classpathTtcResource.substring(1) : "/" + classpathTtcResource;

            java.io.InputStream is = getClass().getResourceAsStream(p1);
            if (is == null) is = getClass().getResourceAsStream(p2);
            if (is == null) is = Thread.currentThread().getContextClassLoader().getResourceAsStream(p1);
            if (is == null) is = Thread.currentThread().getContextClassLoader().getResourceAsStream(p2);
            if (is == null) return null;

            byte[] bytes;
            try (java.io.InputStream in = is) {
                bytes = in.readAllBytes();
            }

            // 由于 TTC 内部可能存在多个字体 face，且不同 encoding(IDENTITY_H/V) 对字形映射会有影响，
            // 我们创建候选字体后用 containsGlyph() 检测关键中文字符是否可用，选择得分最高的那个返回。
            String test = "用户中文";
            float bestScore = -1;
            PdfFont bestFont = null;

            String[] encodings = new String[] { PdfEncodings.IDENTITY_H, PdfEncodings.IDENTITY_V };
            for (int idx = ttcIndex; idx < ttcIndex + 8; idx++) {
                FontProgram fp;
                try {
                    fp = FontProgramFactory.createFont(bytes, idx, true);
                } catch (Exception ignore) {
                    continue;
                }
                if (fp == null) continue;

                for (String enc : encodings) {
                    PdfFont font = PdfFontFactory.createFont(
                            fp,
                            enc,
                            PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED
                    );
                    if (font == null) continue;

                    float score = 0;
                    for (int i = 0; i < test.length(); i++) {
                        int cp = test.codePointAt(i);
                        if (font.containsGlyph(cp)) score += 1;
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        bestFont = font;
                    }
                }
            }

            if (bestFont != null) {
                log.warn("PDF 中文 TTC 字体选择：resource={}, fromIndex={}, bestScore={}", classpathTtcResource, ttcIndex, bestScore);
            }
            return bestFont;
        } catch (Exception e) {
            log.warn("TTC 字体兜底加载失败: resource={}, error={}", classpathTtcResource, e.getMessage());
            return null;
        }
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

    /**
     * 读取模板 content JSON。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readTemplateContent(String templateId) {
        try {
            ReportTemplate template = templateRepository.findById(templateId)
                    .orElseThrow(() -> new IllegalArgumentException("报表模板不存在: " + templateId));
            if (template.getContent() == null || template.getContent().isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(template.getContent(), Map.class);
        } catch (Exception e) {
            log.warn("读取模板内容失败，导出按默认配置继续: templateId={}, error={}", templateId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 解析 gridMeta 导出配置：列宽、行高、冻结行等。
     */
    @SuppressWarnings("unchecked")
    private GridMetaExportConfig parseGridMetaExportConfig(
            Map<String, Object> templateContent,
            ReportRenderResponse rendered
    ) {
        GridMetaExportConfig cfg = new GridMetaExportConfig();
        cfg.colCount = Math.max(1, rendered.getColCount());
        cfg.defaultColWidthPx = 100;
        cfg.defaultRowHeightPx = 25;
        cfg.freezeHeaderRows = 0;
        cfg.colWidths = new HashMap<>();

        if (rendered.getColCount() <= 0 && rendered.getCells() != null && !rendered.getCells().isEmpty()) {
            cfg.colCount = Math.max(1, rendered.getCells().getFirst().size());
        }

        Object gmObj = templateContent.get("gridMeta");
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
     * 解析模板单元格类型上下文（显式类型、模板存在性、列兜底）。
     */
    @SuppressWarnings("unchecked")
    private MediaTypeContext parseMediaTypeContext(Map<String, Object> templateContent) {
        MediaTypeContext ctx = new MediaTypeContext();
        ctx.explicitTypes = new HashMap<>();
        ctx.templateExists = new HashSet<>();
        ctx.columnTypeFallback = new HashMap<>();
        ctx.imageEmbedByCell = new HashMap<>();
        ctx.columnImageEmbedFallback = new HashMap<>();

        Object cellsObj = templateContent.get("cells");
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
            if (!isMediaType(t)) continue;

            ctx.explicitTypes.put(key, t);
            // 同列兜底：保留先出现的媒体类型
            ctx.columnTypeFallback.putIfAbsent(col, t);
            // 仅图片类型读取「导出嵌入单元格」；缺省为 true（与前端一致）
            if ("image".equals(t)) {
                boolean embed = parseEmbedImageInCellFlag(cellObj);
                ctx.imageEmbedByCell.put(key, embed);
                ctx.columnImageEmbedFallback.putIfAbsent(col, embed);
            }
        }
        return ctx;
    }

    /**
     * 解析模板 JSON 中单元格的 {@code embedImageInCell} 字段。
     *
     * @param cellObj 单元格对象 Map
     * @return true 表示导出时使用 MOVE_AND_RESIZE；false 为 MOVE_DONT_RESIZE
     */
    private boolean parseEmbedImageInCellFlag(Map<?, ?> cellObj) {
        Object embedObj = cellObj.get("embedImageInCell");
        if (embedObj instanceof Boolean b) {
            return b;
        }
        if (embedObj instanceof Number n) {
            return n.intValue() != 0;
        }
        if (embedObj instanceof String s) {
            return !"false".equalsIgnoreCase(s.trim()) && !"0".equals(s.trim());
        }
        return true;
    }

    /**
     * 解析当前格导出图片时是否「嵌入单元格」锚定。
     *
     * <p>条形码、二维码始终返回 true（保持与历史行为一致的缩放锚点）。</p>
     *
     * @param ctx       模板媒体上下文
     * @param row       0-based 行
     * @param col       0-based 列
     * @param mediaType 已解析的媒体类型
     * @return 是否使用 MOVE_AND_RESIZE
     */
    private boolean resolveEmbedImageInCell(MediaTypeContext ctx, int row, int col, String mediaType) {
        if (!"image".equals(mediaType)) {
            return true;
        }
        CellKey key = new CellKey(row, col);
        Boolean explicit = ctx.imageEmbedByCell.get(key);
        if (explicit != null) {
            return explicit;
        }
        // 数据展开行：按列上首个图片列配置的 embed 兜底
        if (!ctx.templateExists.contains(key)) {
            Boolean colFallback = ctx.columnImageEmbedFallback.get(col);
            if (colFallback != null) {
                return colFallback;
            }
        }
        return true;
    }

    /**
     * 构建合并区域索引：左上角 -> 区域、覆盖区集合。
     */
    private MergePlacementContext buildMergePlacementContext(List<ReportRenderResponse.MergeRegion> merges) {
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

    /**
     * 根据位置解析媒体类型：
     * 1) 显式类型优先；
     * 2) 模板原始格子但无显式类型，不兜底；
     * 3) 非模板原始格子（通常数据展开新增）允许按列兜底。
     */
    private String resolveMediaType(MediaTypeContext ctx, int row, int col) {
        CellKey key = new CellKey(row, col);
        String explicit = ctx.explicitTypes.get(key);
        if (explicit != null) return explicit;
        if (ctx.templateExists.contains(key)) return null;
        return ctx.columnTypeFallback.get(col);
    }

    /**
     * 解析媒体数据（图片字节 + POI 图片类型），失败返回 empty。
     */
    private Optional<MediaBinary> resolveMediaBinary(String mediaType, String value) {
        try {
            return switch (mediaType) {
                case "image" -> resolveImageBinary(value);
                case "qrcode" -> Optional.of(generateQrcodeBinary(value));
                case "barcode" -> Optional.of(generateBarcodeBinary(value));
                default -> Optional.empty();
            };
        } catch (Exception e) {
            log.warn("媒体生成失败，已降级为文本: type={}, value={}, error={}", mediaType, shorten(value), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析 image 类型：支持 dataURL 与 http/https 远程地址。
     */
    private Optional<MediaBinary> resolveImageBinary(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return Optional.empty();

        Matcher dataUrl = DATA_URL_PATTERN.matcher(v);
        if (dataUrl.matches()) {
            if (v.length() > MAX_DATA_URL_CHARS) {
                throw new IllegalArgumentException("dataURL 过大");
            }
            String mime = dataUrl.group(1).toLowerCase(Locale.ROOT);
            String b64 = dataUrl.group(2);
            byte[] bytes = Base64.getDecoder().decode(b64);
            int type = pictureTypeByMime(mime);
            if (type < 0) {
                throw new IllegalArgumentException("不支持的 dataURL 图片类型: " + mime);
            }
            return Optional.of(new MediaBinary(bytes, type));
        }

        URI uri = URI.create(v);
        String scheme = lower(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return Optional.empty();
        }
        validateRemoteHost(uri);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(200L, callbackProperties.getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(500L, callbackProperties.getReadTimeoutMs())))
                .GET()
                .header("Accept", "image/*,*/*;q=0.1")
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("下载图片失败，HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IllegalStateException("图片响应为空");
            }
            if (body.length > MAX_REMOTE_IMAGE_BYTES) {
                throw new IllegalStateException("图片体积超限");
            }
            String ct = response.headers().firstValue("Content-Type").orElse("");
            int pictureType = pictureTypeByMime(ct);
            if (pictureType < 0) {
                pictureType = sniffPictureType(body);
            }
            if (pictureType < 0) {
                throw new IllegalStateException("不支持的图片格式");
            }
            return Optional.of(new MediaBinary(body, pictureType));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("下载图片失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成二维码 PNG（二期可增加尺寸配置映射）。
     */
    private MediaBinary generateQrcodeBinary(String value) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(
                value,
                BarcodeFormat.QR_CODE,
                220,
                220
        );
        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
        byte[] bytes = bufferedImageToPng(image);
        return new MediaBinary(bytes, Workbook.PICTURE_TYPE_PNG);
    }

    /**
     * 生成条形码 PNG（Code128）。
     */
    private MediaBinary generateBarcodeBinary(String value) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(
                value,
                BarcodeFormat.CODE_128,
                360,
                120
        );
        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
        byte[] bytes = bufferedImageToPng(image);
        return new MediaBinary(bytes, Workbook.PICTURE_TYPE_PNG);
    }

    /**
     * 在指定单元格（或合并区域）中插入图片。
     */
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
        // 将绘图锚点收缩为合并区/单格内的比例区域并居中，避免图贴满格遮挡边框
        applyPictureAnchorInsetWithinCells(anchor, sheet, row, col, lastRow, lastCol, MEDIA_ANCHOR_FILL_FRACTION);
        // 嵌入单元格：随锚定区域移动并缩放；否则仍随单元格移动但不随单元格缩放
        anchor.setAnchorType(
                embedInCell ? ClientAnchor.AnchorType.MOVE_AND_RESIZE : ClientAnchor.AnchorType.MOVE_DONT_RESIZE
        );
        Picture picture = drawing.createPicture(anchor, pictureIndex);
        if (picture == null) {
            throw new IllegalStateException("插入图片失败");
        }
    }

    /**
     * 按单元格/合并区总宽高设置 {@link XSSFClientAnchor} 的 dx/dy，使图片绘制区域为居中矩形、边长为总面积的 {@code fillFraction}。
     *
     * <p>Excel 两格锚点：从 (col1,row1,dx1,dy1) 到 (col2,row2,dx2,dy2)，第二角落在右下格子坐标系下，
     * 使用负 dx2/dy2 实现相对左上的内缩；详见 OOXML 两单元格锚点定义。</p>
     *
     * @param anchor     锚点（需为 {@link XSSFClientAnchor}，xlsx 导出恒满足）
     * @param sheet      工作表
     * @param firstRow   合并区/单格首行（0-based）
     * @param firstCol   首列（0-based）
     * @param lastRow    末行（0-based，含）
     * @param lastCol    末列（0-based，含）
     * @param fillFraction 宽高占用比例，如 0.75
     */
    private void applyPictureAnchorInsetWithinCells(
            ClientAnchor anchor,
            Sheet sheet,
            int firstRow,
            int firstCol,
            int lastRow,
            int lastCol,
            double fillFraction
    ) {
        if (!(anchor instanceof XSSFClientAnchor xssf) || !(sheet instanceof XSSFSheet xsh)) {
            return;
        }
        if (fillFraction <= 0 || fillFraction >= 1) {
            return;
        }
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
            if (rowObj != null) {
                pt = rowObj.getHeightInPoints();
            }
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

    /** Excel 96dpi 下：point → 像素 */
    private static int pointsToPx(float points) {
        return Math.max(1, (int) Math.round(points * 96.0d / 72.0d));
    }

    /** OOXML：dx/dy 使用 EMU，914400 EMU = 1 inch，按 96dpi 换算像素 */
    private static int pxToEmu(int px) {
        return Math.max(1, (int) Math.round(px * 914400.0d / 96.0d));
    }

    /**
     * 应用 Sheet 级别配置：列宽、冻结行等。
     */
    private void applySheetMeta(Sheet sheet, GridMetaExportConfig cfg) {
        for (int c = 0; c < Math.max(1, cfg.colCount); c++) {
            int px = cfg.colWidths.getOrDefault(c, cfg.defaultColWidthPx);
            sheet.setColumnWidth(c, pxToExcelColumnWidth(px));
        }
        if (cfg.freezeHeaderRows > 0) {
            sheet.createFreezePane(0, cfg.freezeHeaderRows);
        }
    }

    /**
     * 构建 POI CellStyle（覆盖设计器常用样式子集）。
     */
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
        if (fontColor != null && font instanceof org.apache.poi.xssf.usermodel.XSSFFont xssfFont) {
            xssfFont.setColor(fontColor);
        }
        String family = cssStyle.get("fontFamily");
        if (family != null && !family.isBlank()) {
            font.setFontName(family);
        }
        String fontSize = cssStyle.get("fontSize");
        Short pt = parseFontSizePt(fontSize);
        if (pt != null && pt > 0) {
            font.setFontHeightInPoints(pt);
        }
        String weight = lower(cssStyle.get("fontWeight"));
        if ("bold".equals(weight) || "700".equals(weight) || "800".equals(weight) || "900".equals(weight)) {
            font.setBold(true);
        }
        String italic = lower(cssStyle.get("fontStyle"));
        if ("italic".equals(italic)) {
            font.setItalic(true);
        }
        style.setFont(font);
        return style;
    }

    /**
     * 样式缓存键（按 key 排序）。
     */
    private String canonicalStyleKey(Map<String, String> cssStyle) {
        List<String> keys = new ArrayList<>(cssStyle.keySet());
        keys.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append(k).append('=').append(cssStyle.get(k)).append(';');
        }
        return sb.toString();
    }

    /**
     * 解析 CSS 颜色：#RGB / #RRGGBB / rgb(r,g,b)。
     */
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
            // 忽略非法颜色
        }
        return null;
    }

    /**
     * 解析 CSS 字号（12px/10pt）为 Excel point。
     */
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

    /**
     * 是否为媒体类型。
     */
    private boolean isMediaType(String t) {
        return "image".equals(t) || "qrcode".equals(t) || "barcode".equals(t);
    }

    /**
     * 是否存在可绘制边框。
     */
    private boolean hasBorder(String cssBorder) {
        if (cssBorder == null) return false;
        String s = cssBorder.trim().toLowerCase(Locale.ROOT);
        return !s.isEmpty() && !"none".equals(s);
    }

    /**
     * 将列字母（A/B/.../AA）转为 0-based 索引。
     */
    private int colLettersToIndex(String colId) {
        if (colId == null || colId.isBlank()) return -1;
        String s = colId.trim().toUpperCase(Locale.ROOT);
        int result = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 'A' || ch > 'Z') return -1;
            result = result * 26 + (ch - 'A' + 1);
        }
        return result - 1;
    }

    /**
     * 校验远程图片 host（白名单 + 私网限制）。
     */
    private void validateRemoteHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("图片 URL 缺少 host");
        }
        if (!isHostAllowed(host)) {
            throw new IllegalArgumentException("图片 URL host 未通过白名单");
        }
        if (callbackProperties.isDenyPrivateHosts()) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                    throw new IllegalArgumentException("图片 URL 为内网/回环地址");
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("图片 URL host 解析失败: " + host, e);
            }
        }
    }

    /**
     * host 白名单匹配（与水印回调一致：精确或 *. 后缀）。
     */
    private boolean isHostAllowed(String host) {
        List<String> patterns = callbackProperties.getAllowedHostPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) continue;
            String p = pattern.trim().toLowerCase(Locale.ROOT);
            if (p.startsWith("*.")) {
                if (lower.endsWith(p.substring(1)) || lower.equals(p.substring(2))) {
                    return true;
                }
            } else if (lower.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * MIME 转 POI 图片类型。
     */
    private int pictureTypeByMime(String mime) {
        String m = lower(mime);
        if (m.contains("png")) return Workbook.PICTURE_TYPE_PNG;
        if (m.contains("jpeg") || m.contains("jpg")) return Workbook.PICTURE_TYPE_JPEG;
        // GIF 在当前实现中不做内嵌（避免图片类型常量不匹配），降级文本
        if (m.contains("gif")) return -1;
        if (m.contains("bmp")) return Workbook.PICTURE_TYPE_DIB;
        return -1;
    }

    /**
     * 按文件头粗略探测图片类型（PNG/JPEG/GIF/BMP）。
     */
    private int sniffPictureType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return -1;
        // PNG
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return Workbook.PICTURE_TYPE_PNG;
        }
        // JPEG
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return Workbook.PICTURE_TYPE_JPEG;
        }
        // GIF：当前版本降级为文本，不做内嵌
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return -1;
        }
        // BMP
        if (bytes[0] == 'B' && bytes[1] == 'M') {
            return Workbook.PICTURE_TYPE_DIB;
        }
        return -1;
    }

    /**
     * BufferedImage 转 PNG 字节。
     */
    private byte[] bufferedImageToPng(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
    }

    /**
     * 文本裁剪用于日志，避免刷屏。
     */
    private String shorten(String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }

    private String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 像素转 Excel 列宽单位（1/256 字符宽）。
     */
    private int pxToExcelColumnWidth(int px) {
        int safePx = Math.max(20, px);
        return Math.min(255 * 256, Math.max(256, (int) Math.round(safePx * 256.0 / 7.0)));
    }

    /**
     * 像素转 Row#setHeight twips（1/20 point）。
     */
    private short pxToPointTwips(int px) {
        int safePx = Math.max(1, px);
        int twips = Math.round(safePx * 15f);
        return (short) Math.max(20, Math.min(Short.MAX_VALUE, twips));
    }

    /**
     * 导出结果。
     */
    public record ExportResult(String contentType, String filename, byte[] body) {}

    /**
     * 单元格坐标键。
     */
    private record CellKey(int row, int col) {}

    /**
     * gridMeta 导出配置。
     */
    private static class GridMetaExportConfig {
        int colCount;
        int defaultColWidthPx;
        int defaultRowHeightPx;
        int freezeHeaderRows;
        Map<Integer, Integer> colWidths;
    }

    /**
     * 模板媒体类型上下文。
     */
    private static class MediaTypeContext {
        Map<CellKey, String> explicitTypes;
        Set<CellKey> templateExists;
        Map<Integer, String> columnTypeFallback;
        /** type=image 时：是否导出为「嵌入单元格」缩放锚点 */
        Map<CellKey, Boolean> imageEmbedByCell;
        /** 同列首张图片格的 embed 配置，供数据展开行兜底 */
        Map<Integer, Boolean> columnImageEmbedFallback;
    }

    /**
     * 合并区放置上下文。
     */
    private static class MergePlacementContext {
        List<CellRangeAddress> regions;
        Map<CellKey, CellRangeAddress> mergeStartRegion;
        Set<CellKey> coveredCells;
    }

    /**
     * 媒体二进制与类型。
     */
    private record MediaBinary(byte[] bytes, int pictureType) {}
}
