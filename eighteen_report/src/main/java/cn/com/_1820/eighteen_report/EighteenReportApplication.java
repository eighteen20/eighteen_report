package cn.com._1820.eighteen_report;

import cn.com._1820.eighteen_report.config.WatermarkCallbackProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Eighteen Report 报表工具主启动类。
 *
 * 项目采用 Spring Boot 4 承载后端接口与静态资源，并由前端完成报表设计/预览渲染，后端负责渲染与导出能力。
 */
@SpringBootApplication
@EnableConfigurationProperties(WatermarkCallbackProperties.class)
public class EighteenReportApplication {

    public static void main(String[] args) {
        SpringApplication.run(EighteenReportApplication.class, args);
    }

}
