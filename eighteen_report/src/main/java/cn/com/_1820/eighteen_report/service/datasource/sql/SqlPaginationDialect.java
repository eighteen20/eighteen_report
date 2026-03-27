package cn.com._1820.eighteen_report.service.datasource.sql;

/**
 * SQL 分页方言接口：根据数据库类型拼接分页 SQL 与统计 SQL。
 *
 * <p>设计意图：</p>
 * <ul>
 *   <li>隔离数据库差异（MySQL/PostgreSQL/Oracle/SQLServer）；</li>
 *   <li>查询服务层只关心“分页意图”，不关心具体语法；</li>
 *   <li>后续新增方言仅需新增实现类并在 resolver 中注册。</li>
 * </ul>
 */
public interface SqlPaginationDialect {

    /**
     * 为原始查询拼接分页语句。
     *
     * @param parsedSql 命名参数解析后的 SQL（已替换为 ?）
     * @return 分页 SQL（参数顺序由调用方按方言约定追加）
     */
    String toPageSql(String parsedSql);

    /**
     * 将原始查询包装为 count 查询。
     *
     * @param parsedSql 命名参数解析后的 SQL（已替换为 ?）
     * @return count SQL
     */
    String toCountSql(String parsedSql);
}

