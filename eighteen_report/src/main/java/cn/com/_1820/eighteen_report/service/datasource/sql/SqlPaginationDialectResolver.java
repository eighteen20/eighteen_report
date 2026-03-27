package cn.com._1820.eighteen_report.service.datasource.sql;

import cn.com._1820.eighteen_report.entity.DataSourceConfig;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * SQL 方言解析器：根据数据源 URL/驱动推断分页方言。
 *
 * <p>当前实现先落地 MySQL，后续可扩展更多数据库方言。</p>
 */
@Component
public class SqlPaginationDialectResolver {

    private final MySqlPaginationDialect mySqlDialect;

    public SqlPaginationDialectResolver(MySqlPaginationDialect mySqlDialect) {
        this.mySqlDialect = mySqlDialect;
    }

    /**
     * 解析数据源对应的 SQL 分页方言。
     *
     * @param config 数据源配置
     * @return 方言实现（当前默认回落到 MySQL）
     */
    public SqlPaginationDialect resolve(DataSourceConfig config) {
        if (config == null) {
            return mySqlDialect;
        }
        String url = config.getUrl() != null ? config.getUrl().toLowerCase(Locale.ROOT) : "";
        String driver = config.getDriverClass() != null ? config.getDriverClass().toLowerCase(Locale.ROOT) : "";
        if (url.contains(":mysql:") || driver.contains("mysql")) {
            return mySqlDialect;
        }
        // 先用 MySQL 兜底，后续新增方言时在此分支扩展
        return mySqlDialect;
    }
}

