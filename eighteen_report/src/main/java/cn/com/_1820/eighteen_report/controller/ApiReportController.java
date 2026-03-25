package cn.com._1820.eighteen_report.controller;

import cn.com._1820.eighteen_report.dto.*;
import cn.com._1820.eighteen_report.service.ReportExportService;
import cn.com._1820.eighteen_report.service.ReportImageUploadService;
import cn.com._1820.eighteen_report.service.ReportQueryService;
import cn.com._1820.eighteen_report.service.ReportRenderService;
import cn.com._1820.eighteen_report.service.ReportTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 报表 REST API 控制器，提供报表模板 CRUD、数据查询、渲染预览和文件导出功能。
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ApiReportController {

    private final ReportTemplateService templateService;
    private final ReportQueryService queryService;
    private final ReportRenderService renderService;
    private final ReportExportService exportService;
    private final ReportImageUploadService imageUploadService;

    /**
     * 分页查询报表模板列表
     */
    @GetMapping("/templates")
    public ResponseEntity<PageResult<ReportTemplateDto>> listTemplates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var p = templateService.list(PageRequest.of(page, size));
        return ResponseEntity.ok(new PageResult<>(p.getContent(), p.getTotalElements()));
    }

    /**
     * 查询报表模板详情
     */
    @GetMapping("/template/{id}")
    public ResponseEntity<ReportTemplateDto> getTemplate(@PathVariable String id) {
        return ResponseEntity.ok(templateService.getById(id));
    }

    /**
     * 创建报表模板
     */
    @PostMapping("/template")
    public ResponseEntity<ReportTemplateDto> createTemplate(@RequestBody ReportTemplateDto dto) {
        return ResponseEntity.ok(templateService.create(dto));
    }

    /**
     * 更新报表模板
     */
    @PutMapping("/template/{id}")
    public ResponseEntity<ReportTemplateDto> updateTemplate(
            @PathVariable String id,
            @RequestBody ReportTemplateDto dto) {
        return ResponseEntity.ok(templateService.update(id, dto));
    }

    /**
     * 删除报表模板
     */
    @DeleteMapping("/template/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 执行报表数据集查询（返回原始数据）
     */
    @PostMapping("/query")
    public ResponseEntity<ReportQueryResponse> query(@RequestBody ReportQueryRequest request) {
        return ResponseEntity.ok(queryService.query(request));
    }

    /**
     * 渲染报表（变量替换+数据行展开，返回最终矩阵）
     */
    @PostMapping("/render")
    public ResponseEntity<ReportRenderResponse> render(@RequestBody ReportQueryRequest request) {
        return ResponseEntity.ok(renderService.render(
                request.getTemplateId(),
                request.getParams()
        ));
    }

    /**
     * 导出报表为 Excel 文件
     */
    @PostMapping(value = "/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> export(@RequestBody ReportExportRequest request) {
        ReportExportService.ExportResult result = exportService.export(request);
        String filename = result.filename();
        if (filename != null && !filename.isEmpty()) {
            String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.parseMediaType(result.contentType()))
                    .body(result.body());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .body(result.body());
    }

    /**
     * 上传本地图片：由报表工具后端将文件转发到模板配置的业务方回调接口（imgFile 参数），
     * 业务方返回图片 URL。
     *
     * @param templateId 模板主键（用于读取 imageUploadCallbackUrl）
     * @param file       multipart 上传的图片文件
     * @return 业务方返回的图片 URL 字符串
     */
    @PostMapping("/{templateId}/image/upload/local")
    public ResponseEntity<String> uploadLocalImage(
            @PathVariable String templateId,
            @RequestParam("file") MultipartFile file) {
        String imageUrl = imageUploadService.uploadLocal(templateId, file);
        return ResponseEntity.ok(imageUrl);
    }

    /**
     * 上传网络图片：由报表工具后端拉取指定网络地址的图片，再转发给业务方回调接口（imgFile 参数），
     * 业务方返回图片 URL。
     *
     * @param templateId 模板主键（用于读取 imageUploadCallbackUrl）
     * @param body       请求体，包含 {@code imageUrl} 字段
     * @return 业务方返回的图片 URL 字符串
     */
    @PostMapping("/{templateId}/image/upload/remote")
    public ResponseEntity<String> uploadRemoteImage(
            @PathVariable String templateId,
            @RequestBody Map<String, String> body) {
        String imageUrl = imageUploadService.uploadRemote(templateId, body.get("imageUrl"));
        return ResponseEntity.ok(imageUrl);
    }

    /** 分页结果包装 */
    public record PageResult<T>(java.util.List<T> list, long total) {}
}
