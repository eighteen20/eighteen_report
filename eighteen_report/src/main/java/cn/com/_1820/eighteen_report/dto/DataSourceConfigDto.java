package cn.com._1820.eighteen_report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 数据源配置传输对象，用于前后端数据交互。查询时 password 不返回明文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSourceConfigDto {

    private String id;
    private String name;
    /** JDBC 或 API */
    private String type;
    private String url;
    private String username;
    /** 创建/更新时可传入，查询时不返回明文 */
    private String password;
    private String driverClass;
    private Instant createdAt;
    private Instant updatedAt;
}
