const tasteAspects = { NARRATIVE: '叙事', CHARACTER: '人物', AUDIOVISUAL: '视听', THEME: '主题', PACING: '节奏', OTHER: '其他' };
const tasteQuoteFields = { CONTENT: '感想原文', LIKED: '打动我的地方', DISLIKED: '不满意的地方' };
const tasteStatuses = { PENDING: '待核对', CONFIRMED: '已确认', REJECTED: '已拒绝' };
const tasteFormFields = ['quoteField', 'quote', 'statement', 'direction', 'aspect', 'factor'];
let tasteInstance = null, tasteProfileGeneration = 0, tasteUsageGeneration = 0;

function tasteCurrent(state) {
    return tasteInstance === state && document.getElementById('tastePanel') === state.panel;
}
function tasteInput(field) { return document.getElementById('taste' + field[0].toUpperCase() + field.slice(1)); }
function tasteDraft() { return Object.fromEntries(tasteFormFields.map(field => [field, tasteInput(field)?.value || ''])); }
function tasteSelection() { return Object.fromEntries(['content', 'liked', 'disliked'].map(field => [field, document.getElementById('tasteSelect' + field[0].toUpperCase() + field.slice(1))?.value || ''])); }
function tasteDirty(state) {
    return !!state.requestPending || !!(state.callResult && state.callResult.status !== 'SUCCEEDED')
        || (state.loaded && (state.draftBaseline !== JSON.stringify(tasteDraft()) || state.selectionBaseline !== JSON.stringify(tasteSelection())));
}
function tasteStatus(state, text) {
    const node = document.getElementById('tasteStatus'); if (tasteCurrent(state) && node) node.textContent = text;
}

function canLeaveTasteMemory() {
    const state = tasteInstance;
    if (!state || !tasteCurrent(state)) return true;
    if (state.busy) { tasteStatus(state, state.operationMessage || '正在整理或保存观点，请等待完成后再离开。'); return false; }
    return !tasteDirty(state) || confirm('存在未保存的观点或AI选文、尚未核对的调用编号，离开会放弃这些输入，是否继续？');
}
function closeTasteMemory(force = false) {
    if (!force && !canLeaveTasteMemory()) return false;
    tasteInstance?.panel?.remove(); tasteInstance = null; return true;
}
function tasteSetBusy(state, busy) {
    state.busy = busy;
    if (busy) {
        state.controls = [...state.panel.querySelectorAll('button, input, textarea, select')].map(control => [control, control.disabled]);
        state.controls.forEach(([control]) => { control.disabled = true; });
    } else state.controls?.forEach(([control, disabled]) => { control.disabled = disabled; });
}
function beginTasteOperation(message) {
    const state = tasteInstance;
    if (!state || !tasteCurrent(state)) return null;
    const operation = { state, previousMessage: document.getElementById('tasteStatus')?.textContent || '' };
    state.operationMessage = message; tasteSetBusy(state, true); tasteStatus(state, message); return operation;
}
function endTasteOperation(operation) {
    if (!operation || !tasteCurrent(operation.state)) return;
    tasteSetBusy(operation.state, false); operation.state.operationMessage = null; tasteStatus(operation.state, operation.previousMessage);
}
function tasteOptions(values) { return Object.entries(values).map(([value, label]) => `<option value="${value}">${label}</option>`).join(''); }
function tasteMeta(source) {
    return `语境：${esc(memoryContexts[source.context] || source.context)} · 范围：${esc(source.scope || '未填写')} · 观看日期：${esc(source.watchedDate || '未填写')} · 书写：${esc(source.createdAt || '未填写')} · 原文版本 ${source.version}`;
}
function renderTastePanel(state) {
    const source = state.source;
    state.panel.innerHTML = `<div class="taste-heading"><div><h4>在这部作品中 · 整理观点</h4><p class="memory-note">手工整理即可使用。AI只提供待核对候选，确认后才进入本地总结。</p></div><button type="button" class="a-btn" onclick="closeTasteMemory()">收起整理</button></div>
        <p id="tasteSourceMeta" class="taste-meta">${tasteMeta(source)}</p><details class="taste-source"><summary>核对完整感想原文</summary><div id="tasteSourceBody">
        ${Object.entries(tasteQuoteFields).map(([key, label]) => `<h5>${label}</h5><p class="taste-plain">${esc(source[key.toLowerCase()] || '未填写')}</p>`).join('')}</div></details>
        <form id="tasteForm" class="taste-form" onsubmit="event.preventDefault();saveTasteInsight()"><h5 id="tasteFormTitle">手工添加观点</h5>
        <div class="taste-form-grid"><label>引用字段<select id="tasteQuoteField" class="app-input">${tasteOptions(tasteQuoteFields)}</select></label>
        <label>方向<select id="tasteDirection" class="app-input">${tasteOptions({ LIKE: '喜欢', DISLIKE: '不喜欢' })}</select></label>
        <label>方面<select id="tasteAspect" class="app-input">${tasteOptions(tasteAspects)}</select></label><label>具体因素<input id="tasteFactor" class="app-input" placeholder="如：人物选择、结尾音乐"></label></div>
        <label>引用连续原文<textarea id="tasteQuote" class="app-input" rows="3" placeholder="从上方原文中选择一段，最多800字符"></textarea></label>
        <label>我想表达的观点<textarea id="tasteStatement" class="app-input" rows="3" placeholder="最多500字符"></textarea></label>
        <div class="taste-actions"><button id="tasteSave" type="submit" class="a-btn">保存并确认观点</button><button type="button" class="a-btn" onclick="resetTasteForm()">取消编辑</button></div></form>
        <details class="taste-ai"><summary>使用AI整理（发送前可预览）</summary><p class="memory-note">将选中的原文、评价范围和语境发送给供应商。默认保留完整文字；总长须在1–6000字符内，超限请自己选取连续片段，留空的字段不发送。</p>
        ${['content', 'liked', 'disliked'].map(field => `<label>${tasteQuoteFields[field.toUpperCase()]}选文<textarea id="tasteSelect${field[0].toUpperCase() + field.slice(1)}" class="app-input" rows="3" oninput="invalidateTastePreview()">${esc(source[field] || '')}</textarea></label>`).join('')}
        <div class="taste-actions"><button type="button" class="a-btn" onclick="previewTasteAi()">预览发送内容与预算</button><button type="button" class="a-btn" onclick="cancelTastePreview()">取消预览</button></div><div id="tastePreview"></div><div id="tasteResult"></div><div id="tasteCallHistory"></div></details>
        <p id="tasteStatus" class="memory-note" role="status" aria-live="polite"></p><div id="tasteEntries"></div>
        <button type="button" class="a-btn" onclick="openTasteMemory(${state.animeId},${state.memoryId})">重新加载原文与观点</button>`;
    const wasBusy = state.busy; state.busy = false;
    state.loaded = true; resetTasteForm(false); renderTasteEntries(state); state.selectionBaseline = JSON.stringify(tasteSelection());
    if (wasBusy) { tasteSetBusy(state, true); tasteStatus(state, state.operationMessage || '正在执行操作，请等待完成。'); }
}
async function openTasteMemory(animeId, memoryId) {
    if (!canLeaveTasteMemory()) return;
    const mount = document.getElementById('tasteMount'); if (!mount) return;
    closeTasteMemory(true);
    const panel = document.createElement('section'); panel.id = 'tastePanel'; panel.className = 'taste-panel'; mount.appendChild(panel);
    const state = { animeId, memoryId, panel, loaded: false, busy: false, entries: [], editing: null, preview: null, requestPending: false, previousCalls: [] };
    tasteInstance = state; panel.innerHTML = '<p id="tasteStatus" class="memory-note" role="status">正在读取准确原文和观点…</p>';
    const [source, insights] = await Promise.all([fetchApi(`/api/anime/${animeId}/memories/${memoryId}`, { returnError: true }), fetchApi(`/api/anime/${animeId}/memories/${memoryId}/insights`, { returnError: true })]);
    if (!tasteCurrent(state)) return;
    if (source?.code !== 200 || insights?.code !== 200) {
        panel.innerHTML = `<p class="memory-note">原文或观点读取失败，未开始整理。</p><button type="button" class="a-btn" onclick="openTasteMemory(${animeId},${memoryId})">重试读取</button>`; return;
    }
    state.source = source.data; state.entries = insights.data.entries; renderTastePanel(state);
}
function renderTasteEntries(state) {
    if (!tasteCurrent(state)) return;
    document.getElementById('tasteEntries').innerHTML = state.entries.map(entry => `<article class="taste-entry" data-insight-id="${entry.id}" data-stale="${entry.stale}">
        <div class="taste-entry-heading"><span class="taste-badge">${entry.direction === 'LIKE' ? '喜欢' : '不喜欢'} · ${esc(tasteAspects[entry.aspect])} · ${esc(entry.factor)}</span><span>${tasteStatuses[entry.status]}${entry.stale ? ' · 原文已变动，已排除总结' : ''}</span></div>
        <p class="taste-plain">${esc(entry.statement)}</p><blockquote class="taste-plain">${esc(entry.quote)}</blockquote><p class="taste-meta">${esc(tasteQuoteFields[entry.quoteField])} · 来源版本 ${entry.sourceVersion} · ${entry.origin === 'AI' ? (entry.status === 'CONFIRMED' ? 'AI候选，用户已确认' : 'AI候选，' + tasteStatuses[entry.status]) : '手工观点'}</p>
        ${entry.origin === 'AI' ? `<details class="taste-suggestion"><summary>查看只读AI原建议</summary><p class="taste-plain">来源：AI\n引用字段：${esc(tasteQuoteFields[entry.suggestedQuoteField])}\n引用：${esc(entry.suggestedQuote)}\n观点：${esc(entry.suggestedStatement)}\n方向：${entry.suggestedDirection === 'LIKE' ? '喜欢' : '不喜欢'}\n方面：${esc(tasteAspects[entry.suggestedAspect])}\n因素：${esc(entry.suggestedFactor)}</p></details>` : ''}
        <div class="taste-actions">${entry.stale ? `<button type="button" class="a-btn" onclick="editTasteInsight(${entry.id})">核对新原文并重新引用</button>` : `<button type="button" class="a-btn" onclick="setTasteInsightStatus(${entry.id},'CONFIRMED')">确认观点</button><button type="button" class="a-btn" onclick="editTasteInsight(${entry.id})">改写观点</button><button type="button" class="a-btn" onclick="setTasteInsightStatus(${entry.id},'REJECTED')">拒绝观点</button>`}<button type="button" class="a-btn del" onclick="deleteTasteInsight(${entry.id})">删除观点</button></div></article>`).join('') || '<p class="memory-empty">还没有观点。可以手工添加，或先预览AI选文。</p>';
}
function resetTasteForm(check = true) {
    const state = tasteInstance; if (!state || state.busy || (check && !canLeaveTasteMemory())) return;
    state.editing = null; state.key = null; state.conflicted = false;
    const defaults = { quoteField: 'CONTENT', direction: 'LIKE', aspect: 'NARRATIVE' };
    for (const field of tasteFormFields) tasteInput(field).value = defaults[field] || '';
    document.getElementById('tasteFormTitle').textContent = '手工添加观点'; document.getElementById('tasteSave').disabled = false;
    state.draftBaseline = JSON.stringify(tasteDraft());
}
function editTasteInsight(id) {
    const state = tasteInstance; if (!state || state.busy || state.conflicted || !canLeaveTasteMemory()) return;
    const entry = state.entries.find(value => value.id === id); if (!entry) return;
    state.editing = entry; state.key = entry.key; state.conflicted = false;
    for (const field of tasteFormFields) tasteInput(field).value = entry[field];
    document.getElementById('tasteFormTitle').textContent = entry.stale ? '核对新原文后，显式重新引用并确认' : '改写并确认这条观点';
    document.getElementById('tasteSave').disabled = false;
    state.draftBaseline = JSON.stringify(tasteDraft());
    tasteStatus(state, entry.stale ? '原文已经变动。旧引用已保留供核对，请重新选择当前原文中的连续片段；保存才会采用当前来源版本。' : '改写保留原AI建议，保存不会调用AI。'); tasteInput('quote').focus();
}
function tasteWriteBody(state, draft, status = 'CONFIRMED', entry = state.editing) {
    return { ...draft, key: entry?.key || state.key, sourceVersion: state.source.version, status, ...(entry ? { expectedVersion: entry.version } : {}) };
}
function tasteValid(state, draft) {
    for (const [field, limit] of [['quote', 800], ['statement', 500], ['factor', 100]]) {
        if (!draft[field].trim() || draft[field].length > limit) { tasteStatus(state, `请填写引用、观点和因素；引用最多800、观点500、因素100字符，输入不会被截断。`); return false; }
    }
    if (!state.source[draft.quoteField.toLowerCase()]?.includes(draft.quote)) { tasteStatus(state, '引用必须为当前所选字段的连续原文；请核对后重新引用。'); return false; }
    return true;
}
async function writeTasteInsight(state, entry, body) {
    tasteSetBusy(state, true); tasteStatus(state, '正在保存观点…');
    const result = await fetchApi(`/api/anime/${state.animeId}/memories/${state.memoryId}/insights${entry ? '/' + entry.id : ''}`, { method: entry ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), returnError: true, returnConflict: true });
    if (!tasteCurrent(state)) return;
    tasteSetBusy(state, false);
    if (result?.code !== 200) {
        if (result?.code === 409) { state.conflicted = true; document.getElementById('tasteSave').disabled = true; }
        tasteStatus(state, (result?.message || '写入失败') + '；输入已保留，请重新加载原文与观点后核对，不会自动重试。'); return;
    }
    state.entries = [...state.entries.filter(value => value.id !== result.data.id), result.data].sort((a, b) => a.id - b.id);
    if (state.editing?.id === entry?.id) resetTasteForm(false);
    renderTasteEntries(state); tasteStatus(state, '观点已保存；仅确认且来源有效的观点进入总结。'); refreshTasteProfile();
}
async function saveTasteInsight() {
    const state = tasteInstance; if (!state || state.busy || state.conflicted) return;
    const draft = tasteDraft(); if (!tasteValid(state, draft)) return;
    state.key ||= crypto.randomUUID(); await writeTasteInsight(state, state.editing, tasteWriteBody(state, draft));
}
async function setTasteInsightStatus(id, status) {
    const state = tasteInstance; if (!state || state.busy || state.conflicted || !canLeaveTasteMemory()) return;
    const entry = state.entries.find(value => value.id === id); if (!entry || entry.stale) return;
    await writeTasteInsight(state, entry, tasteWriteBody(state, entry, status, entry));
}
async function deleteTasteInsight(id) {
    const state = tasteInstance; if (!state || state.busy || !canLeaveTasteMemory()) return;
    const entry = state.entries.find(value => value.id === id); if (!entry || !confirm('确定删除这条观点吗？')) return;
    tasteSetBusy(state, true);
    const result = await fetchApi(`/api/anime/${state.animeId}/memories/${state.memoryId}/insights/${id}?expectedVersion=${entry.version}`, { method: 'DELETE', returnError: true, returnConflict: true });
    if (!tasteCurrent(state)) return; tasteSetBusy(state, false);
    if (result?.code !== 200) { tasteStatus(state, (result?.message || '删除失败') + '；输入已保留，请重新加载核对。'); return; }
    state.entries = state.entries.filter(value => value.id !== id); if (state.editing?.id === id) resetTasteForm(false);
    renderTasteEntries(state); tasteStatus(state, '观点已删除。'); refreshTasteProfile();
}
function invalidateTastePreview() {
    const state = tasteInstance; if (!state || state.busy) return;
    state.preview = null; document.getElementById('tastePreview').innerHTML = '';
    tasteStatus(state, '选文已变化，请重新预览；已有调用编号仍保留供核对。');
}
function cancelTastePreview() { invalidateTastePreview(); if (tasteInstance) tasteStatus(tasteInstance, '已取消预览，没有发送AI请求。'); }
async function previewTasteAi() {
    const state = tasteInstance; if (!state || state.busy) return;
    const selections = tasteSelection(), count = Object.values(selections).reduce((sum, text) => sum + text.length, 0);
    if (count < 1 || count > 6000) { tasteStatus(state, `已选${count}字符，须为1–6000字符；原文不会自动截断，请自己选连续片段。`); return; }
    if (Object.entries(selections).some(([field, value]) => value && !state.source[field]?.includes(value))) { tasteStatus(state, '每个字段须为空或来自对应原文的连续片段，请核对。'); return; }
    tasteSetBusy(state, true);
    const result = await fetchApi(`/api/anime/${state.animeId}/memories/${state.memoryId}/insights/ai/preview`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sourceVersion: state.source.version, selections }), returnError: true, returnConflict: true });
    if (!tasteCurrent(state)) return; tasteSetBusy(state, false);
    if (result?.code !== 200) { tasteStatus(state, (result?.message || '预览失败') + '；选文已保留，没有发送AI请求。'); return; }
    state.preview = result.data; state.previewInput = JSON.stringify(selections);
    const p = result.data;
    document.getElementById('tastePreview').innerHTML = `<div class="taste-preview"><h5>发送前核对</h5><p class="taste-meta">供应商 ${esc(p.provider)} · 模型 ${esc(p.model)} · 规则 ${esc(p.promptVersion || '')}</p><p class="taste-meta">范围：${esc(p.scope || '未填写')} · 语境：${esc(memoryContexts[p.context] || p.context || state.source.context)}</p>
        ${Object.entries(p.selections).filter(([, value]) => value).map(([field, value]) => `<h5>${tasteQuoteFields[field.toUpperCase()]}</h5><p class="taste-plain">${esc(value)}</p>`).join('')}
        <p class="memory-note">${p.selectedCharacters}字符 · 估算输入 ${p.estimatedInputTokens} tokens · 输出上限 ${p.maxOutputTokens} tokens · 估算 ¥${esc(p.estimatedCostYuan)}（仅估算）<br>今日调用 ${p.callsToday ?? 0}/${p.dailyLimit ?? 20} · 剩余 ${p.remainingCalls} 次。本应用用量，不代表账户余额。${p.cachedRequestKey ? '<br>同一输入已有成功记录，将复用原编号与当前候选，不额外调用。' : ''}</p>
        <button id="tasteSend" type="button" class="a-btn" onclick="sendTasteAi()" ${!p.configured || (!p.cachedRequestKey && p.remainingCalls <= 0) ? 'disabled' : ''}>明确发送并整理</button>${!p.configured ? '<p class="memory-note">AI未配置，仍可手工整理。</p>' : ''}</div>`;
    tasteStatus(state, '预览完成。核对后点击“明确发送并整理”才会发送。');
}
function renderTasteResult(state, result) {
    state.callResult = result; state.requestPending = result.status === 'RUNNING';
    if (result.status === 'SUCCEEDED') {
        const current = state.entries.filter(entry => entry.aiCallKey !== result.requestKey);
        state.entries = [...new Map([...current, ...(result.entries || [])].map(entry => [entry.id, entry])).values()].sort((a, b) => a.id - b.id);
        renderTasteEntries(state);
    }
    const usage = result.usage;
    document.getElementById('tasteResult').innerHTML = `<p class="memory-note">调用编号：${esc(result.requestKey)}<br>${esc(result.message || result.status)}${result.cached ? ' · 已复用缓存，候选为当前已修改、拒绝或删除后的结果' : ''}<br>${usage ? `实际输入 ${usage.promptTokens ?? '未知'} tokens · 实际输出 ${usage.completionTokens ?? '未知'} tokens · 缓存 ${usage.cachedTokens ?? '未知'} · 推理 ${usage.reasoningTokens ?? '未知'}` : '实际用量未知；调用次数仍计入本应用额度。'}</p><button type="button" class="a-btn" onclick="reconcileTasteAi()">核对本次调用记录</button><button type="button" class="a-btn" onclick="newTasteAiRequest()">核对后开始新的预览</button>`;
    if (result.status === 'SUCCEEDED' && state.requestBody) {
        state.selectionBaseline = JSON.stringify(Object.fromEntries(['content', 'liked', 'disliked'].map(field => [field, state.requestBody.selections[field] || ''])));
    }
    tasteStatus(state, result.status === 'SUCCEEDED' ? '候选已读取，请逐条核对后确认；待核对候选不进入总结。' : '本次调用未完成整理；草稿与编号已保留，不会自动重试。');
    refreshTasteUsage();
}
async function reconcileTasteAi() {
    const state = tasteInstance; if (!state?.requestKey || state.busy) return;
    tasteSetBusy(state, true);
    const result = await fetchApi(`/api/taste/ai/calls/${state.requestKey}`, { returnError: true });
    if (!tasteCurrent(state)) return; tasteSetBusy(state, false);
    state.lastLookupCode = result?.code;
    if (result?.code === 200 && result.data) renderTasteResult(state, result.data);
    else {
        state.requestPending = true; tasteStatus(state, '暂未核对到调用记录；草稿与调用编号已保留。请核对本次调用，不会自动新编号重发。');
        document.getElementById('tasteResult').innerHTML = `<p class="memory-note">已保留调用编号：${esc(state.requestKey)}，暂未核对到记录。</p><button type="button" class="a-btn" onclick="reconcileTasteAi()">核对本次调用记录</button>${result?.code === 404 ? '<button type="button" class="a-btn" onclick="retryUnknownTasteAi()">确认后使用同编号重试</button><button type="button" class="a-btn" onclick="newTasteAiRequest()">核对后开始新的预览</button>' : ''}`;
    }
    return result;
}
async function readPreviousTasteCall(index) {
    const state = tasteInstance, key = state?.previousCalls[index]; if (!key || state.busy) return;
    tasteSetBusy(state, true); const result = await fetchApi(`/api/taste/ai/calls/${key}`, { returnError: true });
    if (!tasteCurrent(state)) return; tasteSetBusy(state, false);
    tasteStatus(state, `旧调用 ${key}：${result?.data?.message || result?.message || '暂时无法核对'}；当前选文与草稿已保留。`);
}
async function retryUnknownTasteAi() {
    const state = tasteInstance; if (!state?.requestBody || state.busy) return;
    const lookup = await reconcileTasteAi(); if (!tasteCurrent(state) || lookup?.code !== 404) return;
    if (!confirm('核对暂未找到记录，网络中断前仍可能已发送。确定使用原编号和原选文重试吗？不会生成新编号。')) return;
    tasteSetBusy(state, true);
    const result = await fetchApi(`/api/anime/${state.animeId}/memories/${state.memoryId}/insights/ai/extract`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(state.requestBody), returnError: true, returnConflict: true });
    if (!tasteCurrent(state)) return; tasteSetBusy(state, false);
    if (result?.data?.requestKey) renderTasteResult(state, result.data);
    if (result?.code !== 200 || !result?.data?.requestKey) await reconcileTasteAi();
}
async function newTasteAiRequest() {
    const state = tasteInstance; if (!state || state.busy) return;
    const result = await reconcileTasteAi(); if (!tasteCurrent(state)) return;
    if (result?.code !== 200 && result?.code !== 404) { tasteStatus(state, '原调用尚未核对完成，编号已保留，请稍后再次核对。'); return; }
    if (!confirm(`${result?.data?.status === 'RUNNING' || result?.code === 404 ? '原调用结果未知，可能仍在运行或已经计费；旧编号保留可继续核对。' : '原调用已结束。'}新的整理会使用新编号并消耗一次调用额度，服务端仍会核对并发与额度，是否继续预览？`)) return;
    state.previousCalls.push(state.requestKey);
    document.getElementById('tasteCallHistory').innerHTML = state.previousCalls.map((key, index) => `<p class="memory-note">旧调用编号：${esc(key)} <button type="button" class="a-btn" onclick="readPreviousTasteCall(${index})">核对旧调用 ${index + 1}</button></p>`).join('');
    state.requestKey = null; state.requestPending = false; state.callResult = null; invalidateTastePreview(); await previewTasteAi();
}
async function sendTasteAi() {
    const state = tasteInstance; if (!state || state.busy) return;
    if (state.requestKey && state.requestPending) { await reconcileTasteAi(); return; }
    const p = state.preview;
    if (!p || !p.configured || state.previewInput !== JSON.stringify(tasteSelection()) || (!p.cachedRequestKey && p.remainingCalls <= 0)) return;
    // 已发送的请求保持原编号；编辑选文后须明确结束核对再开始新请求。
    if (state.requestKey && state.callResult && !p.cachedRequestKey) { tasteStatus(state, '本次调用已结束，请先核对调用记录，再明确开始新的预览。'); return; }
    state.requestKey = p.cachedRequestKey || state.requestKey || crypto.randomUUID(); state.requestPending = true;
    state.requestBody = { sourceVersion: p.sourceVersion, selections: p.selections, requestKey: state.requestKey };
    document.getElementById('tasteResult').innerHTML = `<p class="memory-note">已保留调用编号：${esc(state.requestKey)}</p><button type="button" class="a-btn" onclick="reconcileTasteAi()">核对本次调用记录</button>`;
    tasteSetBusy(state, true); tasteStatus(state, '正在发送并整理，请等待完成后再离开…');
    const result = await fetchApi(`/api/anime/${state.animeId}/memories/${state.memoryId}/insights/ai/extract`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(state.requestBody), returnError: true, returnConflict: true });
    if (!tasteCurrent(state)) return; tasteSetBusy(state, false);
    document.getElementById('tastePreview').innerHTML = ''; state.preview = null;
    if (result?.data?.requestKey) {
        renderTasteResult(state, { ...result.data, cached: result.data.cached || p.cachedRequestKey === result.data.requestKey });
        if (result.code !== 200) await reconcileTasteAi();
    } else await reconcileTasteAi();
}
async function refreshTasteSource(animeId, memoryId, deleted = false) {
    refreshTasteProfile(); const state = tasteInstance;
    if (!state || state.animeId !== animeId || state.memoryId !== memoryId) return;
    if (deleted) { closeTasteMemory(true); return; }
    const [source, insights] = await Promise.all([fetchApi(`/api/anime/${animeId}/memories/${memoryId}`, { returnError: true }), fetchApi(`/api/anime/${animeId}/memories/${memoryId}/insights`, { returnError: true })]);
    if (!tasteCurrent(state)) return;
    if (source?.code !== 200 || insights?.code !== 200) { tasteStatus(state, '原文已保存，观点刷新失败。草稿已保留，请重新加载核对。'); return; }
    state.source = source.data; state.entries = insights.data.entries; state.preview = null; document.getElementById('tastePreview').innerHTML = '';
    document.getElementById('tasteSourceMeta').textContent = `原文已更新至版本 ${state.source.version}；AI选文与观点草稿仍为你的当前输入，请核对。`;
    document.getElementById('tasteSourceBody').innerHTML = Object.entries(tasteQuoteFields).map(([key, label]) => `<h5>${label}</h5><p class="taste-plain">${esc(state.source[key.toLowerCase()] || '未填写')}</p>`).join('');
    renderTasteEntries(state); tasteStatus(state, '原文已变化，旧来源观点已排除总结。当前观点草稿与AI选文保留，请重新加载核对后显式引用新原文。');
}
function renderTasteProfile(data) {
    const host = document.getElementById('tasteProfile'); if (!host) return;
    const open = host.querySelector?.('details')?.open || false;
    const evidence = (item, direction) => `<li><span class="taste-badge">${direction}</span><strong>${esc(item.animeName)}</strong><p class="taste-plain">${esc(item.statement)}</p><blockquote class="taste-plain">${esc(item.quote)}</blockquote><p class="taste-meta">${esc(memoryContexts[item.context] || item.context)} · 范围：${esc(item.scope || '未填写')} · 观看：${esc(item.watchedDate || '未填写')} · 书写：${esc(item.sourceCreatedAt || '未填写')}</p><button type="button" class="a-btn" onclick="openTasteEvidence(${item.animeId},${item.memoryId})">查看准确原文并核对</button><button type="button" class="a-btn" data-insight="${esc(item.insightKey)}" onclick="openRecommendationBases(this.dataset.insight)">关联为推荐依据</button></li>`;
    host.innerHTML = `<details class="taste-summary" ${open ? 'open' : ''}><summary><span>观影总结</span><span>${data.animeCount} 部作品 · ${data.evidenceCount} 条已确认依据</span></summary><p class="memory-note">从已确认且原文未变动的观点整理，喜欢与不喜欢会同时保留。${data.animeCount <= 1 ? '样本不足：仅能说明在这部作品中的感受，不能视为稳定类型。' : '这些是现有作品中的感受，随着新记忆继续补充。'}${data.staleCount ? ` ${data.staleCount} 条观点原文已变动，暂不进入总结，请重新核对来源。` : ''}</p>
        ${data.groups.map(group => `<section class="taste-group"><h4>${esc(tasteAspects[group.aspect])} · ${esc(group.factor)}</h4><p class="taste-meta">${group.animeCount} 部作品 · 喜欢 ${group.likes.length} 条 · 不喜欢 ${group.dislikes.length} 条${group.likes.length && group.dislikes.length ? ' · 正负观点并存' : ''}</p><ul>${group.likes.map(item => evidence(item, '喜欢')).join('')}${group.dislikes.map(item => evidence(item, '不喜欢')).join('')}</ul></section>`).join('') || '<p class="memory-empty">目前没有已确认且来源有效的观点。先在作品的观影记忆中整理并核对；AI候选不会自动成为总结。</p>'}
        <p id="tasteUsage" class="memory-note"></p><button type="button" class="a-btn" onclick="refreshTasteProfile();refreshTasteUsage()">刷新总结与本应用用量</button></details>`;
}
async function refreshTasteProfile() {
    if (!document.getElementById('tasteProfile')) return;
    const generation = ++tasteProfileGeneration;
    const result = await fetchApi('/api/taste/profile', { returnError: true });
    if (generation !== tasteProfileGeneration) return;
    if (result?.code === 200) { renderTasteProfile(result.data); refreshTasteUsage(); }
    else document.getElementById('tasteProfile').innerHTML = '<p class="memory-note">观影总结读取失败。</p><button type="button" class="a-btn" onclick="refreshTasteProfile()">重新读取总结</button>';
}
async function refreshTasteUsage() {
    const generation = ++tasteUsageGeneration;
    const result = await fetchApi('/api/taste/ai/usage', { returnError: true });
    if (generation !== tasteUsageGeneration || !document.getElementById('tasteUsage')) return;
    const u = result?.data;
    document.getElementById('tasteUsage').textContent = result?.code === 200 ? `本应用今日调用 ${u.callsToday}/${u.dailyLimit} · 剩余 ${u.remainingCalls} 次 · 累计已知输入 ${u.knownInputTokens} / 输出 ${u.knownOutputTokens} tokens · 累计 ${u.unknownCallCount} 次实际用量未知。${u.configured ? '' : 'AI未配置，手工观点仍可使用。'}此处不代表供应商账户余额。` : '本应用用量暂时无法读取。';
}
async function openTasteEvidence(animeId, memoryId) {
    if (typeof openTasteAnimeDetail === 'function' && !await openTasteAnimeDetail(animeId)) return;
    if (document.getElementById('animeMemorySection')?.dataset.animeId === String(animeId)) await openTasteMemory(animeId, memoryId);
}
window.addEventListener('beforeunload', event => {
    if (tasteInstance && tasteCurrent(tasteInstance) && (tasteInstance.busy || tasteDirty(tasteInstance))) { event.preventDefault(); event.returnValue = ''; }
});
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', refreshTasteProfile); else refreshTasteProfile();
