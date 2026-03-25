package cn.com._1820.eighteen_report.service.export.pdf;

import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.events.Event;
import com.itextpdf.kernel.events.IEventHandler;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PDF 水印渲染器：以“每页事件”的方式绘制斜向平铺文字水印。
 *
 * <p>说明：前端使用 SVG pattern 平铺，这里用固定 tile 尺寸 + 旋转文字矩阵近似复现。</p>
 */
@Slf4j
@Component
public class PdfWatermarkRenderer {

    public void installPerPageWatermark(PdfDocument pdfDocument, String watermarkText, PdfFont font) {
        if (pdfDocument == null) return;
        if (watermarkText == null || watermarkText.isBlank()) return;
        if (font == null) return;

        final float watermarkOpacity = 0.12f; // 对齐前端 rgba(...,0.12)
        final float watermarkFontSizePt = 12f; // 16px * 0.75（96dpi -> 72pt）
        final float angleRad = (float) Math.toRadians(-30);
        final PdfExtGState extGState = new PdfExtGState().setFillOpacity(watermarkOpacity);

        pdfDocument.addEventHandler(PdfDocumentEvent.START_PAGE, new IEventHandler() {
            @Override
            public void handleEvent(Event event) {
                try {
                    PdfDocumentEvent pdfEvent = (PdfDocumentEvent) event;
                    var page = pdfEvent.getPage();
                    PdfCanvas canvas = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDocument);
                    canvas.saveState();
                    canvas.setFillColor(new DeviceRgb(17, 24, 39));
                    canvas.setExtGState(extGState);
                    canvas.beginText();
                    canvas.setFontAndSize(font, watermarkFontSizePt);

                    // 与前端 watermarkDensity=1 时的 SVG pattern 尺寸对齐（仅用于近似平铺节奏）
                    float tileWPx = 360f;
                    float tileHPx = 240f;
                    float tileWPt = tileWPx * 72f / 96f;
                    float tileHPt = tileHPx * 72f / 96f;

                    float cos = (float) Math.cos(angleRad);
                    float sin = (float) Math.sin(angleRad);

                    float pageW = page.getPageSize().getWidth();
                    float pageH = page.getPageSize().getHeight();

                    for (float x = -tileWPt; x < pageW + tileWPt; x += tileWPt) {
                        for (float y = -tileHPt; y < pageH + tileHPt; y += tileHPt) {
                            float cx = x + tileWPt / 2f;
                            float cy = y + tileHPt / 2f;
                            canvas.setTextMatrix(cos, sin, -sin, cos, cx, cy);
                            canvas.showText(watermarkText);
                        }
                    }

                    canvas.endText();
                    canvas.restoreState();
                } catch (Exception e) {
                    // 水印失败时不影响主内容导出可用性
                    log.debug("PDF 水印绘制失败: {}", e.getMessage());
                }
            }
        });
    }
}

