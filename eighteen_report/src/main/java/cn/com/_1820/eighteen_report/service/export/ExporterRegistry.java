package cn.com._1820.eighteen_report.service.export;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 导出器注册表：从 Spring 容器中收集所有 {@link ReportExporter}，按 format 路由到匹配实现。
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>开放封闭：新增导出格式仅需新增实现类，不修改门面逻辑；</li>
 *   <li>单一职责：注册表只做“选择 exporter”的决策，不关心导出细节。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ExporterRegistry {

    private final List<ReportExporter> exporters;

    /**
     * 获取与 format 匹配的 exporter。
     *
     * @param format 可能为 null/大小写混合
     * @return 匹配的 exporter
     * @throws IllegalArgumentException 当 format 不被任何 exporter 支持
     */
    public ReportExporter get(String format) {
        String f = format != null ? format.toLowerCase(Locale.ROOT) : "";
        for (ReportExporter e : exporters) {
            if (e.supports(f)) return e;
        }
        throw new IllegalArgumentException("不支持的导出格式: " + format);
    }
}

