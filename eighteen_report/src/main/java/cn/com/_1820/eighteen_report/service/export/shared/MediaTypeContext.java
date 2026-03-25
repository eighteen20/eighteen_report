package cn.com._1820.eighteen_report.service.export.shared;

import java.util.Map;
import java.util.Set;

/**
 * 单元格媒体类型上下文：用于将模板中的单元格类型（image/qrcode/barcode）映射到渲染矩阵坐标。
 *
 * <p>关键点：</p>
 * <ul>
 *   <li>渲染可能“展开行”，导致后续行并不在模板 cells 中存在；</li>
 *   <li>对非模板原始单元格：按列做类型兜底，保证展开行也能渲染媒体。</li>
 * </ul>
 */
public class MediaTypeContext {

    /** 模板中显式声明的 cell type（key=坐标） */
    public Map<CellKey, String> explicitTypes;
    /** 模板中存在的 cell 坐标集合（用于判断是否需要列兜底） */
    public Set<CellKey> templateExists;
    /** 列级兜底媒体类型（key=col） */
    public Map<Integer, String> columnTypeFallback;

    /** image 的“导出嵌入单元格”显式配置（key=坐标） */
    public Map<CellKey, Boolean> imageEmbedByCell;
    /** image 的“导出嵌入单元格”列级兜底（key=col） */
    public Map<Integer, Boolean> columnImageEmbedFallback;
}

