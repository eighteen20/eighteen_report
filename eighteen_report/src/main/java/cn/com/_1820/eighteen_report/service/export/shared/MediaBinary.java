package cn.com._1820.eighteen_report.service.export.shared;

/**
 * 媒体二进制（图片/二维码/条形码）及其 POI pictureType。
 *
 * @param bytes       图片二进制
 * @param pictureType POI pictureType（PNG/JPEG/BMP 等）
 */
public record MediaBinary(byte[] bytes, int pictureType) {}

