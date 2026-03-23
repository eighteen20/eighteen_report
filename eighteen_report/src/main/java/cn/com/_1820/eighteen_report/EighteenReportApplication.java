package cn.com._1820.eighteen_report;

import cn.com._1820.eighteen_report.config.WatermarkCallbackProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Eighteen Report 报表工具主启动类。基于 Spring Boot 4 + FreeMarker + AG Grid 的轻量级报表设计/预览/导出平台。
 */
@SpringBootApplication
@EnableConfigurationProperties(WatermarkCallbackProperties.class)
public class EighteenReportApplication {

    public static void main(String[] args) {
        SpringApplication.run(EighteenReportApplication.class, args);
    }

}
