package cn.com._1820.eighteen_report.service.export.shared;

/**
 * 媒体类型常量与判断工具。
 */
public final class MediaTypes {

    private MediaTypes() {}

    public static boolean isMediaType(String t) {
        return "image".equals(t) || "qrcode".equals(t) || "barcode".equals(t);
    }
}

