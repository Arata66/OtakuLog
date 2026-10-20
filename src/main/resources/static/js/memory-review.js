let memoryReviewGeneration = 0, memoryReviewOpening = 0, memoryReviewPage = 0;

function memoryReviewFilters() {
    return { animeId: document.getElementById('memoryReviewAnime').value,
        context: document.getElementById('memoryReviewContext').value,
        month: document.getElementById('memoryReviewMonth').value,
        order: document.getElementById('memoryReviewOrder').value };
}

function memoryReviewEntryHtml(item) {
    const memory = item.memory;
    const reason = (label, text) => text ? `<div class="memory-reason"><h4>${label}</h4><p>${esc(text)}</p></div>` : '';
    return `<article class="review-entry" data-review-memory-id="${memory.id}">
        <div class="review-entry-heading"><div><h3>${esc(item.animeName)}</h3><span class="memory-context">${esc(memoryContexts[memory.context] || '随手记')}</span></div>
        <button type="button" class="a-btn" ${uiAction('openMemoryReviewEntry', [memory.id])}>在作品中查看</button></div>
        ${memoryTextHtml(memory.content)}${reason('打动我的地方', memory.liked)}${reason('不满意的地方', memory.disliked)}
        <div class="memory-entry-meta"><span title="${esc(memory.createdAt || '')}">书写：${esc(memory.createdAt || '').slice(0, 16).replace('T', ' ')}</span>
        <span>观看日期：${esc(memory.watchedDate || '未填写')}</span>${memory.scope ? `<span>评价范围：${esc(memory.scope)}</span>` : ''}</div></article>`;
}

async function loadMemoryReview(page = 0) {
    const root = document.getElementById('memoryReviewResults');
    if (!root || page < 0) return;
    const generation = ++memoryReviewGeneration, filters = memoryReviewFilters();
    ++memoryReviewOpening;
    const status = document.getElementById('memoryReviewStatus'), pages = document.getElementById('memoryReviewPages');
    status.textContent = '正在读取感想…'; root.setAttribute('aria-busy', 'true');
    const query = new URLSearchParams({ page: String(page), order: filters.order });
    for (const key of ['animeId', 'context', 'month']) if (filters[key]) query.set(key, filters[key]);
    let result;
    try { result = await fetchApi('/api/memories?' + query, { returnError: true }); } catch { result = null; }
    if (generation !== memoryReviewGeneration) return;
    root.setAttribute('aria-busy', 'false');
    if (!result || result.code !== 200) {
        status.textContent = '感想加载失败，筛选条件已保留。';
        root.innerHTML = `<p role="alert">${esc(result?.message || '暂时无法读取感想，请重试。')}</p><button type="button" class="a-btn" ${uiAction('loadMemoryReview', [page])}>重试</button>`;
        pages.innerHTML = ''; return;
    }
    const data = result.data, last = Math.max(0, Math.ceil(data.totalEntries / data.size) - 1);
    if (data.page > last) return loadMemoryReview(last);
    memoryReviewPage = data.page;
    const select = document.getElementById('memoryReviewAnime');
    select.innerHTML = '<option value="">全部作品</option>' + data.works.map(work => `<option value="${work.id}">${esc(work.name)}</option>`).join('')
        + (filters.animeId && !data.works.some(work => String(work.id) === filters.animeId)
            ? `<option value="${esc(filters.animeId)}">该作品暂无感想</option>` : '');
    select.value = filters.animeId;
    const filtered = !!(filters.animeId || filters.context || filters.month);
    root.innerHTML = data.entries.length ? data.entries.map(memoryReviewEntryHtml).join('')
        : `<p class="memory-empty">${filtered ? '没有符合条件的感想，可以调整或清除筛选。' : '还没有观影记忆。在作品详情里写下一句话，它就会出现在这里。'}</p>`;
    status.textContent = `${data.totalEntries} 条感想 · 按书写时间${filters.order === 'oldest' ? '从旧到新' : '从新到旧'}`;
    pages.innerHTML = `<button type="button" class="a-btn" ${uiAction('loadMemoryReview', [data.page - 1])} ${data.page <= 0 ? 'disabled' : ''}>上一页</button>
        <span>第 ${data.page + 1} / ${last + 1} 页</span><button type="button" class="a-btn" ${uiAction('loadMemoryReview', [data.page + 1])} ${data.page >= last ? 'disabled' : ''}>下一页</button>`;
}

function resetMemoryReview() {
    for (const id of ['memoryReviewAnime', 'memoryReviewContext', 'memoryReviewMonth']) document.getElementById(id).value = '';
    document.getElementById('memoryReviewOrder').value = 'newest';
    return loadMemoryReview(0);
}

function refreshMemoryReview() { return loadMemoryReview(memoryReviewPage); }

async function openMemoryReviewEntry(memoryId) {
    const opening = ++memoryReviewOpening;
    let result;
    try { result = await fetchApi('/api/memories/' + memoryId, { returnError: true }); } catch { result = null; }
    if (opening !== memoryReviewOpening || !document.getElementById('tab-memories')?.classList.contains('active')) return;
    if (!result || result.code !== 200) { toast(result?.message || '无法读取这条感想，请刷新后重试。', 'error'); return; }
    const target = result.data;
    _cache[target.anime.id] = target.anime;
    openDetailModal(target.anime.id, target.page, target.memoryId);
}
