package cn.com._1820.eighteen_report.service;

import cn.com._1820.eighteen_report.config.WatermarkCallbackProperties;
import cn.com._1820.eighteen_report.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 报表图片上传中转服务。
 *
 * <p>职责：
 * <ol>
 *   <li>从模板 {@code gridMeta.imageUploadCallbackUrl} 读取业务方的图片接收地址；</li>
 *   <li>对回调地址进行基本安全校验（复用水印回调的 host 白名单 + 私网拒绝策略）；</li>
 *   <li>将前端传来的本地文件流、或从网络拉取的远程图片，以 multipart {@code imgFile} 参数 POST 到业务方；</li>
 *   <li>解析业务方返回：纯字符串 URL 或 JSON {@code {"url":"..."}}，统一返回最终 URL 字符串。</li>
 * </ol>
 *
 * <p>任何配置缺失、安全校验失败、网络异常或业务方响应格式不符，均以带可读描述的 {@link RuntimeException} 抛出。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportImageUploadService {

    /** 转发超时：连接（毫秒） */
    private static final long CONNECT_TIMEOUT_MS = 10_000L;
    /** 转发超时：读取（毫秒） */
    private static final long READ_TIMEOUT_MS = 30_000L;
    /** 响应体最大可读字符数（防止大响应） */
    private static final int MAX_RESPONSE_CHARS = 4096;

    private final ReportTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;
    /** 复用水印回调的安全配置（host 白名单 / 私网拒绝） */
    private final WatermarkCallbackProperties callbackProperties;

    /**
     * 本地图片上传：浏览器 → 报表工具 → 业务方回调。
     *
     * @param templateId 模板主键，用于读取 imageUploadCallbackUrl
     * @param file       用户上传的本地图片文件
     * @return 业务方返回的图片 URL
     */
    public String uploadLocal(String templateId, MultipartFile file) {
        String callbackUrl = readCallbackUrl(templateId);
        validateCallbackUrl(callbackUrl);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("读取上传文件失败: " + e.getMessage(), e);
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "image.jpg";
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        return sendMultipart(callbackUrl, filename, contentType, bytes);
    }

    /**
     * 网络图片上传：报表工具拉取远程 URL → 转发到业务方回调。
     *
     * @param templateId 模板主键，用于读取 imageUploadCallbackUrl
     * @param imageUrl   要拉取的网络图片地址
     * @return 业务方返回的图片 URL
     */
    public String uploadRemote(String templateId, String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new RuntimeException("网络图片地址不能为空");
        }
        // 对拉取地址也进行基本协议校验，防止 SSRF
        URI remoteUri;
        try {
            remoteUri = URI.create(imageUrl.trim());
        } catch (Exception e) {
            throw new RuntimeException("网络图片地址格式无效: " + imageUrl);
        }
        String scheme = remoteUri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new RuntimeException("网络图片地址仅支持 http/https 协议");
        }

        // 拉取远程图片
        byte[] imageBytes = fetchRemoteImage(remoteUri);

        // 从路径猜测文件名
        String path = remoteUri.getPath();
        String filename = (path != null && path.contains("/")) ? path.substring(path.lastIndexOf('/') + 1) : "remote.jpg";
        if (filename.isBlank()) filename = "remote.jpg";
        // 猜测 Content-Type（简单后缀推断）
        String contentType = guessContentType(filename);

        String callbackUrl = readCallbackUrl(templateId);
        validateCallbackUrl(callbackUrl);
        return sendMultipart(callbackUrl, filename, contentType, imageBytes);
    }

    // ============================================================
    // 私有辅助方法
    // ============================================================

    /**
     * 从模板 content JSON 的 gridMeta 中读取 imageUploadCallbackUrl。
     *
     * @param templateId 模板主键
     * @return 回调地址字符串（若未配置则抛出异常）
     */
    @SuppressWarnings("unchecked")
    private String readCallbackUrl(String templateId) {
        var template = templateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("报表模板不存在: " + templateId));
        String content = template.getContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("模板内容为空，无法读取图片上传回调地址");
        }
        try {
            Map<String, Object> contentMap = objectMapper.readValue(content, Map.class);
            Map<String, Object> gridMeta = (Map<String, Object>) contentMap.get("gridMeta");
            if (gridMeta == null) {
                throw new RuntimeException("模板 gridMeta 不存在，请先保存报表配置");
            }
            Object url = gridMeta.get("imageUploadCallbackUrl");
            if (url == null || url.toString().isBlank()) {
                throw new RuntimeException("当前报表模板未配置图片上传回调地址,请在报表设置中配置后重试");
            }
            return url.toString().trim();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析模板内容失败: " + e.getMessage(), e);
        }
    }

    /**
     * 校验回调地址的合法性：协议、host 存在性、白名单、私网拒绝。
     * 复用 {@link WatermarkCallbackProperties} 中的安全策略。
     *
     * @param callbackUrl 待校验的地址
     */
    private void validateCallbackUrl(String callbackUrl) {
        URI uri;
        try {
            uri = URI.create(callbackUrl);
        } catch (Exception e) {
            throw new RuntimeException("图片上传回调地址格式无效: " + callbackUrl);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new RuntimeException("图片上传回调地址仅支持 http/https 协议，当前: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RuntimeException("图片上传回调地址缺少 host");
        }
        // 白名单校验（若配置了白名单才拦截）
        if (!isHostAllowed(host)) {
            throw new RuntimeException("图片上传回调 host 未在白名单中: " + host);
        }
        // 私网拒绝（若启用）
        if (callbackProperties.isDenyPrivateHosts()) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                    throw new RuntimeException("图片上传回调地址指向内网/回环，被安全策略拒绝: " + host);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("图片上传回调 host 解析失败: " + host, e);
            }
        }
    }

    /**
     * 以 multipart/form-data 方式将图片 POST 到业务回调地址，解析并返回图片 URL。
     *
     * @param callbackUrl 业务方接收接口
     * @param filename    文件名（用于 Content-Disposition）
     * @param contentType 文件 MIME 类型
     * @param bytes       文件字节数组
     * @return 业务方返回的图片 URL
     */
    private String sendMultipart(String callbackUrl, String filename, String contentType, byte[] bytes) {
        // 构造 multipart/form-data 请求体（手动拼装，避免引入额外依赖）
        String boundary = "----EighteenReportBoundary" + System.currentTimeMillis();
        String lineSeparator = "\r\n";
        StringBuilder partHeader = new StringBuilder();
        partHeader.append("--").append(boundary).append(lineSeparator);
        partHeader.append("Content-Disposition: form-data; name=\"imgFile\"; filename=\"")
                .append(filename.replace("\"", "_")).append("\"").append(lineSeparator);
        partHeader.append("Content-Type: ").append(contentType).append(lineSeparator);
        partHeader.append(lineSeparator);

        byte[] headerBytes = partHeader.toString().getBytes(StandardCharsets.UTF_8);
        String footerStr = lineSeparator + "--" + boundary + "--" + lineSeparator;
        byte[] footerBytes = footerStr.getBytes(StandardCharsets.UTF_8);

        byte[] body = new byte[headerBytes.length + bytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(bytes, 0, body, headerBytes.length, bytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + bytes.length, footerBytes.length);

        HttpRequest request = HttpRequest.newBuilder(URI.create(callbackUrl))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("图片上传回调请求失败: url={}, error={}", callbackUrl, e.getMessage(), e);
            throw new RuntimeException("调用图片上传回调接口失败: " + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("图片上传回调返回非成功状态码: " + response.statusCode());
        }

        String responseBody = truncate(response.body(), MAX_RESPONSE_CHARS);
        return parseImageUrl(responseBody);
    }

    /**
     * 拉取远程图片的字节流。
     *
     * @param uri 已校验的远程图片 URI
     * @return 图片字节数组
     */
    private byte[] fetchRemoteImage(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .GET()
                .build();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            HttpResponse<InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("拉取网络图片失败，HTTP " + resp.statusCode() + ": " + uri);
            }
            try (InputStream is = resp.body()) {
                return is.readAllBytes();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("拉取网络图片时出错: " + e.getMessage(), e);
        }
    }

    /**
     * 解析业务方返回的响应体，支持两种格式：
     * <ul>
     *   <li>纯字符串 URL，如 {@code https://cdn.example.com/a.jpg}</li>
     *   <li>JSON，如 {@code {"url":"https://cdn.example.com/a.jpg"}}</li>
     * </ul>
     *
     * @param body 响应体字符串
     * @return 图片 URL
     */
    @SuppressWarnings("unchecked")
    private String parseImageUrl(String body) {
        if (body == null) {
            throw new RuntimeException("图片上传回调返回空响应");
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            throw new RuntimeException("图片上传回调返回空响应体");
        }
        // 尝试 JSON 解析
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> map = objectMapper.readValue(trimmed, Map.class);
                Object urlVal = map.get("url");
                if (urlVal != null && !urlVal.toString().isBlank()) {
                    return urlVal.toString().trim();
                }
            } catch (Exception e) {
                log.debug("图片上传回调 JSON 解析失败，改用纯文本: {}", e.getMessage());
            }
        }
        // 兜底：整段 body 视为 URL
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new RuntimeException("图片上传回调返回的内容无法识别为有效 URL: " + truncate(trimmed, 200));
        }
        return trimmed;
    }

    /**
     * 判断 host 是否在白名单中（规则与水印回调相同）。
     * 白名单为空时放行所有 host（仅适合开发环境）。
     */
    private boolean isHostAllowed(String host) {
        var patterns = callbackProperties.getAllowedHostPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        String lower = host.toLowerCase();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) continue;
            String p = pattern.trim().toLowerCase();
            if (p.startsWith("*.")) {
                if (lower.endsWith(p.substring(1)) || lower.equals(p.substring(2))) return true;
            } else if (lower.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** 根据文件后缀推断 MIME 类型 */
    private static String guessContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
