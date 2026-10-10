const memoryContexts = { NOTE: '随手记', INITIAL: '初看', REFLECTION: '回望', REWATCH: '重看' };
const memoryFields = ['content', 'liked', 'disliked', 'scope', 'context', 'watchedDate'];

function memoryCurrent(state) {
    return document.getElementById('animeMemorySection') === state.section && state.section.memoryState === state;
}

function memoryToday() {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

function memoryDraft() {
    return Object.fromEntries(memoryFields.map(field => [field, document.getElementById('memory' + field[0].toUpperCase() + field.slice(1))?.value || '']));
}

function memoryDirty(state) {
    return JSON.stringify(memoryDraft()) !== state.baseline;
}

function memoryStatus(state, message) {
    if (memoryCurrent(state)) document.getElementById('memoryStatus').textContent = message;
}

function canLeaveAnimeMemory() {
    const state = document.getElementById('animeMemorySection')?.memoryState;
    if (!state) return true;
    if (state.saving) {
        memoryStatus(state, state.operationMessage || '正在保存或删除感想，请等待完成后再离开。');
        return false;
    }
    return !memoryDirty(state) || confirm('存在未保存的感想，离开或重新加载会放弃这些输入，是否继续？');
}

function beginAnimeMemoryOperation(message) {
    const detail = document.getElementById('detailModal');
    const state = detail ? document.getElementById('animeMemorySection')?.memoryState : null;
    const operation = { detail, state, previousMessage: state ? document.getElementById('memoryStatus').textContent : '' };
    if (state) {
        state.operationMessage = message;
        memorySetBusy(state, true);
        memoryStatus(state, message);
    }
    return operation;
}

function endAnimeMemoryOperation(operation) {
    if (!operation?.state || !memoryCurrent(operation.state)) return;
    memorySetBusy(operation.state, false);
    operation.state.operationMessage = null;
    memoryStatus(operation.state, operation.previousMessage);
}

function resetMemoryDraft(state, entry = null, context = 'NOTE') {
    state.editing = entry;
    state.key = entry?.key || null;
    for (const field of memoryFields) {
        const input = document.getElementById('memory' + field[0].toUpperCase() + field.slice(1));
        input.value = entry?.[field] || (field === 'context' ? context : '');
    }
    document.getElementById('memorySave').textContent = entry ? '保存修改' : '保存感想';
    document.getElementById('memoryCancelEdit').hidden = !entry;
    document.getElementById('memoryFormTitle').textContent = entry ? '修改这条感想' : '此刻有什么想留下的？';
    document.getElementById('memoryOptional').open = !!entry && !!(entry.liked || entry.disliked || entry.scope || entry.watchedDate);
    state.baseline = JSON.stringify(memoryDraft());
}

function renderMemoryForm(state) {
    state.section.innerHTML = `<div class="memory-heading"><div><h3>观影记忆</h3><p class="memory-note">留下一句话或一篇长文。此刻的感受与以后的回望，都可以各自保留。</p></div>
        <button type="button" class="a-btn" onclick="writeAnimeReflection(${state.id})">写一条回望</button></div>
        <form id="memoryForm" class="memory-form" onsubmit="event.preventDefault();saveAnimeMemory(${state.id})">
            <label id="memoryFormTitle" class="memory-prompt" for="memoryContent">此刻有什么想留下的？</label>
            <textarea id="memoryContent" class="app-input memory-content-input" rows="5" required maxlength="16000" placeholder="写下现在的感想，不必整理成结论。"></textarea>
            <div class="memory-context-row"><label for="memoryContext">这次记录的语境</label><select id="memoryContext" class="app-input">
                ${Object.entries(memoryContexts).map(([key, label]) => `<option value="${key}">${label}</option>`).join('')}</select></div>
            <details id="memoryOptional" class="memory-optional"><summary>补充原因、评价范围或观看日期（可选）</summary><div class="memory-optional-fields">
                <label for="memoryLiked">打动我的地方<textarea id="memoryLiked" class="app-input" rows="3" maxlength="2000"></textarea></label>
                <label for="memoryDisliked">不满意的地方<textarea id="memoryDisliked" class="app-input" rows="3" maxlength="2000"></textarea></label>
                <label for="memoryScope">评价范围<input id="memoryScope" class="app-input" maxlength="200" placeholder="如：第一季、电影、原作后续" value=""></label>
                <label for="memoryWatchedDate">观看日期<input id="memoryWatchedDate" class="app-input" type="date" min="1000-01-01" max="${memoryToday()}" value=""><small>只填记得的实际日期，不确定就留空。</small></label>
            </div></details>
            <div class="memory-form-actions"><button id="memorySave" type="submit" class="a-btn">保存感想</button><button id="memoryCancelEdit" type="button" class="a-btn" hidden onclick="cancelAnimeMemoryEdit(${state.id})">取消编辑</button></div>
        </form><p id="memoryStatus" class="memory-note" role="status" aria-live="polite"></p><div id="memoryList" class="memory-list"></div><div id="memoryPages" class="memory-pages"></div>`;
    resetMemoryDraft(state);
}

async function loadAnimeMemories(id, page = 0) {
    const section = document.getElementById('animeMemorySection');
    if (!section || section.dataset.animeId !== String(id) || page < 0 || (section.memoryState && !canLeaveAnimeMemory())) return;
    const state = { id, section, page, size: 20, totalEntries: 0, entries: [], saving: false, conflicted: false, generation: 0 };
    section.memoryState = state;
    renderMemoryForm(state);
    await fetchMemoryList(state, page);
}

async function fetchMemoryList(state, page, successMessage = '') {
    const generation = ++state.generation;
    document.getElementById('memoryList').innerHTML = '<p class="memory-note" role="status">正在读取感想…</p>';
    document.getElementById('memoryPages').innerHTML = '';
    const result = await fetchApi(`/api/anime/${state.id}/memories?page=${page}&size=20`, { returnError: true });
    // 关闭、切换作品或重新加载后，过期响应不能覆盖当前感想。
    if (!memoryCurrent(state) || generation !== state.generation) return;
    if (!result || result.code !== 200) {
        document.getElementById('memoryList').innerHTML = `<p class="memory-note">感想加载失败，可稍后重试。</p><button type="button" class="a-btn" onclick="loadAnimeMemories(${state.id},${page})">重试</button>`;
        memoryStatus(state, successMessage ? successMessage + '，列表刷新失败，请重新加载核对。' : result?.message || '感想加载失败，请重试。');
        return;
    }
    Object.assign(state, { page: result.data.page, size: result.data.size, totalEntries: result.data.totalEntries, entries: result.data.entries });
    const lastPage = Math.max(0, Math.ceil(state.totalEntries / state.size) - 1);
    if (state.page > lastPage) return fetchMemoryList(state, lastPage, successMessage);
    renderMemoryList(state);
    if (successMessage) memoryStatus(state, successMessage + '。');
}

function renderMemoryList(state) {
    const optional = (label, value) => value ? `<div class="memory-reason"><h4>${label}</h4><p>${esc(value)}</p></div>` : '';
    document.getElementById('memoryList').innerHTML = state.entries.map(entry => `<article class="memory-entry" data-memory-id="${entry.id}">
        <div class="memory-entry-heading"><span class="memory-context">${esc(memoryContexts[entry.context] || '随手记')}</span><div class="memory-entry-actions"><button type="button" class="a-btn" onclick="editAnimeMemory(${state.id},${entry.id})">编辑</button><button type="button" class="a-btn del" onclick="deleteAnimeMemory(${state.id},${entry.id})">删除</button></div></div>
        <p class="memory-original">${esc(entry.content)}</p>${optional('打动我的地方', entry.liked)}${optional('不满意的地方', entry.disliked)}
        <div class="memory-entry-meta">${entry.scope ? `<span>评价范围：${esc(entry.scope)}</span>` : ''}<span>观看日期：${esc(entry.watchedDate || '未填写')}</span><span>书写：${esc(entry.createdAt || '').replace('T', ' ')}</span><span>修改：${esc(entry.updatedAt || '').replace('T', ' ')}</span></div>
    </article>`).join('') || '<p class="memory-empty">还没有观影记忆。一句话也可以，之后再追加回望；观看日期不确定时留空。</p>';
    const lastPage = Math.max(0, Math.ceil(state.totalEntries / state.size) - 1);
    document.getElementById('memoryPages').innerHTML = `<button type="button" class="a-btn" onclick="loadAnimeMemories(${state.id},${state.page - 1})" ${state.page <= 0 ? 'disabled' : ''}>上一页</button><span>第 ${state.page + 1} / ${lastPage + 1} 页 · ${state.totalEntries} 条感想</span><button type="button" class="a-btn" onclick="loadAnimeMemories(${state.id},${state.page + 1})" ${state.page >= lastPage ? 'disabled' : ''}>下一页</button><button type="button" class="a-btn" onclick="loadAnimeMemories(${state.id},${state.page})">重新加载</button>`;
}

function editAnimeMemory(id, memoryId) {
    const state = document.getElementById('animeMemorySection')?.memoryState;
    if (!state || state.id !== id || state.conflicted || !canLeaveAnimeMemory()) return;
    const entry = state.entries.find(item => item.id === memoryId);
    if (!entry) return;
    resetMemoryDraft(state, entry);
    memoryStatus(state, '正在修改已有感想；新的回望请单独追加。');
    document.getElementById('memoryContent').focus();
}

function writeAnimeReflection(id) {
    const state = document.getElementById('animeMemorySection')?.memoryState;
    if (!state || state.id !== id || state.conflicted || !canLeaveAnimeMemory()) return;
    resetMemoryDraft(state, null, 'REFLECTION');
    memoryStatus(state, '写下现在的回望，过去的感想会保留。');
    document.getElementById('memoryContent').focus();
}

function cancelAnimeMemoryEdit(id) {
    const state = document.getElementById('animeMemorySection')?.memoryState;
    if (!state || state.id !== id || !canLeaveAnimeMemory()) return;
    resetMemoryDraft(state);
    if (!state.conflicted) memoryStatus(state, '');
}

function memoryValid(state, draft) {
    if (!draft.content.trim() || draft.content.length > 16000 || draft.liked.length > 2000 || draft.disliked.length > 2000 || draft.scope.length > 200) {
        memoryStatus(state, '请填写感想原文，并检查长度：原文最多16000字，原因各2000字，范围200字。');
        return false;
    }
    const date = draft.watchedDate;
    if (date && (!/^\d{4}-\d{2}-\d{2}$/.test(date) || date < '1000-01-01' || date > memoryToday() || Number.isNaN(Date.parse(date)) || new Date(date).toISOString().slice(0, 10) !== date)) {
        memoryStatus(state, '观看日期须为1000年至今天的有效日期；不确定可留空。');
        return false;
    }
    return document.getElementById('memoryForm').reportValidity();
}

function memorySetBusy(state, busy) {
    state.saving = busy;
    if (busy) {
        state.controls = [...state.section.querySelectorAll('button, input, textarea, select')].map(control => [control, control.disabled]);
        state.controls.forEach(([control]) => { control.disabled = true; });
    } else {
        state.controls?.forEach(([control, disabled]) => { control.disabled = disabled; });
    }
}

function memoryWriteFailure(state, result) {
    if (result?.code === 409) {
        state.conflicted = true;
        document.getElementById('memorySave').disabled = true;
        memoryStatus(state, (result.message || '感想已变化') + '；输入已保留，请重新加载后核对，不会自动重试。');
    } else memoryStatus(state, (result?.message || '保存失败，请联网后重试') + '；输入已保留。');
}

async function saveAnimeMemory(id) {
    const state = document.getElementById('animeMemorySection')?.memoryState;
    if (!state || state.id !== id || state.saving || state.conflicted) return;
    const draft = memoryDraft();
    if (!memoryValid(state, draft)) return;
    if (!state.key) {
        if (typeof crypto?.randomUUID !== 'function') {
            memoryStatus(state, '当前环境不能生成安全编号，请使用localhost或HTTPS访问后再保存；输入已保留。');
            return;
        }
        state.key = crypto.randomUUID();
    }
    const body = { ...draft, key: state.key };
    for (const field of ['liked', 'disliked', 'scope', 'watchedDate']) body[field] ||= null;
    if (state.editing) body.expectedVersion = state.editing.version;
    memorySetBusy(state, true); memoryStatus(state, '正在保存感想…');
    const result = await fetchApi(`/api/anime/${id}/memories${state.editing ? '/' + state.editing.id : ''}`, {
        method: state.editing ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), returnConflict: true, returnError: true
    });
    if (!memoryCurrent(state)) return;
    memorySetBusy(state, false);
    if (result?.code !== 200) { memoryWriteFailure(state, result); return; }
    resetMemoryDraft(state);
    await fetchMemoryList(state, 0, '感想已保存');
}

async function deleteAnimeMemory(id, memoryId) {
    const state = document.getElementById('animeMemorySection')?.memoryState;
    if (!state || state.id !== id || state.saving || state.conflicted) return;
    const entry = state.entries.find(item => item.id === memoryId);
    if (!entry || (state.editing?.id === memoryId && !canLeaveAnimeMemory()) || !confirm('确定删除这条观影记忆吗？删除后无法撤销。')) return;
    memorySetBusy(state, true); memoryStatus(state, '正在删除感想…');
    const result = await fetchApi(`/api/anime/${id}/memories/${memoryId}?expectedVersion=${entry.version}`, { method: 'DELETE', returnConflict: true, returnError: true });
    if (!memoryCurrent(state)) return;
    memorySetBusy(state, false);
    if (result?.code !== 200) { memoryWriteFailure(state, result); return; }
    if (state.editing?.id === memoryId) resetMemoryDraft(state);
    await fetchMemoryList(state, state.page, '感想已删除');
}

window.addEventListener('beforeunload', event => {
    const state = document.getElementById('animeMemorySection')?.memoryState;
    if (state && (state.saving || memoryDirty(state))) { event.preventDefault(); event.returnValue = ''; }
});
