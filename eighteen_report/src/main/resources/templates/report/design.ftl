<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>报表设计 - 报表工具</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/ag-grid-community@32/styles/ag-grid.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/ag-grid-community@32/styles/ag-theme-alpine.min.css">
    <link rel="stylesheet" href="/css/common.css">
    <link rel="stylesheet" href="/css/design.css">
</head>
<body>
<!-- 
  报表设计器页面 (design.ftl)
  
  纯 HTML 结构，CSS/JS 已抽取为独立静态资源文件。
  FreeMarker 仅注入 templateId 变量供前端 JS 使用。
  
  页面结构：
    - 顶部工具栏：返回、名称输入、保存、预览
    - 格式工具栏：加粗/斜体/字体/字号/边框/颜色/合并
    - 主体区域：左侧数据集面板 + 右侧 AG Grid 电子表格
    - 数据集弹窗：添加/编辑 SQL 或 API 数据源
  
  JS 模块加载顺序（按依赖）：
    cell-utils → merge-utils → selection → format-toolbar →
    border-manager → merge-toggle → dataset-panel → grid-designer → designer-app
-->
    <div class="toolbar">
        <a href="/report" class="btn">返回列表</a>
        <input type="text" id="template-name" placeholder="报表名称">
        <button type="button" id="btn-save" class="btn btn-primary">保存</button>
        <button type="button" id="btn-preview" class="btn btn-success">预览</button>
    </div>

    <div class="format-bar">
        <button type="button" id="fmt-bold" class="fmt-btn" title="加粗"><b>B</b></button>
        <button type="button" id="fmt-italic" class="fmt-btn" title="斜体"><i>I</i></button>
        <button type="button" id="fmt-underline" class="fmt-btn" title="下划线"><u>U</u></button>
        <button type="button" id="fmt-strike" class="fmt-btn" title="删除线"><s>S</s></button>
        <div class="fmt-sep"></div>
        <select id="fmt-font" class="fmt-select" title="字体" style="width:110px;">
            <option value="">默认字体</option>
            <option value="SimSun" style="font-family:SimSun">宋体</option>
            <option value="Microsoft YaHei" style="font-family:'Microsoft YaHei'">微软雅黑</option>
            <option value="SimHei" style="font-family:SimHei">黑体</option>
            <option value="KaiTi" style="font-family:KaiTi">楷体</option>
            <option value="FangSong" style="font-family:FangSong">仿宋</option>
            <option value="Arial" style="font-family:Arial">Arial</option>
            <option value="Times New Roman" style="font-family:'Times New Roman'">Times New Roman</option>
            <option value="Courier New" style="font-family:'Courier New'">Courier New</option>
        </select>
        <select id="fmt-size" class="fmt-select" title="字号" style="width:72px;">
            <option value="">字号</option>
            <option value="10px">10</option>
            <option value="11px">11</option>
            <option value="12px">12</option>
            <option value="13px">13</option>
            <option value="14px">14</option>
            <option value="16px">16</option>
            <option value="18px">18</option>
            <option value="20px">20</option>
            <option value="24px">24</option>
            <option value="28px">28</option>
            <option value="32px">32</option>
            <option value="36px">36</option>
        </select>
        <div class="fmt-sep"></div>
        <div class="fmt-align-wrap" id="fmt-align-wrap">
            <button type="button" class="fmt-btn" id="fmt-align-btn" title="文本对齐">
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="#333" stroke-width="1.4" stroke-linecap="round">
                    <line x1="2" y1="3" x2="14" y2="3"></line>
                    <line x1="4" y1="6.5" x2="12" y2="6.5"></line>
                    <line x1="2" y1="10" x2="14" y2="10"></line>
                    <line x1="4" y1="13.5" x2="12" y2="13.5"></line>
                </svg>
            </button>
            <div class="fmt-align-menu" id="fmt-align-menu">
                <div class="fmt-align-opt" data-align="left">左对齐</div>
                <div class="fmt-align-opt" data-align="center">水平居中</div>
                <div class="fmt-align-opt" data-align="right">右对齐</div>
            </div>
        </div>
        <div class="fmt-sep"></div>
        <div class="fmt-border-wrap" id="fmt-border-wrap">
            <button type="button" class="fmt-btn" id="fmt-border-btn" title="边框">
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="#333" stroke-width="1.5">
                    <rect x="1" y="1" width="14" height="14"/><line x1="8" y1="1" x2="8" y2="15"/><line x1="1" y1="8" x2="15" y2="8"/>
                </svg>
            </button>
            <div class="fmt-border-menu" id="fmt-border-menu">
                <div class="fmt-border-opt" data-border="all"><span class="fmt-border-icon bi-all"></span>所有边框</div>
                <div class="fmt-border-opt" data-border="outer"><span class="fmt-border-icon bi-outer"></span>外侧边框</div>
                <div class="fmt-border-opt" data-border="top"><span class="fmt-border-icon bi-top"></span>上边框</div>
                <div class="fmt-border-opt" data-border="bottom"><span class="fmt-border-icon bi-bottom"></span>下边框</div>
                <div class="fmt-border-opt" data-border="left"><span class="fmt-border-icon bi-left"></span>左边框</div>
                <div class="fmt-border-opt" data-border="right"><span class="fmt-border-icon bi-right"></span>右边框</div>
                <div class="fmt-border-opt" data-border="none"><span class="fmt-border-icon bi-none"></span>无边框</div>
            </div>
        </div>
        <div class="fmt-sep"></div>
        <div class="fmt-color-wrap" title="字体颜色">
            <span class="fmt-color-icon">A</span>
            <div id="fmt-color-bar" class="fmt-color-bar" style="background:#000;"></div>
            <input type="color" id="fmt-color" class="fmt-color-input" value="#000000">
        </div>
        <div class="fmt-color-wrap" title="背景颜色">
            <span class="fmt-color-icon" style="font-weight:normal;">&#9632;</span>
            <div id="fmt-bgcolor-bar" class="fmt-color-bar" style="background:#ffffff;"></div>
            <input type="color" id="fmt-bgcolor" class="fmt-color-input" value="#ffffff">
        </div>
        <div class="fmt-sep"></div>
        <button type="button" id="fmt-merge-toggle" class="fmt-btn fmt-merge-btn" title="合并/取消合并单元格">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="#333" stroke-width="1.2">
                <rect x="1" y="1" width="14" height="14" rx="1"/><line x1="8" y1="1" x2="8" y2="6"/><line x1="8" y1="10" x2="8" y2="15"/><line x1="1" y1="8" x2="6" y2="8"/><line x1="10" y1="8" x2="15" y2="8"/>
            </svg>
        </button>
        <button type="button" id="fmt-freeze-row" class="fmt-btn fmt-freeze-btn" title="冻结到当前选中行">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="#333" stroke-width="1.2">
                <path d="M8 1 L11 4 L9 4 L9 9 L7 9 L7 4 L5 4 Z"/>
                <rect x="2" y="10" width="12" height="5" rx="1"/>
            </svg>
        </button>
    </div>

    <div class="main-layout">
        <div class="left-panel">
            <div class="panel-title">数据集管理</div>
            <button type="button" id="btn-add-ds" class="btn btn-primary btn-sm" style="margin-bottom:10px; width:100%;">+ 添加数据集</button>
            <div id="ds-list"></div>
        </div>
        <div class="center-panel">
            <div id="grid-wrapper" class="ag-theme-alpine"></div>
        </div>
        <div class="right-panel">
            <div class="sp-tabs">
                <button type="button" class="sp-tab active" data-tab="report">报表设置</button>
                <button type="button" class="sp-tab" data-tab="cell">单元格设置</button>
            </div>
            <div id="sp-report" class="sp-tab-content active">
                <div class="sp-group-title">基础设置</div>
                <div class="sp-field sp-field-switch">
                    <label for="sp-col-sortable">列排序</label>
                    <label class="sp-switch">
                        <input type="checkbox" id="sp-col-sortable">
                        <span class="sp-slider"></span>
                    </label>
                </div>
                <div class="sp-field sp-field-switch">
                    <label for="sp-grid-lines">网格线（预览/导出）</label>
                    <label class="sp-switch">
                        <input type="checkbox" id="sp-grid-lines" checked>
                        <span class="sp-slider"></span>
                    </label>
                </div>
                <div class="sp-field">
                    <label for="sp-default-row-h">默认行高</label>
                    <input type="number" id="sp-default-row-h" min="10" value="25">
                </div>
                <div class="sp-field">
                    <label for="sp-default-col-w">默认列宽</label>
                    <input type="number" id="sp-default-col-w" min="30" value="100">
                </div>
                <div class="sp-group-title">页面设置</div>
                <div class="sp-field">
                    <label for="sp-paper-size">纸张大小</label>
                    <select id="sp-paper-size">
                        <option value="A4">A4</option>
                        <option value="A3">A3</option>
                        <option value="B5">B5</option>
                        <option value="Letter">Letter</option>
                    </select>
                </div>
                <div class="sp-field">
                    <label for="sp-page-orient">页面方向</label>
                    <select id="sp-page-orient">
                        <option value="portrait">纵向</option>
                        <option value="landscape">横向</option>
                    </select>
                </div>
                <div class="sp-field">
                    <label>页边距 (mm)</label>
                    <div class="sp-margin-inputs">
                        <input type="number" id="sp-margin-top" placeholder="上" min="0" value="10">
                        <input type="number" id="sp-margin-right" placeholder="右" min="0" value="10">
                        <input type="number" id="sp-margin-bottom" placeholder="下" min="0" value="10">
                        <input type="number" id="sp-margin-left" placeholder="左" min="0" value="10">
                    </div>
                </div>
                <div class="sp-field">
                    <label for="sp-pagination">分页方式</label>
                    <select id="sp-pagination">
                        <option value="none">不分页</option>
                        <option value="byRows">按行数分页</option>
                        <option value="manual">手动分页符</option>
                    </select>
                </div>
                <div class="sp-field" id="sp-pagination-rows-wrap" style="display:none;">
                    <label for="sp-pagination-rows">每页行数</label>
                    <input type="number" id="sp-pagination-rows" min="1" value="30">
                </div>

                <div class="sp-group-title">样式设置</div>
                <div class="sp-field">
                    <label for="sp-bg-color">报表背景色</label>
                    <input type="color" id="sp-bg-color" value="#ffffff">
                </div>
                <div class="sp-field">
                    <label for="sp-default-font">默认字体</label>
                    <select id="sp-default-font">
                        <option value="">默认字体</option>
                        <option value="SimSun">宋体</option>
                        <option value="Microsoft YaHei">微软雅黑</option>
                        <option value="SimHei">黑体</option>
                        <option value="KaiTi">楷体</option>
                        <option value="FangSong">仿宋</option>
                        <option value="Arial">Arial</option>
                        <option value="Times New Roman">Times New Roman</option>
                        <option value="Courier New">Courier New</option>
                    </select>
                </div>
                <div class="sp-field">
                    <label for="sp-default-font-size">默认字号</label>
                    <select id="sp-default-font-size">
                        <option value="">默认字号</option>
                        <option value="10px">10</option>
                        <option value="11px">11</option>
                        <option value="12px">12</option>
                        <option value="13px">13</option>
                        <option value="14px">14</option>
                        <option value="16px">16</option>
                        <option value="18px">18</option>
                        <option value="20px">20</option>
                        <option value="24px">24</option>
                    </select>
                </div>
                <div class="sp-field">
                    <label for="sp-watermark">水印文字</label>
                    <input type="text" id="sp-watermark" placeholder="留空则无水印">
                </div>
            </div>
            <div id="sp-cell" class="sp-tab-content">
                <div class="sp-field">
                    <label for="sp-cell-ref">单元格</label>
                    <input type="text" id="sp-cell-ref" disabled>
                </div>
                <div class="sp-field-row">
                    <div class="sp-field">
                        <label for="sp-cell-row-h">行高</label>
                        <input type="number" id="sp-cell-row-h" min="10">
                    </div>
                    <div class="sp-field">
                        <label for="sp-cell-col-w">列宽</label>
                        <input type="number" id="sp-cell-col-w" min="30">
                    </div>
                </div>
                <div class="sp-field">
                    <label for="sp-cell-value">值</label>
                    <textarea id="sp-cell-value" rows="3"></textarea>
                </div>
                <div class="sp-field">
                    <label for="sp-cell-type">值类型</label>
                    <select id="sp-cell-type">
                        <option value="text">文本</option>
                        <option value="number">数值</option>
                        <option value="image">图片</option>
                        <option value="barcode">条形码</option>
                        <option value="qrcode">二维码</option>
                    </select>
                </div>
            </div>
        </div>
    </div>

    <!-- Dataset Modal -->
    <div id="ds-modal" class="modal-overlay">
        <div class="modal">
            <h3 id="ds-modal-title">添加数据集</h3>
            <input type="hidden" id="ds-edit-key">
            <div class="form-group">
                <label>数据集名称 (key)</label>
                <input type="text" id="ds-key" placeholder="如 ds1">
            </div>
            <div class="form-group">
                <label>类型</label>
                <select id="ds-type">
                    <option value="SQL">SQL</option>
                    <option value="API">API</option>
                </select>
            </div>
            <div id="ds-sql-fields">
                <div class="form-group">
                    <label>数据源</label>
                    <select id="ds-source-id"><option value="">加载中...</option></select>
                </div>
                <div class="form-group">
                    <label>SQL 语句</label>
                    <textarea id="ds-sql" placeholder="SELECT name, dept, amount FROM sales WHERE month = :month"></textarea>
                </div>
            </div>
            <div id="ds-api-fields" style="display:none;">
                <div class="form-group">
                    <label>API URL</label>
                    <input type="text" id="ds-api-url" placeholder="https://api.example.com/data">
                </div>
                <div class="form-group">
                    <label>请求方式</label>
                    <select id="ds-api-method">
                        <option value="GET">GET</option>
                        <option value="POST">POST</option>
                    </select>
                </div>
            </div>
            <div class="modal-actions">
                <button type="button" id="btn-ds-test" class="btn btn-success">测试获取字段</button>
                <button type="button" id="btn-ds-cancel" class="btn">取消</button>
                <button type="button" id="btn-ds-save" class="btn btn-primary">确定</button>
            </div>
            <div id="ds-test-result" class="test-result" style="display:none;"></div>
        </div>
    </div>

    <!-- 列功能菜单 -->
    <div id="er-col-menu" class="er-context-menu" style="display:none;">
        <div class="er-context-menu-item" data-action="set-col-width">设置列宽</div>
        <div class="er-context-menu-item er-context-menu-inline" data-action="insert-col-left">
            <span>左侧插入列</span>
            <input type="number" id="er-insert-col-count" min="1" value="1">
        </div>
        <div class="er-context-menu-item" data-action="delete-col">删除列</div>
    </div>
    <!-- 行右键菜单 -->
    <div id="er-row-menu" class="er-context-menu" style="display:none;">
        <div class="er-context-menu-item" data-action="set-row-height">设置行高</div>
        <div class="er-context-menu-item er-context-menu-inline" data-action="insert-row-above">
            <span>上方插入行</span>
            <input type="number" id="er-insert-row-count" min="1" value="1">
        </div>
        <div class="er-context-menu-item" data-action="delete-row">删除行</div>
    </div>
    <!-- 列宽/行高输入弹窗 -->
    <div id="er-input-modal" class="er-input-modal-overlay">
        <div class="er-input-modal">
            <h4 id="er-input-modal-title">设置列宽</h4>
            <div class="er-input-modal-body">
                <label id="er-input-modal-label">列宽（像素）</label>
                <input type="number" id="er-input-modal-value" min="1" step="1">
                <span id="er-input-modal-hint" class="er-input-modal-hint"></span>
            </div>
            <div class="er-input-modal-footer">
                <button type="button" id="er-input-modal-cancel" class="btn">取消</button>
                <button type="button" id="er-input-modal-ok" class="btn btn-primary">确定</button>
            </div>
        </div>
    </div>

    <script src="https://cdn.jsdelivr.net/npm/ag-grid-community@32/dist/ag-grid-community.min.js"></script>
    <script>var __TEMPLATE_ID__ = '${templateId!""}';</script>
    <script src="/js/cell-utils.js"></script>
    <script src="/js/merge-utils.js"></script>
    <script src="/js/selection.js"></script>
    <script src="/js/format-toolbar.js"></script>
    <script src="/js/border-manager.js"></script>
    <script src="/js/merge-toggle.js"></script>
    <script src="/js/dataset-panel.js"></script>
    <script src="/js/grid-designer.js"></script>
    <script src="/js/context-menu.js"></script>
    <script src="/js/resize-manager.js"></script>
    <script src="/js/settings-panel.js"></script>
    <script src="/js/designer-app.js"></script>
</body>
</html>
