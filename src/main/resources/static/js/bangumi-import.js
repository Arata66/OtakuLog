let bangumiImportState = null;
const bangumiCollectionTypes = { 1: '想看', 2: '看过', 3: '在看', 4: '搁置', 5: '抛弃' };

function openBangumiImport() {
    if (bangumiImportState) { document.getElementById('bgmImportUsername').focus(); return; }
    toggleSyncMenu();
    const modal = document.createElement('div'); modal.id = 'bangumiImportModal'; modal.className = 'modal-overlay';
    modal.innerHTML = `<div class="modal-card bangumi-import-card" role="dialog" aria-modal="true" aria-labelledby="bgmImportTitle">
        <div class="memory-list-heading"><h3 id="bgmImportTitle">从 Bangumi 导入</h3><button type="button" class="a-btn" data-ui-click="closeBangumiImport">关闭</button></div>
        <p class="memory-note">读取公开动画收藏，先预览再确认。搁置或集数未知会保留为外部收藏；观看日期不确定时保持为空。</p>
        <form data-ui-submit="readBangumiImport" class="bangumi-import-form">
            <label for="bgmImportUsername">个人主页 /user/ 后的账号标识<input id="bgmImportUsername" class="app-input" maxlength="100" required autocomplete="off" placeholder="账号标识，不是显示昵称"></label>
            <button id="bgmImportRead" type="submit" class="a-btn">读取并预览</button>
            <button id="bgmImportExternal" type="button" class="a-btn" data-ui-click="showBangumiCollections" data-ui-args="[0]">查看外部收藏</button>
        </form>
        <div class="bangumi-import-options"><label><input id="bgmImportScores" type="checkbox">给新增作品导入个人评分</label><label><input id="bgmImportTags" type="checkbox">给新增作品复制收藏标签</label></div>
        <p class="memory-note">已有作品不覆盖。原始评论、评分与标签保留来源；评论不会自动生成感想或发送给 AI。</p>
        <p id="bgmImportStatus" class="memory-note" role="status" aria-live="polite">填写账号后读取预览，尚未写入任何数据。</p>
        <div id="bgmImportRows" class="bangumi-import-rows"></div><div id="bgmImportPages" class="memory-pages"></div>
        <button id="bgmImportConfirm" type="button" class="a-btn" disabled data-ui-click="confirmBangumiImport">确认导入</button>
    </div>`;
    const state = { modal, preview: null, busy: null, page: 0, request: null, external: null, returnFocus: document.activeElement };
    bangumiImportState = state;
    modal.onclick = event => { if (event.target === modal) closeBangumiImport(); };
    modal.addEventListener('keydown', event => { if (event.isComposing || event.keyCode === 229) return; if (event.key === 'Escape') { event.stopPropagation(); closeBangumiImport(); } });
    document.body.appendChild(modal); trapFocus(modal); document.getElementById('bgmImportUsername').focus();
}

function closeBangumiImport() {
    const state = bangumiImportState;
    if (!state) return;
    if (state.busy === 'confirm') { bangumiImportMessage('正在确认导入，请等待结果后再关闭。'); return; }
    state.abort?.abort(); state.modal.remove(); bangumiImportState = null; state.returnFocus?.focus();
}

function bangumiImportMessage(message) { document.getElementById('bgmImportStatus').textContent = message; }
function bangumiImportBusy(state, operation) {
    state.busy = operation;
    for (const id of ['bgmImportRead', 'bgmImportExternal', 'bgmImportUsername']) document.getElementById(id).disabled = !!operation;
    for (const id of ['bgmImportScores', 'bgmImportTags']) document.getElementById(id).disabled = !!operation || !!state.request;
    document.getElementById('bgmImportConfirm').disabled = !!operation || !state.preview || !!state.external;
}

async function readBangumiImport() {
    const state = bangumiImportState;
    if (!state || state.busy) return;
    const username = document.getElementById('bgmImportUsername').value.trim();
    if (!username) { bangumiImportMessage('请填写个人主页 /user/ 后的账号标识。'); return; }
    state.preview = null; state.external = null; state.request = null; state.abort = new AbortController();
    document.getElementById('bgmImportRows').innerHTML = ''; document.getElementById('bgmImportPages').innerHTML = '';
    bangumiImportBusy(state, 'read'); bangumiImportMessage('正在分页读取公开收藏，尚未写入；关闭面板可取消本次预览。');
    try {
        const result = await fetchApi('/api/bangumi/import/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username }), signal: state.abort.signal, returnError: true });
        if (bangumiImportState !== state) return;
        if (result?.code !== 200) { bangumiImportMessage(result?.message || '读取失败，可重新读取；未执行导入。'); return; }
        state.preview = result.data; state.page = 0; renderBangumiImportPreview();
        const data = state.preview;
        bangumiImportMessage(`已读取 ${data.readCount} / ${data.total} 条公开收藏：可新增 ${data.newCount}，已有 ${data.existingCount}，需核对 ${data.reviewCount}。`
            + (data.sourceChanged ? '读取期间上游数量发生变化，建议重新读取核对。' : '') + '确认后才写入，预览15分钟后过期。');
    } catch { if (bangumiImportState === state) bangumiImportMessage('读取失败，请重试；未执行导入。'); }
    finally { if (bangumiImportState === state) bangumiImportBusy(state, null); }
}

function bangumiImportRowHtml(source, reason, index, external = null) {
    const title = source.nameCn?.trim() || source.name?.trim() || `作品编号 ${source.subjectId}`;
    return `<article class="memory-entry"><div class="memory-entry-heading"><strong>${esc(title)}</strong><span>${esc(bangumiCollectionTypes[source.type] || '未知状态')}</span></div>
        <p class="memory-note">${esc(reason)}</p><p class="memory-note">集数：${source.eps > 0 ? esc(source.eps) : '未知'} · 进度：${esc(source.epStatus ?? '未知')} · 个人评分：${source.rate ? esc(source.rate) : '未评分'}</p>
        <details class="memory-optional"><summary>查看原始收藏标签与评论</summary><p class="memory-original">收藏标签：${esc((source.tags || []).join('、') || '未填写')}</p>
        <p class="memory-original">${esc(source.comment || '没有收藏评论')}</p>${source.comment ? `<button type="button" class="a-btn" ${uiAction('copyBangumiComment', [index])}>复制收藏评论</button>` : ''}</details>
        ${external ? `<p class="memory-note">来源：Bangumi / ${esc(external.username)} · 抓取：${esc(external.capturedAt)}</p><button type="button" class="a-btn del" ${uiAction('removeBangumiCollection', [external.key,external.version])}>删除外部收藏</button>` : ''}</article>`;
}

function renderBangumiImportPreview(page = bangumiImportState?.page || 0) {
    const state = bangumiImportState;
    if (!state?.preview) return;
    state.external = null; state.page = Math.max(0, Math.min(page, Math.max(0, Math.ceil(state.preview.rows.length / 20) - 1)));
    const start = state.page * 20;
    document.getElementById('bgmImportRows').innerHTML = state.preview.rows.slice(start, start + 20)
        .map((row, index) => bangumiImportRowHtml(row.source, row.reason, start + index)).join('') || '<p class="memory-note">没有公开动画收藏。</p>';
    const pages = Math.max(1, Math.ceil(state.preview.rows.length / 20));
    document.getElementById('bgmImportPages').innerHTML = `<button type="button" class="a-btn" ${uiAction('renderBangumiImportPreview', [state.page - 1])} ${state.page === 0 ? 'disabled' : ''}>上一页</button><span>第 ${state.page + 1} / ${pages} 页</span><button type="button" class="a-btn" ${uiAction('renderBangumiImportPreview', [state.page + 1])} ${state.page + 1 >= pages ? 'disabled' : ''}>下一页</button>`;
    document.getElementById('bgmImportConfirm').hidden = false; bangumiImportBusy(state, state.busy);
}

async function confirmBangumiImport() {
    const state = bangumiImportState;
    if (!state?.preview || state.busy || state.external) return;
    if (typeof canLeaveAnimeMemory === 'function' && !canLeaveAnimeMemory()) return;
    if (!state.request) {
        if (typeof crypto?.randomUUID !== 'function') { bangumiImportMessage('无法生成安全编号，请使用localhost或HTTPS后重试。'); return; }
        state.request = { previewKey: state.preview.previewKey, requestKey: crypto.randomUUID(),
            importScores: document.getElementById('bgmImportScores').checked, importTags: document.getElementById('bgmImportTags').checked };
    }
    const operation = typeof beginAnimeMemoryOperation === 'function' ? beginAnimeMemoryOperation('正在确认收藏导入，请等待完成。') : null;
    bangumiImportBusy(state, 'confirm'); bangumiImportMessage('正在确认导入，请等待；重复重试将使用同一编号。');
    try {
        const result = await fetchApi('/api/bangumi/import/confirm', { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(state.request), returnError: true, returnConflict: true });
        if (bangumiImportState !== state) return;
        if (result?.code !== 200) { bangumiImportMessage((result?.message || '确认失败或结果未知') + '；可按原选项重试，或重新读取核对。'); return; }
        const data = result.data;
        state.preview = null; document.getElementById('bgmImportPages').innerHTML = '';
        document.getElementById('bgmImportRows').innerHTML = '<p class="memory-note">已确认。可通过“查看外部收藏”回看原始资料与评论。</p>';
        bangumiImportMessage(`导入完成：新增 ${data.created}，未新增 ${data.skipped}（其中需核对 ${data.needsReview}），外部收藏保留 ${data.externalSaved} 条。`);
        closeDetailModal(true); await Promise.allSettled([performSearch(), updateStats(false), loadDailyWatch(), loadHeatmap()]);
    } catch { if (bangumiImportState === state) bangumiImportMessage('确认失败或结果未知；可按原选项重试，避免重复新增。'); }
    finally {
        if (typeof endAnimeMemoryOperation === 'function') endAnimeMemoryOperation(operation);
        if (bangumiImportState === state) bangumiImportBusy(state, null);
    }
}

async function showBangumiCollections(page = 0) {
    const state = bangumiImportState;
    if (!state || state.busy) return;
    bangumiImportBusy(state, 'read'); bangumiImportMessage('正在读取已保存的外部收藏…');
    try {
        const result = await fetchApi(`/api/bangumi/collections?page=${page}&size=20`, { returnError: true });
        if (bangumiImportState !== state) return;
        if (result?.code !== 200) { bangumiImportMessage(result?.message || '外部收藏读取失败，请重试。'); return; }
        state.external = result.data; const data = state.external;
        document.getElementById('bgmImportRows').innerHTML = data.entries.map((record, index) => bangumiImportRowHtml(record.source,
            '独立外部收藏；不会自动转换成感想或实际观看日期。', index, record)).join('') || '<p class="memory-note">还没有外部收藏。</p>';
        document.getElementById('bgmImportPages').innerHTML = `<button type="button" class="a-btn" ${uiAction('showBangumiCollections', [page - 1])} ${page <= 0 ? 'disabled' : ''}>上一页</button><span>共 ${data.total} 条 · 第 ${page + 1} 页</span><button type="button" class="a-btn" ${uiAction('showBangumiCollections', [page + 1])} ${(page + 1) * 20 >= data.total ? 'disabled' : ''}>下一页</button>`
            + (state.preview ? '<button type="button" class="a-btn" data-ui-click="renderBangumiImportPreview">回到导入预览</button>' : '');
        document.getElementById('bgmImportConfirm').hidden = true; bangumiImportMessage(`已保存的外部收藏 ${data.total} 条；删除这里的记录不会删除本地作品。`);
    } catch { if (bangumiImportState === state) bangumiImportMessage('外部收藏读取失败，请重试。'); }
    finally { if (bangumiImportState === state) bangumiImportBusy(state, null); }
}

async function copyBangumiComment(index) {
    const state = bangumiImportState;
    const source = state?.external ? state.external.entries[index]?.source : state?.preview?.rows[index]?.source;
    if (!source?.comment) return;
    try { await navigator.clipboard.writeText(source.comment); toast('已复制收藏评论，可在感想中自行选择语境和日期。', 'success'); }
    catch { toast('无法自动复制，可展开后手动选择原文。', 'error'); }
}

async function removeBangumiCollection(key, version) {
    const state = bangumiImportState;
    if (!state || state.busy || !confirm('删除这条外部收藏？本地作品保留，删除后它可能再次出现在推荐中。')) return;
    bangumiImportBusy(state, 'confirm');
    try {
        const result = await fetchApi(`/api/bangumi/collections/${encodeURIComponent(key)}?expectedVersion=${version}`,
            { method: 'DELETE', returnError: true, returnConflict: true });
        if (bangumiImportState !== state) return;
        bangumiImportBusy(state, null);
        if (result?.code === 200) await showBangumiCollections(state.external?.page || 0);
        else bangumiImportMessage(result?.message || '删除失败，请刷新后核对。');
    } catch { if (bangumiImportState === state) bangumiImportMessage('删除失败，请刷新后核对。'); }
    finally { if (bangumiImportState === state) bangumiImportBusy(state, null); }
}
