package cn.com._1820.eighteen_report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态水印服务端回调相关配置（防 SSRF、超时与响应大小限制）。
 *
 * <p>业务系统在模板 {@code gridMeta.watermarkCallbackUrl} 中配置回调基地址；
 * 渲染时由本服务发起 HTTP GET，将 {@code templateId} 与运行时 {@code params}（去除 {@code watermark}）
 * 作为查询参数透传，由业务系统返回水印文案。</p>
 */
@Data
@ConfigurationProperties(prefix = WatermarkCallbackProperties.PREFIX)
public class WatermarkCallbackProperties {

    public static final String PREFIX = "eighteen.report.watermark-callback";

    /**
     * 是否启用水印回调（关闭后即使模板配置了 URL 也不会请求外部地址）
     */
    private boolean enabled = true;

    /**
     * 建立 TCP 连接超时（毫秒）
     */
    private int connectTimeoutMs = 3000;

    /**
     * 读取响应完整报文超时（毫秒）
     */
    private int readTimeoutMs = 8000;

    /**
     * 允许读取的响应体最大字符数（防止大响应拖垮内存）
     */
    private int maxResponseBodyChars = 512;

    /**
     * 允许 Callback URL 的 host 白名单：精确匹配，或以 {@code *.} 开头表示后缀匹配（见 {@link WatermarkCallbackService}）。
     * 为空表示不校验主机名（仅适合开发环境；生产务必配置，减轻 SSRF 风险）。
     */
    private List<String> allowedHostPatterns = new ArrayList<>();

    /**
     * 为 true 时：若解析到的目标 IP 为回环、链路本地、站点本地等，则拒绝请求（减轻内网探测 SSRF）
     */
    private boolean denyPrivateHosts = false;
}
