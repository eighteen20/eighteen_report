package cn.com._1820.eighteen_report.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 报表模板实体。datasets、cells、gridMeta 等全部存于 content JSON。
 */
@Entity
@Table(name = "report_template")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * 模板内容 JSON，存储报表设计的全部配置：{ datasets: 数据集定义, cells: 单元格值与样式, merges: 合并区域, gridMeta: 网格元数据 }
     */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
