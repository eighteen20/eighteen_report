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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            throw new UnsupportedOperationException("PDF 导出尚未实现");
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
