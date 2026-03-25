package cn.com._1820.eighteen_report.service.export;

/**
 * 报表导出器：按指定格式生成文件字节流。
 *
 * <p>扩展方式：</p>
 * <ul>
 *   <li>新增格式（如 csv/json/image）时，实现本接口并注册为 Spring Bean；</li>
 *   <li>由 {@link ExporterRegistry} 根据 format 选择具体实现。</li>
 * </ul>
 */
public interface ReportExporter {

    /**
     * 是否支持指定 format。
     *
     * @param format 小写格式名，如 {@code xlsx}/{@code pdf}
     * @return true 表示当前 exporter 可处理该格式
     */
    boolean supports(String format);

    /**
     * 执行导出。
     *
     * @param ctx 导出上下文
     * @return 导出结果（contentType/filename/body）
     */
    ExportResult export(ExportContext ctx);
}

