let dailyWatchRequest = 0;
let dailyWatchData = null;

async function loadDailyWatch() {
    const panel = document.getElementById('dailyWatch');
    if (!panel) return;
    const request = ++dailyWatchRequest;
    if (!dailyWatchData) panel.innerHTML = '<p role="status">正在整理追番入口…</p>';
    const result = await fetchApi('/api/watch/daily');
    if (request !== dailyWatchRequest || document.getElementById('dailyWatch') !== panel) return;
    if (!result || result.code !== 200) {
        renderDailyWatch(panel, dailyWatchData, true);
        return;
    }
    dailyWatchData = result.data;
    for (const item of [...dailyWatchData.continueWatching, ...dailyWatchData.todayAiring]) _cache[item.anime.id] = item.anime;
    renderDailyWatch(panel, dailyWatchData, false);
}

function renderDailyWatch(panel, data, stale) {
    const note = stale ? '<p class="daily-note daily-error" role="alert">刷新失败，已显示内容可能过期，请重新加载后记进度。</p>' : '';
    const refresh = '<button class="a-btn" onclick="loadDailyWatch()">重新加载</button>';
    if (!data) { panel.innerHTML = `<div class="daily-heading"><h2>今天从这里继续</h2>${refresh}</div>${note}`; return; }
    const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    function rows(items, empty) {
        return items.length ? items.map(({ anime, lastWatchedDate }) => `<li class="daily-row">
            <div class="daily-row-info"><button class="daily-title" onclick="openDailyAnime(${anime.id})">${esc(anime.name)}</button>
                <div class="daily-meta">${anime.currentEpisode} / ${anime.totalEpisodes} 集 · ${lastWatchedDate ? '最近观看 ' + esc(lastWatchedDate) : '最近观看日期未知'}</div></div>
            <button class="a-btn daily-record" data-watch-id="${anime.id}" onclick="nextEpisode(${anime.id})" ${stale ? 'disabled' : ''}>记看第 ${anime.currentEpisode + 1} 集</button>
        </li>`).join('') : `<li class="daily-empty">${empty}</li>`;
    }
    panel.innerHTML = `<div class="daily-heading"><div><h2>今天从这里继续</h2><p class="daily-note">${esc(data.date)} · ${days[data.weekday - 1] || ''} · 可继续 ${data.ongoingCount} 部</p></div>${refresh}</div>${note}
        <div class="daily-columns"><section aria-label="继续观看"><h3>继续观看 <span>${data.ongoingCount}</span></h3>
            <p class="daily-note">按最近明确日期排列，首页最多显示 6 部。</p><ul class="daily-rows">${rows(data.continueWatching, '暂无可继续作品，在下方添加番剧或将计划作品设为追中。')}</ul></section>
        <section aria-label="今日放送参考"><h3>今日放送参考 <span>${data.todayAiringCount}</span></h3>
            <p class="daily-note">根据已保存的放送日，未核实本周更新。</p><ul class="daily-rows">${rows(data.todayAiring, '今天没有匹配放送日的追中作品，仍可从继续观看中选择。')}</ul></section></div>`;
    syncWatchButtons();
}

function openDailyAnime(id) {
    if (!_cache[id]) { toast('作品信息已变化，请重新加载', 'error'); return; }
    openDetailModal(id);
}
