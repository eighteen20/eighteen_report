package cn.com._1820.eighteen_report.controller;

import cn.com._1820.eighteen_report.entity.DemoUser;
import cn.com._1820.eighteen_report.repository.DemoUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * 演示用户 REST API 控制器，提供用户列表查询接口，用于测试报表数据源功能。
 *
 * <p>同时包含一个模拟业务方图片上传回调接口，用于本地联调测试。
 * 接收到图片文件后将其保存到项目根目录下的 {@code temp/} 文件夹，
 * 并返回可访问的静态资源 URL（需后端已配置 {@code /temp/**} 静态资源映射）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/demo/user")
@RequiredArgsConstructor
public class DemoUserController {

    /**
     * 图片存储目录（项目根目录下的 temp 文件夹，与工作区同级）。
     * 实际路径由 {@link #TEMP_DIR} 在类加载时基于 user.dir 推算。
     */
    private static final Path TEMP_DIR = Paths.get(System.getProperty("user.dir"))
            .getParent()          // eighteen_report（子模块） → 工作区根
            .resolve("temp");

    private final DemoUserRepository repository;

    /**
     * 查询所有演示用户
     *
     * @param page 页码
     * @param size 每页大小
     */
    @GetMapping("/list")
    public ResponseEntity<List<DemoUser>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(repository.findAll(PageRequest.of(page, size)).toList());
    }

    /**
     * 动态水印测试接口
     *
     * @return 动态水印文案
     */
    @GetMapping("/watermark")
    public ResponseEntity<String> watermark() {
        return ResponseEntity.ok("900821用户: " + System.currentTimeMillis());
    }

    /**
     * 模拟业务方图片上传回调接口（仅供本地联调）。
     *
     * <p>接收报表工具以 {@code multipart/form-data}（参数名 {@code imgFile}）转发的图片文件，
     * 将其保存到 {@code <workspace>/temp/} 目录，文件名为 UUID + 原始扩展名，
     * 返回 JSON {@code {"url":"http://localhost:8080/temp/<filename>"}} 供报表工具写入单元格。</p>
     *
     * <p><b>配置方式</b>：在报表设计器「报表设置 → 图片上传回调地址」中填写：
     * {@code http://localhost:9876/api/demo/image/upload}</p>
     *
     * @param imgFile multipart 图片文件（参数名须为 imgFile，由报表工具约定）
     * @return JSON：{@code {"url":"..."}} 或错误信息
     */
    @PostMapping("/image/upload")
    public ResponseEntity<String> uploadImage(@RequestParam("imgFile") MultipartFile imgFile) {
        if (imgFile == null || imgFile.isEmpty()) {
            return ResponseEntity.badRequest().body("{\"error\":\"imgFile 参数为空\"}");
        }

        // 确保 temp 目录存在
        try {
            Files.createDirectories(TEMP_DIR);
        } catch (IOException e) {
            log.error("创建 temp 目录失败: {}", TEMP_DIR, e);
            return ResponseEntity.internalServerError().body("{\"error\":\"服务端目录初始化失败\"}");
        }

        // 生成唯一文件名：UUID + 原始后缀（避免覆盖）
        String originalFilename = imgFile.getOriginalFilename();
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;
        Path dest = TEMP_DIR.resolve(filename);

        // 保存文件
        try {
            Files.copy(imgFile.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("保存图片文件失败: {}", dest, e);
            return ResponseEntity.internalServerError().body("{\"error\":\"文件保存失败: " + e.getMessage() + "\"}");
        }

        // 返回可访问的 URL（依赖 TempResourceConfig 的 /temp/** 静态资源映射）
        String url = "http://localhost:9876/temp/" + filename;
        log.info("[模拟图片回调] 已保存图片: {} → {}", originalFilename, dest);
        return ResponseEntity.ok("{\"url\":\"" + url + "\"}");
    }
}
