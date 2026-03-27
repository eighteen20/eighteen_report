package cn.com._1820.eighteen_report.service.datasource.sql;

import org.springframework.stereotype.Component;

/**
 * MySQL 分页方言：使用 {@code LIMIT ? OFFSET ?}。
 */
@Component
public class MySqlPaginationDialect implements SqlPaginationDialect {

    @Override
    public String toPageSql(String parsedSql) {
        return "SELECT * FROM (" + parsedSql + ") t LIMIT ? OFFSET ?";
    }

    @Override
    public String toCountSql(String parsedSql) {
        return "SELECT COUNT(1) FROM (" + parsedSql + ") t";
    }
}

