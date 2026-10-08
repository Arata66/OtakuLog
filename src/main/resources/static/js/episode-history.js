function episodeHistoryCurrent(section) {
    return document.getElementById('episodeHistorySection') === section;
}

async function loadEpisodeHistory(id, page = 0) {
    const section = document.getElementById('episodeHistorySection');
    if (!section || section.dataset.animeId !== String(id) || section.historyState?.saving) return;
    const state = { id, page, section, entries: [], saving: false, conflicted: false };
    section.historyState = state;
    section.innerHTML = '<p role="status">正在读取观看记录…</p>';
    const result = await fetchApi(`/api/anime/${id}/episodes?page=${page}&size=20`);
    // 关闭、切换作品或再次翻页后，旧响应不能更新当前详情。
    if (!episodeHistoryCurrent(section) || section.historyState !== state) return;
    if (!result || result.code !== 200) {
        section.innerHTML = `<p role="alert">观看记录加载失败</p><button class="a-btn" onclick="loadEpisodeHistory(${id},${page})">重试</button>`;
        return;
    }
    Object.assign(state, result.data);
    renderEpisodeHistory(state);
}

function renderEpisodeHistory(state) {
    const sources = { WATCHED: '观看时记录', MANUAL: '手动补录', IMPORT: '导入补录', LEGACY: '历史来源不明（可能含旧估算）' };
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    const rows = state.entries.map(entry => `<div class="episode-history-row">
        <label class="episode-history-label" for="episode-date-${entry.episodeNumber}">第 ${entry.episodeNumber} 集${entry.episodeNumber > state.currentEpisode ? ' · 进度外历史' : ''}
            <small>${esc(entry.recorded ? sources[entry.source] || '未知来源' : '缺少记录')}${entry.recorded && !entry.watchedDate ? ' · 日期未知' : ''}</small></label>
        <input class="app-input" id="episode-date-${entry.episodeNumber}" type="date" min="1000-01-01" max="${today}" value="${esc(entry.watchedDate || '')}" aria-label="第 ${entry.episodeNumber} 集观看日期">
        <button class="a-btn" onclick="saveEpisodeDate(${state.id},${entry.episodeNumber})">${entry.source === 'LEGACY' ? '核对并保存' : '保存'}</button>
    </div>`).join('');
    const lastPage = Math.max(0, Math.ceil(state.totalRows / state.size) - 1);
    state.section.innerHTML = `<h3>观看记录</h3>
        <p class="episode-history-note">按集核对实际观看日期，未知可留空。保存不改变观看进度；历史来源不明的日期须核对后才计入年报。</p>
        <div class="episode-history-rows">${rows || '<p>暂无已观看记录，开始观看后可在这里核对。</p>'}</div>
        <p id="episodeHistoryStatus" class="episode-history-note" role="status" aria-live="polite"></p>
        <div class="episode-history-pages">
            <button class="a-btn" onclick="changeEpisodePage(${state.id},${state.page - 1})" ${state.page <= 0 ? 'disabled' : ''}>上一页</button>
            <span>第 ${state.page + 1} / ${lastPage + 1} 页 · ${state.totalRows} 集</span>
            <button class="a-btn" onclick="changeEpisodePage(${state.id},${state.page + 1})" ${state.page >= lastPage ? 'disabled' : ''}>下一页</button>
            <button class="a-btn" onclick="changeEpisodePage(${state.id},${state.page})">重新加载</button>
        </div>`;
}

async function changeEpisodePage(id, page) {
    const section = document.getElementById('episodeHistorySection');
    const state = section?.historyState;
    if (!state || state.id !== id || state.saving || page < 0) return;
    const dirty = state.entries.some(entry => document.getElementById(`episode-date-${entry.episodeNumber}`)?.value !== (entry.watchedDate || ''));
    if (dirty && !confirm('存在未保存的日期，重新加载或翻页会放弃这些输入，是否继续？')) return;
    await loadEpisodeHistory(id, page);
}

async function saveEpisodeDate(id, number) {
    const section = document.getElementById('episodeHistorySection');
    const state = section?.historyState;
    if (!state || state.id !== id || state.saving || state.conflicted) return;
    const expected = state.entries.find(entry => entry.episodeNumber === number);
    const input = document.getElementById(`episode-date-${number}`);
    if (!expected || !input || (input.reportValidity && !input.reportValidity())) return;
    const watchedDate = input.value || null;
    if (expected.source === 'LEGACY') {
        const action = watchedDate ? `确认第 ${number} 集实际观看日期为 ${watchedDate}？` : `确认第 ${number} 集观看日期未知？`;
        if (!confirm(action + ' 这会将该集标为手动核对；原日期可能来自旧估算，请按真实记忆填写。')) return;
    } else if (expected.watchedDate && !watchedDate && !confirm(`清空第 ${number} 集日期后，将保留未知日期记录且不计入年度观看量，是否继续？`)) return;
    state.saving = true;
    const controls = [...section.querySelectorAll('input, button')];
    const previous = controls.map(control => control.disabled);
    controls.forEach(control => { control.disabled = true; });
    const result = await fetchApi(`/api/anime/${id}/episodes/${number}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, returnConflict: true,
        body: JSON.stringify({ watchedDate, expected })
    });
    if (result?.code === 200) loadHeatmap();
    if (!episodeHistoryCurrent(section) || section.historyState !== state) return;
    state.saving = false;
    controls.forEach((control, index) => { control.disabled = previous[index]; });
    if (result?.code === 409) {
        state.conflicted = true;
        document.getElementById('episodeHistoryStatus').textContent = result.message + '；输入已保留，请重新加载后再核对。';
        section.querySelectorAll('.episode-history-row button').forEach(button => { button.disabled = true; });
    } else if (result?.code === 200) {
        // 更新当前行的快照，同时保留其他行尚未提交的日期输入。
        const drafts = new Map(state.entries.filter(entry => entry.episodeNumber !== number
            && document.getElementById(`episode-date-${entry.episodeNumber}`)?.value !== (entry.watchedDate || ''))
            .map(entry => [entry.episodeNumber, document.getElementById(`episode-date-${entry.episodeNumber}`)?.value]));
        toast('观看日期已保存', 'success');
        state.saving = true;
        controls.forEach(control => { control.disabled = true; });
        const refreshed = await fetchApi(`/api/anime/${id}/episodes?page=${state.page}&size=20`);
        if (!episodeHistoryCurrent(section) || section.historyState !== state) return;
        state.saving = false;
        controls.forEach((control, index) => { control.disabled = previous[index]; });
        if (!refreshed || refreshed.code !== 200) {
            state.conflicted = true;
            document.getElementById('episodeHistoryStatus').textContent = '日期已保存，但刷新失败；其他输入已保留，请重新加载后再保存。';
            section.querySelectorAll('.episode-history-row button').forEach(button => { button.disabled = true; });
            return;
        }
        Object.assign(state, refreshed.data);
        renderEpisodeHistory(state);
        for (const [episode, value] of drafts) {
            const field = document.getElementById(`episode-date-${episode}`);
            if (field && value !== undefined) field.value = value;
        }
    } else {
        document.getElementById('episodeHistoryStatus').textContent = '保存失败，输入已保留，可重试。';
    }
}
