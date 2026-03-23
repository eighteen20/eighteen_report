package cn.com._1820.eighteen_report.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 管理员预配置的数据源连接（JDBC 或 API）。
 */
@Entity
@Table(name = "data_source_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSourceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** 数据源显示名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** JDBC 或 API */
    @Column(nullable = false, length = 10)
    private String type;

    /** JDBC URL 或 API 基地址 */
    @Column(nullable = false, length = 500)
    private String url;

    /** 数据库用户名（JDBC 类型时使用） */
    @Column(length = 100)
    private String username;

    /** 数据库密码（JDBC 类型时使用） */
    @Column(length = 200)
    private String password;

    /** JDBC 驱动类名，如 com.mysql.cj.jdbc.Driver */
    @Column(name = "driver_class", length = 200)
    private String driverClass;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 最后更新时间 */
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
