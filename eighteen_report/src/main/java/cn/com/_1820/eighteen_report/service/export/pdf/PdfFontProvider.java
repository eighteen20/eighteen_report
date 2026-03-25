package cn.com._1820.eighteen_report.service.export.pdf;

import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PDF 字体提供器：负责加载中文字体并提供 regular/bold 字体实例。
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>优先使用 classpath 中的 NotoSansCJKsc OTF（可嵌入，跨平台稳定）；</li>
 *   <li>bold OTF 失败时回退 regular OTF，避免回退 Helvetica 导致中文方块；</li>
 *   <li>最后兜底 TTC（STHeiti），并基于 containsGlyph 选择合适 face/encoding；</li>
 *   <li>最终仍失败时：regular 回退 Helvetica（保证导出不中断），bold 返回 null（避免中文方块）。</li>
 * </ul>
 */
@Slf4j
@Component
public class PdfFontProvider {

    public PdfFont regular() {
        return loadNotoOrFallback("fonts/NotoSansCJKsc-Regular.otf");
    }

    public PdfFont bold() {
        return loadNotoOrFallback("fonts/NotoSansCJKsc-Bold.otf");
    }

    /**
     * 加载 NotoSansCJK 字体：按 classpath 路径读取并嵌入；失败则回退标准字体（中文可能显示方块）。
     *
     * @param classpathResource 类路径资源，如 {@code /fonts/NotoSansCJKsc-Regular.otf}
     */
    private PdfFont loadNotoOrFallback(String classpathResource) {
        boolean wantBold = classpathResource != null && classpathResource.toLowerCase(Locale.ROOT).contains("bold");

        // 1) 优先加载 Noto OTF
        try {
            PdfFont f = loadOtfFromClasspath(classpathResource);
            if (f != null) {
                log.warn("PDF 中文字体加载：使用 Noto OTF: {}", classpathResource);
                return f;
            }
        } catch (Exception e) {
            log.warn("加载 Noto OTF 失败: resource={}, error={}", classpathResource, e.getMessage());
        }

        // 2) bold 加载失败时，用 regular OTF 兜底（避免 Helvetica 导致方块字）
        if (wantBold) {
            try {
                PdfFont f = loadOtfFromClasspath("fonts/NotoSansCJKsc-Regular.otf");
                if (f != null) {
                    log.warn("PDF 中文字体加载：bold Noto OTF 失败，回退到 Noto regular");
                    return f;
                }
            } catch (Exception e) {
                log.warn("bold Noto regular 回退失败: error={}", e.getMessage());
            }
            // bold OTF + regular OTF 都失败时，不再回退 Helvetica（避免方块字）
            return null;
        }

        // 3) Noto OTF 都失败，最后尝试 TTC
        try {
            PdfFont ttcFont = loadTtcFontOrNull("fonts/STHeiti-Light.ttc", 0);
            if (ttcFont != null) {
                log.warn("PDF 中文字体加载：使用 TTC兜底");
                return ttcFont;
            }
        } catch (Exception ignored) {
            // ignore
        }

        // 4) 最终兜底：regular 回退 Helvetica
        try {
            PdfFont f = PdfFontFactory.createFont(StandardFonts.HELVETICA, PdfEncodings.WINANSI);
            log.warn("PDF 中文字体加载：回退 Helvetica（可能不支持中文，但避免导出中断）");
            return f;
        } catch (Exception ignored) {
            return null;
        }
    }

    private PdfFont loadOtfFromClasspath(String classpathResource) throws Exception {
        if (classpathResource == null || classpathResource.isBlank()) return null;
        String p1 = classpathResource;
        String p2 = classpathResource.startsWith("/") ? classpathResource.substring(1) : "/" + classpathResource;

        java.io.InputStream is = getClass().getResourceAsStream(p1);
        if (is == null) is = getClass().getResourceAsStream(p2);
        if (is == null) is = Thread.currentThread().getContextClassLoader().getResourceAsStream(p1);
        if (is == null) is = Thread.currentThread().getContextClassLoader().getResourceAsStream(p2);
        if (is == null) return null;

        try (java.io.InputStream in = is) {
            byte[] bytes = in.readAllBytes();
            return PdfFontFactory.createFont(
                    bytes,
                    PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED,
                    true
            );
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

            // 创建候选字体后用 containsGlyph() 检测关键中文字符是否可用，选择得分最高的那个返回。
            String test = "用户中文";
            float bestScore = -1;
            PdfFont bestFont = null;

            String[] encodings = new String[]{PdfEncodings.IDENTITY_H, PdfEncodings.IDENTITY_V};
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
}

