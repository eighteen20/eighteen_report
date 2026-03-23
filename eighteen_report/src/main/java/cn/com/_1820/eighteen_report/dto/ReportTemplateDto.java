package cn.com._1820.eighteen_report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 报表模板传输对象，包含模板名称、描述及 JSON 格式的完整设计内容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportTemplateDto {

    private String id;
    private String name;
    private String description;
    /** 模板内容 JSON：{ datasets, cells, gridMeta } */
    private String content;
    private Instant createdAt;
    private Instant updatedAt;
}
