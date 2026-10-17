let recommendationBasisPage = null, recommendationBasisSelection = new Set();

function recommendationQuery() {
    const mode = document.getElementById('recMode')?.value || 'AUTO';
    const params = new URLSearchParams({ mode });
    if (mode === 'TAGS' || mode === 'SEED') {
        const tags = (document.getElementById('recModeTags')?.value || '').split(/[,，、\n]/).map(t => t.trim()).filter(Boolean);
        if (tags.length > 3) throw new Error('本轮最多选择3个标签。'); tags.forEach(t => params.append('tags', t));
    }
    if (mode === 'SEED') {
        const id = String(document.getElementById('recSeedId')?.value || '').trim();
        if (!/^\d{1,10}$/.test(id) || Number(id) < 1 || Number(id) > 2147483647) throw new Error('请提供有效的Bangumi作品编号，或从作品详情选择“找类似这部”。');
        params.set('subjectId', id);
    }
    if (mode === 'TASTE') [...recommendationBasisSelection].forEach(key => params.append('basisKeys', key));
    return '&' + params.toString();
}
function invalidateRecommendationChoice() {
    ++recommendationView.generation; recommendationView.controller?.abort(); recommendationView.observer?.disconnect(); recommendationView.result = null;
    if (recommendationView.busy) recommendationView.reloadPending = true;
    const grid = document.getElementById('recGrid'); if (grid) grid.innerHTML = inlineStateHtml('empty', '选择本轮依据后开始推荐', '永久条件继续有效。');
    for (const id of ['recEvidence', 'recSummary', 'recStatus']) { const field = document.getElementById(id); if (field) field.textContent = ''; }
}
async function changeRecommendationMode() {
    invalidateRecommendationChoice(); const mode = document.getElementById('recMode')?.value || 'AUTO';
    document.getElementById('recModeTagsField').hidden = mode !== 'TAGS' && mode !== 'SEED';
    document.getElementById('recSeedField').hidden = mode !== 'SEED'; document.getElementById('recBasisSelection').hidden = mode !== 'TASTE';
    if (mode === 'TASTE') await readRecommendationBasisSelection();
}
function useRecommendationSeed(id) {
    if (typeof closeDetailModal === 'function' && closeDetailModal() === false) return;
    document.getElementById('recMode').value = 'SEED'; document.getElementById('recSeedId').value = String(id); document.getElementById('recModeTags').value = '';
    changeRecommendationMode(); switchTab('charts');
}
function applyRecommendationBasisPage(data) {
    const selectedEntries = page => [...recommendationBasisSelection].map(key => page?.entries.find(e => e.data.key === key));
    const changed = JSON.stringify(selectedEntries(recommendationBasisPage)) !== JSON.stringify(selectedEntries(data));
    recommendationBasisPage = data; const active = data.entries.filter(e => e.state === 'ACTIVE');
    const valid = new Set(active.map(e => e.data.key)); recommendationBasisSelection = new Set([...recommendationBasisSelection].filter(key => valid.has(key)));
    if (changed && document.getElementById('recMode')?.value === 'TASTE') invalidateRecommendationChoice();
    renderRecommendationBasisSelection();
}
function renderRecommendationBasisSelection() {
    const host = document.getElementById('recBasisSelection'); if (!host || !recommendationBasisPage) return;
    const entries = recommendationBasisPage.entries.filter(e => e.state === 'ACTIVE');
    host.innerHTML = '<p class="rec-help">只使用本轮勾选的有效关联，最多3条喜欢与50条避开；同标签正负同时选中会提示冲突。</p>' + (entries.length ? entries.map(e => `<label class="rec-check rec-basis-choice"><input type="checkbox" data-key="${esc(e.data.key)}" ${recommendationBasisSelection.has(e.data.key) ? 'checked' : ''} data-ui-change="toggleRecommendationBasis"><span>${e.data.direction === 'LIKE' ? '喜欢' : '避开'} · ${esc(e.source.factor)} → ${esc(e.data.tag || '作品 ' + e.data.subjectId)}</span></label>`).join('') : '<p class="rec-help">还没有有效关联。先在“推荐依据”里把确认观点关联到标签或作品。</p>');
}
function toggleRecommendationBasis(key, enabled) { if (enabled) recommendationBasisSelection.add(key); else recommendationBasisSelection.delete(key); invalidateRecommendationChoice(); }
async function readRecommendationBasisSelection() {
    try {
        const response = await fetchApi('/api/recommendations/bases', { returnError: true });
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '推荐依据读取失败。'); applyRecommendationBasisPage(response.data);
    } catch (error) { document.getElementById('recStatus').textContent = error.message; }
}
async function openRecommendationBases(focusKey) {
    const modal = showRecommendationModal('bases', '<p class="rec-help">选择已确认的观点，明确关联标签或作品；先预览影响，再保存。来源变动后暂停使用。</p><div id="recBasisForm"></div><div id="recBasisRows"></div><button class="btn-sm" data-ui-click="readRecommendationBases">重新读取依据</button>');
    if (!modal) return; modal.focusKey = focusKey || ''; modal.previewGeneration = 0; modal.draft = null; modal.requestKey = null;
    await readRecommendationBases();
}
async function readRecommendationBases() {
    const modal = recommendationModal; if (!modal || modal.kind !== 'bases' || modal.busy) return; recommendationModalBusy(true);
    try {
        const response = await fetchApi('/api/recommendations/bases', { returnError: true });
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '推荐依据读取失败。');
        if (recommendationModal !== modal) return; applyRecommendationBasisPage(response.data); modal.pageData = response.data;
        document.getElementById('recBasisForm').innerHTML = response.data.sources.length ? `<label>当前确认观点<select id="recBasisSource" data-ui-change="selectRecommendationBasisSource">${response.data.sources.map(s => `<option value="${esc(s.insightKey)}">${s.direction === 'LIKE' ? '喜欢' : '不喜欢'} · ${esc(s.animeName)} · ${esc(s.factor)}</option>`).join('')}</select></label><div id="recBasisEvidence"></div>
            <label>关联方式<select id="recBasisKind" data-ui-change="recommendationBasisKindChanged"><option value="TAG">明确标签</option><option id="recBasisSeedOption" value="SEED">一部喜欢的作品</option></select></label>
            <label id="recBasisTagField">Bangumi标签<input id="recBasisTag" placeholder="例如：剧情" data-ui-input="recommendationBasisFormChanged"></label><label id="recBasisSeedField" hidden>Bangumi作品编号<input id="recBasisSeed" type="number" min="1" data-ui-input="recommendationBasisFormChanged"></label>
            <div class="rec-actions"><button class="btn-sm" data-ui-click="previewRecommendationBasis">预览关联影响</button><button id="recBasisSave" class="btn-sm" data-ui-click="saveRecommendationBasis" disabled>确认保存关联</button></div>` : '<p class="rec-help">暂无当前有效的确认观点。先在观影记忆中整理并确认，未确认和已失效观点不会列入。</p>';
        renderRecommendationBasisRows(modal);
        if (response.data.sources.length) { document.getElementById('recBasisSource').value = response.data.sources.some(s => s.insightKey === modal.focusKey) ? modal.focusKey : response.data.sources[0].insightKey; selectRecommendationBasisSource(); }
        document.getElementById('recModalStatus').textContent = '';
    } catch (error) { if (recommendationModal === modal) document.getElementById('recModalStatus').textContent = error.message; }
    finally { if (recommendationModal === modal) { recommendationModalBusy(false); const save = document.getElementById('recBasisSave'); if (save) save.disabled = !modal.draft; } }
}
function renderRecommendationBasisRows(modal) {
    document.getElementById('recBasisRows').innerHTML = '<h4>已保存的关联</h4>' + (modal.pageData.entries.map((e, index) => `<div class="rec-feedback-row"><span>${e.data.direction === 'LIKE' ? '喜欢' : '避开'} · ${esc(e.data.tag || '作品 ' + e.data.subjectId)}<small>${e.state === 'ACTIVE' ? '来源有效' : e.state === 'DISABLED' ? '已停用' : '来源已变化或缺失，暂停使用'}</small></span>${e.data.enabled ? `<button class="btn-sm" ${uiAction('disableRecommendationBasis', [index])}>停用</button>` : ''}</div>`).join('') || '<p class="rec-help">暂无保存的关联。</p>');
}
function selectRecommendationBasisSource() {
    const modal = recommendationModal; if (!modal?.pageData) return;
    const source = modal.pageData.sources.find(s => s.insightKey === document.getElementById('recBasisSource').value); if (!source) return;
    modal.source = source; modal.existing = modal.pageData.entries.find(e => e.data.insightKey === source.insightKey)?.data || null; modal.requestKey = modal.existing?.key || crypto.randomUUID();
    document.getElementById('recBasisEvidence').innerHTML = `<p>${source.direction === 'LIKE' ? '喜欢' : '不喜欢'} · ${esc(source.animeName)} · ${esc(source.factor)}</p><p>${esc(source.statement)}</p><blockquote class="taste-plain">${esc(source.quote)}</blockquote>`;
    document.getElementById('recBasisSeedOption').disabled = source.direction !== 'LIKE';
    document.getElementById('recBasisKind').value = source.direction === 'LIKE' ? modal.existing?.kind || 'TAG' : 'TAG';
    document.getElementById('recBasisTag').value = modal.existing?.tag || ''; document.getElementById('recBasisSeed').value = modal.existing?.subjectId || '';
    recommendationBasisKindChanged();
}
function recommendationBasisKindChanged() { const seed = document.getElementById('recBasisKind').value === 'SEED'; document.getElementById('recBasisTagField').hidden = seed; document.getElementById('recBasisSeedField').hidden = !seed; recommendationBasisFormChanged(); }
function recommendationBasisFormChanged() { const modal = recommendationModal; if (!modal || modal.kind !== 'bases') return; ++modal.previewGeneration; modal.draft = null; const save = document.getElementById('recBasisSave'); if (save) save.disabled = true; document.getElementById('recModalStatus').textContent = '修改后请重新预览关联影响。'; }
function recommendationBasisInput() {
    const modal = recommendationModal, source = modal?.source; if (!source) throw new Error('请选择有效确认观点。');
    const kind = document.getElementById('recBasisKind').value, seed = String(document.getElementById('recBasisSeed').value).trim(), tag = document.getElementById('recBasisTag').value.trim();
    if (kind === 'TAG' && (!tag || tag.length > 200)) throw new Error('标签须为1至200字。');
    if (kind === 'SEED' && (source.direction !== 'LIKE' || !/^\d{1,10}$/.test(seed) || Number(seed) < 1 || Number(seed) > 2147483647)) throw new Error('作品关联需要喜欢观点和有效Bangumi编号。');
    return { key: modal.requestKey, insightKey: source.insightKey, sourceVersion: source.sourceVersion, insightVersion: source.insightVersion, sourceFingerprint: source.sourceFingerprint,
        direction: source.direction, kind, tag: kind === 'TAG' ? tag : null, subjectId: kind === 'SEED' ? Number(seed) : null, enabled: true, expectedVersion: modal.existing?.version || 0 };
}
async function previewRecommendationBasis() {
    const modal = recommendationModal; if (!modal || modal.kind !== 'bases' || modal.busy) return;
    const generation = ++modal.previewGeneration;
    try {
        const body = recommendationBasisInput(); recommendationModalBusy(true);
        const response = await fetchApi('/api/recommendations/bases/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), returnError: true });
        if (recommendationModal !== modal || generation !== modal.previewGeneration) return;
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '关联预览失败。');
        if (JSON.stringify(body) !== JSON.stringify(recommendationBasisInput())) throw new Error('输入已变化，请重新预览。');
        modal.draft = body; document.getElementById('recModalStatus').textContent = [response.data.effect, response.data.subjectName, response.data.tags?.length ? '实际使用标签：' + response.data.tags.join('、') : ''].filter(Boolean).join('\n');
    } catch (error) { if (recommendationModal === modal) document.getElementById('recModalStatus').textContent = error.message; }
    finally { if (recommendationModal === modal) { recommendationModalBusy(false); document.getElementById('recBasisSave').disabled = !modal.draft; } }
}
async function saveRecommendationBasis() {
    const modal = recommendationModal; if (!modal || modal.kind !== 'bases' || modal.busy) return;
    try {
        if (!modal.draft || JSON.stringify(modal.draft) !== JSON.stringify(recommendationBasisInput())) throw new Error('输入已变化，请重新预览关联影响。');
        recommendationModalBusy(true);
        const response = await fetchApi('/api/recommendations/bases', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(modal.draft), returnError: true });
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '保存未确认，保留原请求供重试。');
        modal.focusKey = modal.source.insightKey; modal.draft = null; recommendationModalBusy(false); await readRecommendationBases();
        document.getElementById('recModalStatus').textContent = '关联已保存；在口味模式勾选后使用。'; await loadRecommendationPanel();
    } catch (error) { if (recommendationModal === modal) document.getElementById('recModalStatus').textContent = error.message; }
    finally { if (recommendationModal === modal) { recommendationModalBusy(false); const save = document.getElementById('recBasisSave'); if (save) save.disabled = !modal.draft; } }
}
async function disableRecommendationBasis(index) {
    const modal = recommendationModal, entry = modal?.pageData?.entries?.[index]; if (!entry || modal.busy) return; recommendationModalBusy(true);
    try {
        const response = await fetchApi('/api/recommendations/bases/' + encodeURIComponent(entry.data.key) + '?expectedVersion=' + entry.data.version, { method: 'DELETE', returnError: true });
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '停用未确认，请重新读取核对。');
        recommendationModalBusy(false); await readRecommendationBases(); await loadRecommendationPanel();
    } catch (error) { if (recommendationModal === modal) document.getElementById('recModalStatus').textContent = error.message; }
    finally { if (recommendationModal === modal) recommendationModalBusy(false); }
}
