package cn.com._1820.eighteen_report.service;

import cn.com._1820.eighteen_report.dto.ReportTemplateDto;
import cn.com._1820.eighteen_report.entity.ReportTemplate;
import cn.com._1820.eighteen_report.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报表模板 CRUD 服务，负责模板的创建、查询、更新、删除，以及 Entity ↔ DTO 转换。
 */
@Service
@RequiredArgsConstructor
public class ReportTemplateService {

    private final ReportTemplateRepository repository;

    /**
     * 分页查询报表模板列表，按更新时间倒序排列
     */
    public Page<ReportTemplateDto> list(Pageable pageable) {
        return repository.findAllByOrderByUpdatedAtDesc(pageable)
                .map(this::toDto);
    }

    /**
     * 根据 ID 查询报表模板，不存在时抛出 IllegalArgumentException
     */
    public ReportTemplateDto getById(String id) {
        ReportTemplate t = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报表模板不存在: " + id));
        return toDto(t);
    }

    /**
     * 创建新的报表模板，自动生成 UUID 主键
     */
    @Transactional
    public ReportTemplateDto create(ReportTemplateDto dto) {
        ReportTemplate entity = toEntity(dto);
        entity.setId(null);
        entity = repository.save(entity);
        return toDto(entity);
    }

    /**
     * 更新指定 ID 的报表模板名称、描述和内容
     */
    @Transactional
    public ReportTemplateDto update(String id, ReportTemplateDto dto) {
        ReportTemplate existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报表模板不存在: " + id));
        existing.setName(dto.getName());
        existing.setDescription(dto.getDescription());
        if (dto.getContent() != null) {
            existing.setContent(dto.getContent());
        }
        existing = repository.save(existing);
        return toDto(existing);
    }

    /**
     * 删除指定 ID 的报表模板，不存在时抛出 IllegalArgumentException
     */
    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("报表模板不存在: " + id);
        }
        repository.deleteById(id);
    }

    private ReportTemplateDto toDto(ReportTemplate e) {
        return ReportTemplateDto.builder()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .content(e.getContent())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private ReportTemplate toEntity(ReportTemplateDto dto) {
        return ReportTemplate.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .content(dto.getContent())
                .build();
    }
}
