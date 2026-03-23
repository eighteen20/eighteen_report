package cn.com._1820.eighteen_report.repository;

import cn.com._1820.eighteen_report.entity.ReportTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 报表模板 JPA 仓库，提供按更新时间倒序的分页查询。
 */
public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, String> {

    /**
     * 按更新时间倒序分页查询所有报表模板
     */
    Page<ReportTemplate> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}
