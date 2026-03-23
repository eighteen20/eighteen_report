package cn.com._1820.eighteen_report.repository;

import cn.com._1820.eighteen_report.entity.DataSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 数据源配置 JPA 仓库，提供按名称排序的查询。
 */
public interface DataSourceConfigRepository extends JpaRepository<DataSourceConfig, String> {

    /**
     * 按名称升序查询所有数据源配置
     */
    List<DataSourceConfig> findAllByOrderByNameAsc();
}
