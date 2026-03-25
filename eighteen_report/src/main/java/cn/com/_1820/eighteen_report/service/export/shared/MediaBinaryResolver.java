package cn.com._1820.eighteen_report.service.export.shared;

import cn.com._1820.eighteen_report.config.WatermarkCallbackProperties;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体二进制解析器：将单元格 value 按 mediaType 转为可插入到导出文件的图片二进制。
 *
 * <p>能力：</p>
 * <ul>
 *   <li>image：支持 http/https URL 与 dataURL（base64）；</li>
 *   <li>qrcode/barcode：使用 ZXing 生成 PNG；</li>
 *   <li>安全：按 {@link WatermarkCallbackProperties} 做 host 白名单与私网拦截（SSRF 防护）。</li>
 * </ul>
 *
 * <p>容错：解析/下载/生成失败时返回 {@link Optional#empty()}，由上层降级为文本。</p>
 */
@Slf4j
@Component
public class MediaBinaryResolver {

    /** data:image/...;base64,... 解析 */
    private static final Pattern DATA_URL_PATTERN = Pattern.compile("^data:(image/[\\w+.-]+);base64,(.+)$", Pattern.CASE_INSENSITIVE);

    /** 远程图片读取大小上限（字节） */
    private static final int MAX_REMOTE_IMAGE_BYTES = 5 * 1024 * 1024;
    /** dataURL 长度上限（字符） */
    private static final int MAX_DATA_URL_CHARS = 8 * 1024 * 1024;

    public Optional<MediaBinary> resolve(WatermarkCallbackProperties callbackProps, String mediaType, String value) {
        try {
            return switch (mediaType) {
                case "image" -> resolveImageBinary(callbackProps, value);
                case "qrcode" -> Optional.of(generateQrcodeBinary(value));
                case "barcode" -> Optional.of(generateBarcodeBinary(value));
                default -> Optional.empty();
            };
        } catch (Exception e) {
            log.warn("媒体解析失败，已降级为文本: type={}, error={}", mediaType, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<MediaBinary> resolveImageBinary(WatermarkCallbackProperties callbackProps, String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return Optional.empty();

        Matcher dataUrl = DATA_URL_PATTERN.matcher(v);
        if (dataUrl.matches()) {
            String mime = dataUrl.group(1);
            String b64 = dataUrl.group(2);
            if (b64.length() > MAX_DATA_URL_CHARS) throw new IllegalStateException("dataURL 过大");
            byte[] bytes = Base64.getDecoder().decode(b64);
            // POI 对 GIF 支持不稳定，统一转 PNG
            if (isGifMime(mime) || isGifBytes(bytes)) {
                bytes = transcodeToPng(bytes);
                mime = "image/png";
            }
            int pictureType = pictureTypeByMime(mime);
            if (pictureType < 0) pictureType = sniffPictureType(bytes);
            if (pictureType < 0) throw new IllegalStateException("不支持的图片格式");
            return Optional.of(new MediaBinary(bytes, pictureType));
        }

        URI uri = URI.create(v);
        validateRemoteHost(callbackProps, uri);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500L, callbackProps.getConnectTimeoutMs())))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(500L, callbackProps.getReadTimeoutMs())))
                .GET()
                .header("Accept", "image/*,*/*;q=0.1")
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("下载图片失败，HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) throw new IllegalStateException("图片响应为空");
            if (body.length > MAX_REMOTE_IMAGE_BYTES) throw new IllegalStateException("图片体积超限");

            String ct = response.headers().firstValue("Content-Type").orElse("");
            if (isGifMime(ct) || isGifBytes(body)) {
                body = transcodeToPng(body);
                ct = "image/png";
            }

            int pictureType = pictureTypeByMime(ct);
            if (pictureType < 0) pictureType = sniffPictureType(body);
            if (pictureType < 0) throw new IllegalStateException("不支持的图片格式");
            return Optional.of(new MediaBinary(body, pictureType));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("下载图片失败: " + e.getMessage(), e);
        }
    }

    private MediaBinary generateQrcodeBinary(String value) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 220, 220);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            return new MediaBinary(bufferedImageToPng(image), Workbook.PICTURE_TYPE_PNG);
        } catch (Exception e) {
            throw new IllegalStateException("二维码生成失败: " + e.getMessage(), e);
        }
    }

    private MediaBinary generateBarcodeBinary(String value) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.CODE_128, 360, 120);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            return new MediaBinary(bufferedImageToPng(image), Workbook.PICTURE_TYPE_PNG);
        } catch (Exception e) {
            throw new IllegalStateException("条形码生成失败: " + e.getMessage(), e);
        }
    }

    private void validateRemoteHost(WatermarkCallbackProperties callbackProps, URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("图片地址 host 为空");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("图片地址仅支持 http/https");
        }
        if (!isHostAllowed(callbackProps, host)) {
            throw new IllegalArgumentException("图片地址 host 不在白名单: " + host);
        }
        if (callbackProps.isDenyPrivateHosts()) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isSiteLocalAddress()) {
                    throw new IllegalArgumentException("禁止访问私网/本地地址: " + host);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("图片地址解析失败: " + host);
            }
        }
    }

    private boolean isHostAllowed(WatermarkCallbackProperties callbackProps, String host) {
        var patterns = callbackProps.getAllowedHostPatterns();
        if (patterns == null || patterns.isEmpty()) return true; // 不配置则放行（与历史逻辑一致）
        for (String p : patterns) {
            if (p == null || p.isBlank()) continue;
            String pp = p.trim();
            if (pp.equals(host)) return true;
            if (pp.startsWith("*.")) {
                String suffix = pp.substring(1); // ".example.com"
                if (host.endsWith(suffix)) return true;
            }
        }
        return false;
    }

    private int pictureTypeByMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("png")) return Workbook.PICTURE_TYPE_PNG;
        if (m.contains("jpeg") || m.contains("jpg")) return Workbook.PICTURE_TYPE_JPEG;
        if (m.contains("bmp")) return Workbook.PICTURE_TYPE_DIB;
        return -1;
    }

    private int sniffPictureType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return -1;
        // PNG
        if (bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return Workbook.PICTURE_TYPE_PNG;
        }
        // JPEG
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return Workbook.PICTURE_TYPE_JPEG;
        }
        // GIF 由上层转码为 PNG
        // BMP
        if (bytes[0] == 0x42 && bytes[1] == 0x4D) {
            return Workbook.PICTURE_TYPE_DIB;
        }
        return -1;
    }

    private boolean isGifMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return m.contains("gif");
    }

    private boolean isGifBytes(byte[] bytes) {
        return bytes != null
                && bytes.length >= 3
                && bytes[0] == 0x47
                && bytes[1] == 0x49
                && bytes[2] == 0x46;
    }

    /**
     * 将输入图片转码为 PNG，规避 POI 对部分格式（如 GIF）的兼容性问题。
     */
    private byte[] transcodeToPng(byte[] input) {
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(input));
            if (img == null) {
                throw new IllegalStateException("图片解码失败，无法转 PNG");
            }
            return bufferedImageToPng(img);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("图片转 PNG 失败: " + e.getMessage(), e);
        }
    }

    private byte[] bufferedImageToPng(BufferedImage image) {
        try {
            var baos = new java.io.ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", baos)) {
                throw new IllegalStateException("PNG 编码失败");
            }
            return baos.toByteArray();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PNG 编码失败: " + e.getMessage(), e);
        }
    }
}

