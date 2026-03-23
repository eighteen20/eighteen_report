package cn.com._1820.eighteen_report.service;

import cn.com._1820.eighteen_report.dto.ReportExportRequest;
import cn.com._1820.eighteen_report.dto.ReportRenderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

/**
 * 报表导出服务：先调用渲染服务获取最终数据，再使用 EasyExcel 导出为 xlsx 格式。后续可扩展 PDF 等格式。
 */
@Service
@RequiredArgsConstructor
public class ReportExportService {

    private final ReportRenderService renderService;

    /**
     * 根据导出请求渲染报表并导出为指定格式的文件流
     */
    public ExportResult export(ReportExportRequest request) {
        ReportRenderResponse rendered = renderService.render(
                request.getTemplateId(),
                request.getQueryParams() != null ? request.getQueryParams() : Collections.emptyMap()
        );

        String format = request.getFormat() != null ? request.getFormat().toLowerCase() : "xlsx";
        if ("xlsx".equals(format)) {
            return exportExcel(rendered);
        }
        if ("pdf".equals(format)) {
            throw new UnsupportedOperationException("PDF 导出尚未实现");
        }
        throw new IllegalArgumentException("不支持的导出格式: " + request.getFormat());
    }

    private ExportResult exportExcel(ReportRenderResponse rendered) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<List<String>> cells = rendered.getCells();

        List<List<String>> head = List.of();
        List<List<Object>> body;
        if (!cells.isEmpty()) {
            head = cells.getFirst().stream().map(List::of).toList();
            body = cells.stream().skip(1)
                    .map(row -> row.stream().map(v -> (Object) v).toList())
                    .toList();
        } else {
            body = List.of();
        }

        com.alibaba.excel.EasyExcel.write(out)
                .head(head)
                .sheet("报表")
                .doWrite(body);

        return new ExportResult(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "report.xlsx",
                new ByteArrayInputStream(out.toByteArray())
        );
    }

    public record ExportResult(String contentType, String filename, java.io.InputStream body) {}
}
