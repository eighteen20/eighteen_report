package cn.com._1820.eighteen_report.controller;

import cn.com._1820.eighteen_report.dto.DataSourceConfigDto;
import cn.com._1820.eighteen_report.dto.DataSourceTestRequest;
import cn.com._1820.eighteen_report.dto.DataSourceTestResponse;
import cn.com._1820.eighteen_report.service.DataSourceConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据源管理 REST API 控制器，提供数据源的增删改查和连接测试功能。
 */
@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
public class ApiDataSourceController {

    private final DataSourceConfigService service;

    /**
     * 查询所有数据源列表
     */
    @GetMapping("/list")
    public ResponseEntity<List<DataSourceConfigDto>> list() {
        return ResponseEntity.ok(service.list());
    }

    /**
     * 根据 ID 查询数据源详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<DataSourceConfigDto> get(@PathVariable String id) {
        return ResponseEntity.ok(service.getById(id));
    }

    /**
     * 创建新数据源
     */
    @PostMapping
    public ResponseEntity<DataSourceConfigDto> create(@RequestBody DataSourceConfigDto dto) {
        return ResponseEntity.ok(service.create(dto));
    }

    /**
     * 更新数据源配置
     */
    @PutMapping("/{id}")
    public ResponseEntity<DataSourceConfigDto> update(@PathVariable String id, @RequestBody DataSourceConfigDto dto) {
        return ResponseEntity.ok(service.update(id, dto));
    }

    /**
     * 删除数据源
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 测试数据源连接（执行 SQL 或调用 API 获取字段列表和预览数据）
     */
    @PostMapping("/test")
    public ResponseEntity<DataSourceTestResponse> test(@RequestBody DataSourceTestRequest request) {
        return ResponseEntity.ok(service.test(request));
    }
}
