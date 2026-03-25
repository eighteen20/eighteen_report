package cn.com._1820.eighteen_report.service.export;

/**
 * 导出结果：以字节流形式返回给前端下载。
 *
 * <p>注意：Controller 会将 {@code contentType} 写入响应头，并以 {@code filename} 作为附件文件名。</p>
 *
 * @param contentType MIME 类型（如 xlsx / pdf）
 * @param filename    下载文件名（建议包含扩展名）
 * @param body        文件二进制内容
 */
public record ExportResult(String contentType, String filename, byte[] body) {}

