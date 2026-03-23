<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>报表列表 - 报表工具</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #f5f6fa; }
        .toolbar { display: flex; align-items: center; gap: 10px; padding: 8px 16px; background: #fff; border-bottom: 1px solid #e0e0e0; }
        .toolbar h1 { font-size: 16px; font-weight: 600; color: #333; }
        .btn { padding: 6px 16px; border: 1px solid #ccc; border-radius: 4px; background: #fff; cursor: pointer; font-size: 13px; text-decoration: none; color: #333; display: inline-block; }
        .btn:hover { background: #f0f0f0; }
        .btn-primary { background: #1677ff; color: #fff; border-color: #1677ff; }
        .btn-primary:hover { background: #4096ff; }
        .btn-danger { background: #ff4d4f; color: #fff; border-color: #ff4d4f; }
        .btn-danger:hover { background: #ff7875; }
        .btn-sm { padding: 3px 10px; font-size: 12px; }
        .content { padding: 16px; }
        table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 6px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
        th { background: #fafafa; text-align: left; padding: 10px 12px; font-size: 13px; color: #555; border-bottom: 1px solid #e8e8e8; }
        td { padding: 10px 12px; font-size: 13px; border-bottom: 1px solid #f0f0f0; }
        tr:hover td { background: #fafafa; }
        .text-muted { color: #999; }
        .text-danger { color: #cf1322; }
    </style>
</head>
<body>
    <div class="toolbar">
        <h1>报表列表</h1>
        <div style="flex:1;"></div>
        <a href="/report/design" class="btn btn-primary">+ 新建报表</a>
    </div>
    <div class="content">
        <table>
            <thead>
                <tr>
                    <th>名称</th>
                    <th>描述</th>
                    <th>更新时间</th>
                    <th style="width:220px;">操作</th>
                </tr>
            </thead>
            <tbody id="list-body">
                <tr><td colspan="4" class="text-muted">加载中...</td></tr>
            </tbody>
        </table>
    </div>
    <script>
    (function () {
        fetch('/api/report/templates?page=0&size=50')
            .then(function (r) { return r.json(); })
            .then(function (res) {
                var tbody = document.getElementById('list-body');
                if (!res.list || res.list.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="4" class="text-muted">暂无报表，请新建</td></tr>';
                    return;
                }
                tbody.innerHTML = res.list.map(function (item) {
                    var updated = item.updatedAt ? new Date(item.updatedAt).toLocaleString() : '-';
                    return '<tr>' +
                        '<td>' + (item.name || '-') + '</td>' +
                        '<td>' + (item.description || '-') + '</td>' +
                        '<td>' + updated + '</td>' +
                        '<td>' +
                        '<a href="/report/design/' + item.id + '" class="btn btn-sm">编辑</a> ' +
                        '<a href="/report/preview/' + item.id + '" class="btn btn-sm">预览</a> ' +
                        '<button type="button" class="btn btn-sm btn-danger" data-id="' + item.id + '">删除</button>' +
                        '</td></tr>';
                }).join('');
                tbody.querySelectorAll('button[data-id]').forEach(function (btn) {
                    btn.addEventListener('click', function () {
                        if (!confirm('确定删除该报表？')) return;
                        var id = btn.getAttribute('data-id');
                        fetch('/api/report/template/' + id, { method: 'DELETE' })
                            .then(function (r) { if (r.ok) location.reload(); });
                    });
                });
            })
            .catch(function () {
                document.getElementById('list-body').innerHTML =
                    '<tr><td colspan="4" class="text-danger">加载失败</td></tr>';
            });
    })();
    </script>
</body>
</html>
