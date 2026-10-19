(function () {
    const node = document.getElementById('desktopBackupStatus');
    if (!node) return;
    async function refresh() {
        try {
            const response = await fetch('/api/desktop/backups/status', { credentials: 'same-origin' });
            if (!response.ok) throw new Error();
            const result = await response.json();
            if (result.code !== 200) throw new Error();
            const state = result.data;
            node.textContent = state.warning ? `${state.warning} 备份位置：${state.directory}`
                : state.pending ? '每日完整备份正在保存…'
                : state.lastSuccessfulDay ? `最近每日备份：${state.lastSuccessfulDay}。有修改的自然日保存一次，保留最近 7 份。`
                : '每日完整备份：等待首次修改，保留最近 7 份。可从“文件 → 打开备份目录”查看。';
        } catch { node.textContent = '自动备份状态暂时无法读取，请从“文件 → 打开备份目录”核对；仍可手动导出完整备份。'; }
    }
    refresh();
    window.setInterval(refresh, 10000);
})();
