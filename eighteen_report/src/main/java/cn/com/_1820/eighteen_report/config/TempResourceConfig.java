package cn.com._1820.eighteen_report.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地联调专用：将工作区 {@code temp/} 目录映射为可访问的静态资源路径 {@code /temp/**}。
 *
 * <p>图片上传模拟回调（{@code /api/demo/image/upload}）会把文件存入 {@code <workspace>/temp/}，
 * 存完后返回 {@code http://localhost:9876/temp/<filename>}；本配置使该 URL 可以正常访问图片。</p>
 *
 * <p>目录路径推算逻辑：以 {@code System.getProperty("user.dir")}（即 Spring Boot 工作目录，
 * 通常为 {@code eighteen_report/} 子模块根）的上一级作为工作区根，再拼接 {@code temp}。</p>
 */
@Slf4j
@Configuration
public class TempResourceConfig implements WebMvcConfigurer {

    /**
     * 将 {@code /temp/**} 请求映射到文件系统的 {@code <workspace>/temp/} 目录。
     * 目录不存在时自动创建，避免首次启动报错。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // user.dir = 后端子模块根（eighteen_report/eighteen_report）
        // 上一级为整个工作区根（eighteen_report/）
        Path tempDir = Paths.get(System.getProperty("user.dir"))
                .getParent()
                .resolve("temp");

        try {
            Files.createDirectories(tempDir);
        } catch (Exception e) {
            log.warn("temp 目录创建失败，/temp/** 静态资源可能无法访问: {}", e.getMessage());
        }

        // 路径末尾必须有分隔符，Spring 才能正确解析子资源
        String location = "file:" + tempDir.toAbsolutePath() + "/";
        log.info("本地联调图片目录映射: /temp/** → {}", location);

        registry.addResourceHandler("/temp/**")
                .addResourceLocations(location);
    }
}
