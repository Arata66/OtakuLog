let recommendationView = { generation: 0, controller: null, result: null, requests: new Map(), busy: false, reloadPending: false, observer: null, shown: new Set() };
let recommendationModal = null;

async function loadRecommendationPanel() {
    const grid = document.getElementById('recGrid'); if (!grid) return;
    if (recommendationView.busy) {
        recommendationView.reloadPending = true; ++recommendationView.generation;
        recommendationView.result = null; recommendationView.observer?.disconnect();
        grid.innerHTML = inlineStateHtml('loading', '正在更新推荐条件...', '反馈确认后会重新核对候选。');
        return;
    }
    const charts = document.getElementById('tab-charts'); if (charts && !charts.classList.contains('active')) return;
    const generation = ++recommendationView.generation;
    recommendationView.controller?.abort(); const controller = new AbortController(); recommendationView.controller = controller;
    recommendationView.observer?.disconnect();
    grid.innerHTML = inlineStateHtml('loading', '正在核对推荐...', '只显示符合当前条件的候选。');
    try {
        const recent = !!document.getElementById('recIncludeRecent')?.checked;
        const query = typeof recommendationQuery === 'function' ? recommendationQuery() : '';
        const response = await fetchApi('/api/recommendations?includeRecent=' + recent + query, { signal: controller.signal });
        if (generation !== recommendationView.generation || (charts && !charts.classList.contains('active'))) return;
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '推荐核对失败，请重试。');
        recommendationView.result = response.data; recommendationView.requests.clear(); recommendationView.shown.clear(); renderRecommendationRows();
        const data = response.data, entries = data.entries || [];
        const summary = document.getElementById('recSummary');
        if (summary) {
            const settings = data.settings || {}, reasons = { YEAR: '年份不符', UNKNOWN_YEAR: '年份未知', TAG: '排除标签', TRACKED: '已在库', WATCHED: '已看', FEEDBACK: '不感兴趣', RECENT: '近期展示', EXTERNAL: '外部在看或搁置／放弃', NO_MATCH: '题材依据不足', SEED: '种子自身', TASTE_TAG: '口味避开标签' };
            const counts = Object.entries(data.excludedCounts || {}).map(([key, count]) => `${reasons[key] || '未核对'} ${count}`);
            summary.textContent = [`${settings.minYear || '不限'} — ${settings.maxYear || '不限'}`, settings.excludedTags?.length ? '排除：' + settings.excludedTags.join('、') : '', ...counts,
                data.unverified ? `详情未核对 ${data.unverified}` : '', data.sourceErrors ? `部分来源失败 ${data.sourceErrors}` : ''].filter(Boolean).join(' · ');
        }
        const evidence = document.getElementById('recEvidence');
        if (evidence) evidence.textContent = [data.context?.seedName ? '种子：' + data.context.seedName : '', data.seedTags?.length ? '本轮实际标签：' + data.seedTags.join('、') : '',
            ...(data.context?.bases || []).map(b => `${b.source.direction === 'LIKE' ? '喜欢' : '避开'} · ${b.source.factor}（${b.source.animeName}）→ ${b.tags.join('、')}`)].filter(Boolean).join('\n');
        if (entries.length && data.batchKey) await observeRecommendationShown(data, generation);
    } catch (error) {
        if (generation !== recommendationView.generation || controller.signal.aborted) return;
        recommendationView.result = null; grid.innerHTML = inlineStateHtml('error', '推荐加载失败', error.message);
    }
}

async function observeRecommendationShown(data, generation) {
    recommendationView.observer?.disconnect();
    if (typeof IntersectionObserver !== 'function') { await recordRecommendationShown(data.entries.map(item => item.id), data, generation); return; }
    recommendationView.observer = new IntersectionObserver(async entries => {
        const visible = entries.filter(entry => entry.isIntersecting).map(entry => Number(entry.target.dataset.subject));
        if (visible.length) await recordRecommendationShown(visible, data, generation);
    }, { threshold: 0.01 });
    document.getElementById('recGrid').querySelectorAll('[data-subject]').forEach(card => recommendationView.observer.observe(card));
}
async function recordRecommendationShown(ids, data, generation) {
    const charts = document.getElementById('tab-charts');
    if (generation !== recommendationView.generation || (charts && !charts.classList.contains('active'))) return;
    const fresh = [...new Set(ids)].filter(id => data.entries.some(item => item.id === id) && !recommendationView.shown.has(id));
    if (!fresh.length) return; fresh.forEach(id => recommendationView.shown.add(id));
    try {
        const response = await fetchApi('/api/recommendations/shown', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ batchKey: data.batchKey, subjectIds: fresh }) });
        if ((!response || response.code !== 200) && generation === recommendationView.generation) throw new Error('近期展示未保存；当前候选仍可查看，下次可能重复。');
    } catch (error) { if (generation === recommendationView.generation) { fresh.forEach(id => recommendationView.shown.delete(id)); document.getElementById('recStatus').textContent = error.message; } }
}

function renderRecommendationRows() {
    const grid = document.getElementById('recGrid'), data = recommendationView.result; if (!grid || !data) return;
    document.getElementById('recStatus').textContent = '';
    if (!data.entries?.length) {
        grid.innerHTML = data.status === 'BASIS_CONFLICT' ? inlineStateHtml('empty', '喜欢与避开同一标签，请核对本轮选择', '两边都保留；取消其中一条勾选后再开始推荐。')
            : data.status === 'COLD_START_TASTE' ? inlineStateHtml('empty', '先关联并勾选喜欢观点', '打开推荐依据，明确把当前确认观点关联到标签或作品；不会回退全部收藏。')
            : data.status === 'COLD_START_TAGS' ? inlineStateHtml('empty', '输入喜欢的标签后开始推荐', '本轮最多3个标签，永久排除继续有效。')
            : data.status === 'COLD_START' ? inlineStateHtml('empty', '先选择几个喜欢的标签', '打开推荐条件，或为喜欢的作品评分。未评分收藏不会当作偏好。')
            : inlineStateHtml('empty', '当前条件下没有新候选', '可以修改条件，或明确勾选“查看近期推荐”；永久排除仍然有效。');
        return;
    }
    grid.innerHTML = data.entries.map((item, index) => {
        const pending = recommendationView.requests.get(item.id);
        const image = item.image ? `<img class="browse-cover" src="${esc(item.image)}" alt="" onerror="this.hidden=true">` : `<div class="browse-cover-empty">${esc((item.nameCn || item.name || '').charAt(0))}</div>`;
        const actions = pending ? `<button class="btn-sm" onclick="retryRecommendationFeedback(${index})">重试提交反馈</button>`
            : `<button class="btn-sm" onclick="feedbackRecommendation(${index},'NOT_INTERESTED')">不感兴趣</button><button class="btn-sm" onclick="feedbackRecommendation(${index},'WATCHED')">已看过</button>`;
        return `<article class="browse-card rec-card" data-subject="${item.id}">${image}<div class="browse-info"><button class="rec-name" onclick="selectRecommendation(${index})">${esc(item.nameCn || item.name)}</button>
            <div class="browse-meta">${item.score ? 'Bangumi评分 ' + esc(item.score) : '未评分'} ${esc(item.date || '首播日期未知')}</div>
            <div class="browse-reason">${esc(item.reason || '')}</div><div class="rec-badges">${item.watched ? '已看 · ' : ''}${item.tracked ? '已在库 · ' : ''}${item.recent ? '近期展示' : ''}</div>
            <div class="rec-actions">${actions}</div></div></article>`;
    }).join('');
}

function selectRecommendation(index) {
    const item = recommendationView.result?.entries?.[index]; if (!item) return;
    document.getElementById('animeName').value = item.nameCn || item.name; searchBangumi();
}

async function feedbackRecommendation(index, kind) {
    if (recommendationView.busy) return;
    const data = recommendationView.result, item = data?.entries?.[index]; if (!item) return;
    let request = recommendationView.requests.get(item.id);
    if (!request) {
        request = { requestKey: crypto.randomUUID(), batchKey: data.batchKey, subjectId: item.id, kind };
        recommendationView.requests.set(item.id, request);
    }
    recommendationView.busy = true; recommendationView.controller?.abort(); ++recommendationView.generation;
    recommendationView.observer?.disconnect();
    document.getElementById('recGrid').querySelectorAll('button').forEach(button => button.disabled = true);
    try {
        const response = await fetchApi('/api/recommendations/feedback', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request) });
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '反馈未确认，保留原请求供重试。');
        recommendationView.requests.delete(item.id);
        if (response.data?.active) data.entries = data.entries.filter(row => row.id !== item.id);
        toast(response.data?.active ? '已保存推荐反馈，可在反馈列表撤销。' : '这条反馈已被撤销，未重新启用。');
    } catch (error) { document.getElementById('recStatus').textContent = error.message; }
    finally {
        recommendationView.busy = false;
        if (recommendationView.reloadPending) {
            recommendationView.reloadPending = false; await loadRecommendationPanel();
        } else {
            const status = document.getElementById('recStatus').textContent; renderRecommendationRows();
            document.getElementById('recStatus').textContent = status;
            if (data.entries.length) await observeRecommendationShown(data, recommendationView.generation);
        }
    }
}
async function retryRecommendationFeedback(index) { return feedbackRecommendation(index, null); }

function showRecommendationModal(kind, body) {
    if (recommendationModal?.busy) return null;
    closeRecommendationModal(); const overlay = document.createElement('div'); overlay.id = kind === 'settings' ? 'recommendationSettingsModal' : kind === 'bases' ? 'recommendationBasesModal' : 'recommendationFeedbackModal'; overlay.className = 'modal-overlay';
    const title = kind === 'settings' ? '推荐条件' : kind === 'bases' ? '推荐依据' : '推荐反馈';
    overlay.innerHTML = `<section class="rec-dialog" role="dialog" aria-modal="true" aria-label="${title}"><div class="rec-dialog-heading"><h3>${title}</h3><button class="btn-sm" onclick="closeRecommendationModal()" aria-label="关闭推荐面板">关闭</button></div>${body}<p id="recModalStatus" role="status"></p></section>`;
    document.body.appendChild(overlay); recommendationModal = { kind, overlay, busy: false, settings: null, page: 0, entries: [], origin: document.activeElement };
    if (typeof trapFocus === 'function') recommendationModal.cleanup = trapFocus(overlay, closeRecommendationModal);
    return recommendationModal;
}
function closeRecommendationModal() {
    if (!recommendationModal || recommendationModal.busy) return;
    const modal = recommendationModal; recommendationModal = null;
    if (typeof modal.cleanup === 'function') modal.cleanup(); modal.overlay.remove();
    if (typeof restoreFocus === 'function') restoreFocus(modal.origin);
}
function recommendationModalBusy(busy) {
    if (!recommendationModal) return; recommendationModal.busy = busy;
    recommendationModal.overlay.querySelectorAll('button,input,textarea,select').forEach(field => field.disabled = busy);
    const save = document.getElementById('recSaveSettings'); if (save) save.disabled = busy;
}

async function openRecommendationSettings() {
    const modal = showRecommendationModal('settings', `<p class="rec-help">条件会永久保存，结果少时不会自动放宽。</p><div class="rec-years"><label>起始年份<input id="recMinYear" type="number" min="1" max="9999" placeholder="不限"></label><label>结束年份<input id="recMaxYear" type="number" min="1" max="9999" placeholder="不限"></label></div>
        <label>永不推荐这些标签<textarea id="recExcludedTags" rows="2" placeholder="例如：机战，每个标签用逗号分开"></textarea></label><p class="rec-help">最多50个，按作品真实标签精确匹配，不自动扩大题材。</p>
        <label>用这些喜欢的标签找<input id="recRecallTags" placeholder="例如：剧情、奇幻，最多3个"></label><p class="rec-help">留空时，使用评分至少7分且未放弃作品的标签。</p>
        <label class="rec-check"><input id="recIncludeTracked" type="checkbox">包括已在库／已看作品</label><p class="rec-help">外部在看、搁置或放弃仍会避开；不感兴趣始终排除。</p>
        <div class="rec-actions"><button id="recSaveSettings" class="btn-sm" onclick="saveRecommendationSettings()">保存条件并推荐</button><button class="btn-sm" onclick="readRecommendationSettings()">重新读取条件</button></div>`);
    if (modal) await readRecommendationSettings();
}
async function readRecommendationSettings() {
    const modal = recommendationModal; if (!modal || modal.kind !== 'settings' || modal.busy) return;
    recommendationModalBusy(true);
    try {
        const response = await fetchApi('/api/recommendations/settings');
        if (recommendationModal !== modal) return;
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '条件读取失败。');
        modal.settings = response.data;
        for (const [id, value] of [['recMinYear',response.data.minYear ?? ''],['recMaxYear',response.data.maxYear ?? ''],['recExcludedTags',response.data.excludedTags.join('、')],['recRecallTags',response.data.recallTags.join('、')]]) document.getElementById(id).value = value;
        document.getElementById('recIncludeTracked').checked = response.data.includeTracked;
        document.getElementById('recModalStatus').textContent = '';
    } catch (error) { if (recommendationModal === modal) document.getElementById('recModalStatus').textContent = error.message; }
    finally { if (recommendationModal === modal) recommendationModalBusy(false); }
}
async function saveRecommendationSettings() {
    const modal = recommendationModal; if (!modal?.settings || modal.busy) return;
    try {
        const year = id => { const text = String(document.getElementById(id).value).trim(); if (!text) return null; if (!/^\d{1,4}$/.test(text) || Number(text) < 1) throw new Error('年份须为1至9999。'); return Number(text); };
        const tags = id => document.getElementById(id).value.split(/[,，、\n]/).map(value => value.trim()).filter(Boolean);
        const body = { ...modal.settings, minYear: year('recMinYear'), maxYear: year('recMaxYear'), excludedTags: tags('recExcludedTags'), recallTags: tags('recRecallTags'), includeTracked: document.getElementById('recIncludeTracked').checked };
        if (body.minYear && body.maxYear && body.minYear > body.maxYear) throw new Error('起始年份不能大于结束年份。');
        recommendationModalBusy(true);
        const response = await fetchApi('/api/recommendations/settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '条件保存未确认，请重新读取核对。');
        recommendationModalBusy(false); closeRecommendationModal(); await loadRecommendationPanel();
    } catch (error) { if (recommendationModal === modal) document.getElementById('recModalStatus').textContent = error.message; }
    finally { if (recommendationModal === modal) recommendationModalBusy(false); }
}

async function openRecommendationFeedback() {
    const modal = showRecommendationModal('feedback', `<p class="rec-help">这些标记只影响推荐，不生成观看日期。撤销后仍保留防重历史。</p><div id="recFeedbackRows"></div><div class="rec-actions"><button id="recFeedbackPrevious" class="btn-sm" onclick="recommendationFeedbackPage(-1)">上一页</button><button id="recFeedbackNext" class="btn-sm" onclick="recommendationFeedbackPage(1)">下一页</button></div>`);
    if (modal) await readRecommendationFeedback();
}
async function readRecommendationFeedback() {
    const modal = recommendationModal; if (!modal || modal.kind !== 'feedback' || modal.busy) return;
    recommendationModalBusy(true);
    try {
        const response = await fetchApi('/api/recommendations/feedback?page=' + modal.page + '&size=20');
        if (recommendationModal !== modal) return;
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '反馈读取失败。');
        modal.entries = response.data.entries;
        document.getElementById('recFeedbackRows').innerHTML = modal.entries.length ? modal.entries.map((entry,index) => `<div class="rec-feedback-row"><span>${esc(entry.name)} <small>${entry.kind === 'WATCHED' ? '已看过' : '不感兴趣'}</small></span><button class="btn-sm" onclick="removeRecommendationFeedback(${index})">撤销</button></div>`).join('') : '<p>没有活动的推荐反馈。</p>';
        recommendationModalBusy(false);
        document.getElementById('recFeedbackPrevious').disabled = modal.page === 0;
        document.getElementById('recFeedbackNext').disabled = (modal.page + 1) * 20 >= response.data.total;
        document.getElementById('recModalStatus').textContent = '';
    } catch (error) { if (recommendationModal === modal) { recommendationModalBusy(false); document.getElementById('recModalStatus').textContent = error.message; } }
}
async function recommendationFeedbackPage(delta) { if (!recommendationModal || recommendationModal.busy) return; recommendationModal.page = Math.max(0,recommendationModal.page + delta); await readRecommendationFeedback(); }
async function removeRecommendationFeedback(index) {
    const modal = recommendationModal, entry = modal?.entries?.[index]; if (!entry || modal.busy || !confirm('撤销这条推荐反馈？不会改动观看记录。')) return;
    recommendationModalBusy(true);
    try {
        const response = await fetchApi('/api/recommendations/feedback/' + encodeURIComponent(entry.key) + '?expectedVersion=' + entry.version, { method: 'DELETE' });
        if (!response || response.code !== 200) throw new Error(response?.msg || response?.message || '撤销未确认，请刷新后核对。');
        recommendationModalBusy(false); await readRecommendationFeedback(); await loadRecommendationPanel();
    } catch (error) { if (recommendationModal === modal) { recommendationModalBusy(false); document.getElementById('recModalStatus').textContent = error.message; } }
}
