<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>报表预览 - 报表工具</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/ag-grid-community@32/styles/ag-grid.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/ag-grid-community@32/styles/ag-theme-alpine.min.css">
    <link rel="stylesheet" href="/css/common.css">
    <link rel="stylesheet" href="/css/preview.css">
</head>
<body>
<!-- 
  报表预览页面 (preview.ftl)
  
  纯 HTML 结构，JS 已抽取为独立静态资源文件。
  FreeMarker 仅注入 templateId 变量供前端 JS 使用。
  
  页面结构：
    - 顶部工具栏：返回、报表标题、导出 Excel
    - AG Grid 只读表格：展示渲染后的报表数据
-->
    <div class="toolbar">
        <a href="/report" class="btn">返回列表</a>
        <span class="report-title" id="report-title">加载中...</span>
        <div style="flex:1;"></div>
        <button type="button" id="btn-export-xlsx" class="btn btn-primary">导出 Excel</button>
    </div>
    <div id="grid-wrapper" class="ag-theme-alpine">
        <div class="loading">加载中...</div>
    </div>

    <script src="https://cdn.jsdelivr.net/npm/ag-grid-community@32/dist/ag-grid-community.min.js"></script>
    <script>var __TEMPLATE_ID__ = '${templateId!""}';</script>
    <script src="/js/merge-utils.js"></script>
    <script src="/js/preview-app.js"></script>
</body>
</html>
