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
    dailyWatchData = { ...result.data, todayAiring: [], todayAiringCount: null, airingStatus: 'CHECKING' };
    for (const item of dailyWatchData.continueWatching) _cache[item.anime.id] = item.anime;
    renderDailyWatch(panel, dailyWatchData, false);
    // 外部日历核对不能拖住继续观看及记进度后的刷新。
    void loadDailyAiring(request, panel);
}

async function loadDailyAiring(request, panel) {
    let result;
    try { result = await fetchApi('/api/watch/airing'); } catch { result = null; }
    if (request !== dailyWatchRequest || document.getElementById('dailyWatch') !== panel) return;
    const data = result && result.code === 200 ? result.data : null;
    const verified = data && data.airingStatus === 'VERIFIED'
        && data.date === dailyWatchData.date && data.weekday === dailyWatchData.weekday;
    dailyWatchData = { ...dailyWatchData,
        todayAiring: verified ? data.todayAiring : [],
        todayAiringCount: verified ? data.todayAiringCount : null,
        airingStatus: verified ? 'VERIFIED' : 'UNAVAILABLE' };
    for (const item of dailyWatchData.todayAiring) _cache[item.anime.id] = item.anime;
    renderDailyWatch(panel, dailyWatchData, false);
}

function renderDailyWatch(panel, data, stale) {
    const note = stale ? '<p class="daily-note daily-error" role="alert">刷新失败，已显示内容可能过期，请重新加载后记进度。</p>' : '';
    const refresh = '<button class="a-btn" onclick="loadDailyWatch()">重新加载</button>';
    if (!data) { panel.innerHTML = `<div class="daily-heading"><h2>今天从这里继续</h2>${refresh}</div>${note}`; return; }
    const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    const verified = data.airingStatus === 'VERIFIED';
    const checking = data.airingStatus === 'CHECKING';
    const airingCount = verified ? data.todayAiringCount : checking ? '核对中' : '未核实';
    const airingNote = verified ? '根据当前 Bangumi 日历，仅作参考，不代表本周一定更新。'
        : checking ? '正在核对当前放送日历…' : '放送信息未核实，日历暂不可用，请重新加载核对。';
    const airingEmpty = verified ? '当前日历没有匹配今日放送的可继续作品，仍可从继续观看中选择。'
        : '仍可从继续观看中选择。';
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
        <section aria-label="今日放送参考"><h3>今日放送参考 <span>${airingCount}</span></h3>
            <p class="daily-note">${airingNote}</p><ul class="daily-rows">${rows(verified ? data.todayAiring : [], airingEmpty)}</ul></section></div>`;
    syncWatchButtons();
}

function openDailyAnime(id) {
    if (!_cache[id]) { toast('作品信息已变化，请重新加载', 'error'); return; }
    openDetailModal(id);
}
