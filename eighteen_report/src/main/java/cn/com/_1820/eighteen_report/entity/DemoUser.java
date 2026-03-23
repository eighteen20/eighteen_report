package cn.com._1820.eighteen_report.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

/**
 * 演示用户实体，用于测试报表数据源查询功能。包含姓名、部门、薪资等常用字段。
 */
@Entity
@Table(name = "demo_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DemoUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户姓名 */
    @Column(nullable = false, length = 50)
    private String name;

    /** 性别（男/女） */
    @Column(length = 10)
    private String gender;

    /** 年龄 */
    private Integer age;

    /** 所属部门 */
    @Column(length = 100)
    private String department;

    /** 职位 */
    @Column(length = 80)
    private String position;

    /** 薪资（元） */
    @Column(precision = 12, scale = 2)
    private BigDecimal salary;

    /** 邮箱地址 */
    @Column(length = 150)
    private String email;

    /** 联系电话 */
    @Column(length = 20)
    private String phone;

    /** 入职日期 */
    @Column(name = "hire_date")
    private LocalDate hireDate;

    /** 状态（在职/离职） */
    @Column(length = 20)
    private String status;

    /** 记录创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
