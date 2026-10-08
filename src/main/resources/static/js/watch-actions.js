const pendingWatchActions = new Set();
const watchButtonStates = new WeakMap();

function isWatchPending(id) { return pendingWatchActions.has(String(id)); }

function syncWatchButtons() {
    for (const id of pendingWatchActions) setWatchButtonsPending(id, true);
}

function setWatchButtonsPending(id, pending) {
    document.querySelectorAll(`[data-watch-id="${id}"]`).forEach(button => {
        if (pending) {
            if (!watchButtonStates.has(button)) watchButtonStates.set(button, { disabled: button.disabled, text: button.textContent });
            button.disabled = true;
            button.textContent = '保存中…';
        } else {
            const previous = watchButtonStates.get(button);
            button.disabled = previous?.disabled || false;
            if (previous?.text !== undefined) button.textContent = previous.text;
            watchButtonStates.delete(button);
        }
        button.setAttribute?.('aria-busy', String(pending));
    });
}

async function recordWatchProgress(id, direction) {
    if (isWatchPending(id)) return;
    pendingWatchActions.add(String(id));
    setWatchButtonsPending(id, true);
    try {
        const result = await fetchApi(`/api/anime/${id}/${direction}-episode`, { method: 'POST', returnError: true });
        if (!result) {
            toast('未确认进度保存结果，请刷新页面核对后再操作', 'error');
            return;
        }
        if (result.code !== 200) {
            const message = result.message === 'reached_max' ? '已经是最后一集了'
                : result.message === 'reached_min' ? '已经是第 0 集了' : result.message || '更新失败';
            toast(message, result.code === 400 ? 'info' : 'error');
            return;
        }
        const anime = result.data;
        _cache[id] = anime;
        const suffix = anime.status === 'finished' ? '，已完成' : anime.status === 'planning' ? '，已恢复计划' : '';
        toast((direction === 'next' ? '已记看第 ' : '进度已退回第 ') + anime.currentEpisode + ' 集' + suffix, 'success');
        await Promise.allSettled([performSearch(), updateStats(false), loadDailyWatch(), loadHeatmap(), refreshDetailProgress(anime)]);
    } finally {
        pendingWatchActions.delete(String(id));
        setWatchButtonsPending(id, false);
    }
}

async function nextEpisode(id) { return recordWatchProgress(id, 'next'); }
async function prevEpisode(id) { return recordWatchProgress(id, 'prev'); }
