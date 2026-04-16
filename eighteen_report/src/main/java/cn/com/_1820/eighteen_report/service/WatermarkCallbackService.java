package cn.com._1820.eighteen_report.service;

import cn.com._1820.eighteen_report.config.WatermarkCallbackProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动态水印服务端拉取服务。
 *
 * <p>在报表渲染流程中，若模板配置了 {@code watermarkCallbackUrl}，则由本服务对该 URL 发起 GET 请求，
 * 将 {@code templateId} 与业务传入的运行时参数（查询字符串等，去掉 {@code watermark}）一并带给业务系统，
 * 业务按约定格式返回水印文本。浏览器无法直接伪造最终水印（已配置回调时渲染服务会忽略请求体中的
 * {@code watermark} 字段）。</p>
 *
 * <p><b>业务回调约定：</b></p>
 * <ul>
 *   <li>HTTP GET，200 且响应体为 JSON：{@code {"watermark":"展示文案"}}，仅读取 {@code watermark} 字符串；</li>
 *   <li>或 200 且 {@code Content-Type} 含 {@code text/plain}：整段 body 作为水印（去除首尾空白，长度受配置限制）。</li>
 * </ul>
 *
 * <p>任意网络错误、超时、非 2xx、解析失败均记录日志并返回空，不抛出到上层，保证报表渲染主流程成功。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatermarkCallbackService {

    private final WatermarkCallbackProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 请求业务回调地址，解析得到水印文案。
     *
     * @param callbackUrl 模板中配置的完整 URL（可自带 query）
     * @param templateId  当前报表模板 ID
     * @param params      渲染请求中的运行时参数（会作为 query 追加，排除 {@code watermark}）
     * @return 非空且去空白后的水印字符串；失败时为 empty
     */
    public Optional<String> fetchWatermark(String callbackUrl, String templateId, Map<String, Object> params) {
        if (!properties.isEnabled()) {
            log.debug("水印回调功能已关闭（eighteen.report.watermark-callback.enabled=false）");
            return Optional.empty();
        }
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Optional.empty();
        }
        String trimmed = callbackUrl.trim();
        URI uri;
        try {
            uri = buildRequestUri(trimmed, templateId, params != null ? params : Collections.emptyMap());
        } catch (Exception e) {
            log.warn("水印回调 URL 构造失败，已跳过: {}", e.getMessage());
            return Optional.empty();
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            log.warn("水印回调仅允许 http/https 协议，已跳过: {}", uri.getScheme());
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            log.warn("水印回调 URL 缺少 host，已跳过");
            return Optional.empty();
        }
        if (!isHostAllowed(host)) {
            log.warn("水印回调 host 未通过白名单校验，已跳过: {}（请配置 {}.allowed-host-patterns）",
                    host, WatermarkCallbackProperties.PREFIX);
            return Optional.empty();
        }
        if (properties.isDenyPrivateHosts()) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                    log.warn("水印回调目标为内网/回环地址，deny-private-hosts=true 已拒绝: host={} -> {}", host, addr.getHostAddress());
                    return Optional.empty();
                }
            } catch (Exception e) {
                log.warn("水印回调 host 解析失败，已跳过: {} -> {}", host, e.getMessage());
                return Optional.empty();
            }
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(100L, properties.getReadTimeoutMs())))
                .GET()
                .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.1")
                .build();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(100L, properties.getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("水印回调 HTTP 状态非成功: status={}, uri={}", response.statusCode(), uri);
                return Optional.empty();
            }
            String body = truncate(response.body(), properties.getMaxResponseBodyChars());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            Optional<String> parsed = parseBody(body, contentType);
            if (parsed.isEmpty()) {
                log.debug("水印回调响应中无有效 watermark 字段，uri={}", uri);
            }
            return parsed;
        } catch (Exception e) {
            log.warn("水印回调请求失败（不影响报表渲染）: uri={}, error={}", uri, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 在白名单非空时校验 host：支持精确匹配，或以 {@code *.} 前缀表示后缀匹配（如 {@code *.corp.example.com}）。
     */
    private boolean isHostAllowed(String host) {
        List<String> patterns = properties.getAllowedHostPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        String lower = host.toLowerCase();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String p = pattern.trim().toLowerCase();
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
     * 将模板配置的 URL 与 templateId、运行时参数合并为最终 GET URI。
     */
    private URI buildRequestUri(String callbackUrl, String templateId, Map<String, Object> params) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(callbackUrl);
        b.queryParam("templateId", templateId);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            // 客户端传入的 watermark 不再转发给业务（且渲染侧在配置回调时已忽略该字段）
            if ("watermark".equalsIgnoreCase(key)) {
                continue;
            }
            Object val = e.getValue();
            if (val == null) {
                continue;
            }
            if (val instanceof Iterable<?> it) {
                for (Object o : it) {
                    if (o != null) {
                        b.queryParam(key, o);
                    }
                }
            } else if (val.getClass().isArray()) {
                Object[] arr = (Object[]) val;
                for (Object o : arr) {
                    if (o != null) {
                        b.queryParam(key, o);
                    }
                }
            } else {
                b.queryParam(key, val);
            }
        }
        return b.build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
    }

    private Optional<String> parseBody(String body, String contentType) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        String t = body.trim();
        if (contentType.toLowerCase().contains("json") || (t.startsWith("{") && t.endsWith("}"))) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.readValue(t, Map.class);
                Object wm = map != null ? map.get("watermark") : null;
                if (wm == null) {
                    return Optional.empty();
                }
                String s = wm.toString().trim();
                return s.isEmpty() ? Optional.empty() : Optional.of(s);
            } catch (Exception e) {
                log.debug("水印回调 JSON 解析失败，尝试纯文本: {}", e.getMessage());
            }
        }
        if (t.length() > properties.getMaxResponseBodyChars()) {
            t = t.substring(0, properties.getMaxResponseBodyChars());
        }
        return t.isEmpty() ? Optional.empty() : Optional.of(t);
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (maxChars <= 0 || s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars);
    }
}
