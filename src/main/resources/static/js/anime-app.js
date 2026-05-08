        const SM = { watching: '追中', finished: '已完成', planning: '计划', dropped: '放弃' };
        let viewMode = localStorage.getItem('otakulog-view') || 'table';
        let currentPage = 0, isLoading = false, hasMore = true;
        let totalElements = 0, loadedCount = 0;
        const PAGE_SIZE = 12;
        const _cache = {};
        function debounce(fn, ms) { let t; return function(...a) { clearTimeout(t); t = setTimeout(() => fn.apply(this, a), ms); }; }
        function esc(s) { if (s == null) return ''; const d = document.createElement('div'); d.appendChild(document.createTextNode(s)); return d.innerHTML.replace(/"/g, '&quot;'); }
        function showTableSkeleton(rows) {
            const tb = document.querySelector('tbody'); if (!tb) return;
            tb.innerHTML = '';
            for (let i = 0; i < (rows || 5); i++) {
                const r = document.createElement('tr');
                r.innerHTML = '<td></td><td></td><td><div class="skeleton skeleton-cover"></div></td><td><div class="skeleton skeleton-text short" style="width:24px;"></div></td><td><div class="skeleton skeleton-text long"></div></td><td><div class="skeleton skeleton-text short" style="width:60px;"></div></td><td><div class="skeleton skeleton-text short" style="width:60px;"></div></td><td><div class="skeleton skeleton-text short" style="width:40px;"></div></td><td><div class="skeleton skeleton-text short" style="width:60px;"></div></td><td><div class="skeleton skeleton-text medium" style="width:100px;"></div></td><td><div class="skeleton skeleton-text medium" style="width:140px;"></div></td>';
                tb.appendChild(r);
            }
        }
        function showGallerySkeleton(count) {
            const g = document.getElementById('galleryGrid'); if (!g) return;
            g.innerHTML = '';
            for (let i = 0; i < (count || 6); i++) {
                const card = document.createElement('div'); card.className = 'skeleton-card';
                card.innerHTML = '<div class="skeleton skeleton-card-cover"></div><div class="skeleton-card-body"><div class="skeleton skeleton-text long"></div><div class="skeleton skeleton-text medium"></div><div class="skeleton skeleton-text short"></div></div>';
                g.appendChild(card);
            }
        }
        function showDetailSkeleton(count) {
            const g = document.getElementById('detailGrid'); if (!g) return;
            g.innerHTML = '';
            for (let i = 0; i < (count || 6); i++) {
                const card = document.createElement('div'); card.className = 'skeleton-card';
                card.innerHTML = '<div class="skeleton" style="width:100%;aspect-ratio:16/9;"></div><div class="skeleton-card-body"><div class="skeleton skeleton-text long"></div><div class="skeleton skeleton-text medium"></div><div class="skeleton skeleton-text short"></div><div class="skeleton skeleton-text medium"></div></div>';
                g.appendChild(card);
            }
        }

        async function fetchApi(url, options) {
            try {
                const r = await fetch(url, options);
                if (options && options.responseType === 'blob') return r;
                if (!r.ok) {
                    let msg = '请求失败 (' + r.status + ')';
                    try { const body = await r.json(); if (body.message) msg = body.message; } catch(_) {}
                    console.error('HTTP ' + r.status + ': ' + url);
                    toast(msg, 'error');
                    return null;
                }
                return await r.json();
            } catch (e) { console.error('Network error:', url, e); toast('网络连接失败', 'error'); return null; }
        }
        function toast(m, t = 'info') { const c = document.getElementById('tw'), el = document.createElement('div'); el.className = 'toast ' + t; el.textContent = m; c.appendChild(el); setTimeout(() => el.remove(), 3000); }
        function scoreClass(s) { return s >= 8 ? 'sc-high' : s >= 6 ? 'sc-mid' : 'sc-low'; }
        function renderTags(tags) { if (!tags) return ''; return tags.split(',').map(t => t.trim()).filter(Boolean).map(t => `<span class="tag-pill">${esc(t)}</span>`).join(''); }
        function highlightText(text, keyword) {
            if (!text || !keyword) return esc(text);
            const escaped = esc(text);
            const safeKw = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            const re = new RegExp('(' + safeKw + ')', 'gi');
            return escaped.replace(re, '<mark>$1</mark>');
        }
        function renderRemark(text) {
            if (!text) return '';
            if (typeof marked === 'undefined' || typeof DOMPurify === 'undefined') return esc(text);
            const raw = marked.parse(text, { breaks: true, gfm: true });
            return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } });
        }

        /* Batch selection */
        const selectedIds = new Set();
        function toggleRowSelect(id, checked) {
            if (checked) selectedIds.add(id); else selectedIds.delete(id);
            const row = document.getElementById('r-' + id);
            if (row) row.classList.toggle('selected', checked);
            updateBatchToolbar();
        }
        function toggleSelectAll(cb) {
            document.querySelectorAll('.batch-cb[data-id]').forEach(c => {
                c.checked = cb.checked;
                const id = parseInt(c.dataset.id);
                if (cb.checked) selectedIds.add(id); else selectedIds.delete(id);
                const row = document.getElementById('r-' + id);
                if (row) row.classList.toggle('selected', cb.checked);
            });
            updateBatchToolbar();
        }
        function updateBatchToolbar() {
            const bar = document.getElementById('batchToolbar');
            const cnt = document.getElementById('batchCount');
            if (selectedIds.size > 0) { bar.classList.add('active'); cnt.textContent = '已选 ' + selectedIds.size + ' 项'; }
            else { bar.classList.remove('active'); }
        }
        function clearSelection() {
            selectedIds.clear();
            document.querySelectorAll('.batch-cb[data-id]').forEach(c => { c.checked = false; });
            document.querySelectorAll('tbody tr.selected').forEach(r => r.classList.remove('selected'));
            const sa = document.getElementById('selectAll'); if (sa) sa.checked = false;
            updateBatchToolbar();
        }
        async function batchDelete() {
            if (!confirm('确定删除选中的 ' + selectedIds.size + ' 项？')) return;
            const r = await fetchApi('/api/anime/batch-delete', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids: [...selectedIds] }) });
            if (r && r.code === 200) { toast('批量删除成功', 'success'); clearSelection(); performSearch(); updateStats(); } else if (r) toast(r.message || '删除失败', 'error');
        }
        async function batchChangeStatus(status) {
            if (!status) return;
            const r = await fetchApi('/api/anime/batch-status', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids: [...selectedIds], status }) });
            if (r && r.code === 200) { toast('批量更新成功', 'success'); clearSelection(); performSearch(); updateStats(); } else if (r) toast(r.message || '更新失败', 'error');
        }

        /* Theme */
        function toggleTheme() {
            const root = document.documentElement, dark = root.getAttribute('data-theme') === 'dark';
            root.setAttribute('data-theme', dark ? '' : 'dark');
            localStorage.setItem('otakulog-theme', dark ? 'light' : 'dark');
            document.getElementById('themeToggle').textContent = dark ? '☽' : '☀';
            updateChartColors();
        }
        function updateChartColors() {
            const dark = document.documentElement.getAttribute('data-theme') === 'dark';
            Chart.defaults.color = dark ? '#706a60' : '#a89f94';
            Chart.defaults.borderColor = dark ? '#302c24' : '#ede8e0';
        }

        /* Tabs */
        function switchTab(n) {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
            const tabs = document.querySelectorAll('.tab');
            const tabNames = ['list', 'charts', 'calendar', 'timeline'];
            const idx = tabNames.indexOf(n);
            if (idx >= 0 && tabs[idx]) tabs[idx].classList.add('active');
            const content = document.getElementById('tab-' + n);
            if (content) content.classList.add('active');
            localStorage.setItem('otakulog-tab', n);
            updateMobileNav(idx >= 0 ? idx : 0);
            if (n === 'charts') loadCharts();
            if (n === 'timeline') loadTL();
            if (n === 'calendar') loadCalendar();
        }
        function goToAddTracking(name) {
            closeDetailModal();
            switchTab('list');
            setTimeout(() => {
                document.getElementById('animeName').value = name;
                searchBangumi();
                document.querySelector('.add-form')?.scrollIntoView({ behavior: 'smooth' });
            }, 200);
        }
        function updateMobileNav(n) {
            document.querySelectorAll('.mobile-nav-btn').forEach((b, i) => b.classList.toggle('active', i === n));
        }

        /* View toggle */
        function toggleView(mode) {
            viewMode = mode;
            localStorage.setItem('otakulog-view', mode);
            document.getElementById('viewTable').classList.toggle('active', mode === 'table');
            document.getElementById('viewDetail').classList.toggle('active', mode === 'detail');
            document.getElementById('viewGallery').classList.toggle('active', mode === 'gallery');
            document.querySelector('.table-card').classList.toggle('hidden', mode !== 'table');
            document.getElementById('detailView').classList.toggle('active', mode === 'detail');
            document.getElementById('galleryView').classList.toggle('active', mode === 'gallery');
        }

        /* Episodes */
        async function nextEpisode(id) { const r = await fetchApi(`/api/anime/${id}/next-episode`, { method: 'POST' }); if (r && r.code === 200) { toast('集数已更新', 'success'); performSearch(); updateStats(); } else if (r && r.message === 'reached_max') toast('已经是最后一集了', 'info'); else if (r) toast('更新失败', 'error'); }
        async function prevEpisode(id) { const r = await fetchApi(`/api/anime/${id}/prev-episode`, { method: 'POST' }); if (r && r.code === 200) { toast('集数已更新', 'success'); performSearch(); updateStats(); } else if (r && r.message === 'reached_min') toast('已经是第1集了', 'info'); else if (r) toast('更新失败', 'error'); }

        /* Add anime */
        async function addAnime(e) {
            e.preventDefault();
            const bd = document.getElementById('animeBroadcastDay').value;
            const bgId = document.getElementById('animeBangumiId').value;
            const statusVal = document.getElementById('animeStatus') ? document.getElementById('animeStatus').value : 'watching';
            const b = { name: document.getElementById('animeName').value, totalEpisodes: parseInt(document.getElementById('totalEpisodes').value), season: document.getElementById('animeSeason').value, score: parseFloat(document.getElementById('animeScore').value), remark: document.getElementById('animeRemark').value, coverUrl: document.getElementById('animeCover').value || null, startDate: document.getElementById('animeStartDate').value || null, endDate: document.getElementById('animeEndDate').value || null, tags: document.getElementById('animeTags').value || null, broadcastDay: bd ? parseInt(bd) : null, bangumiId: bgId ? parseInt(bgId) : null, status: statusVal, legacy: document.getElementById('animeLegacy').checked };
            const r = await fetchApi('/api/anime/add', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });
            if (r && r.code === 200) { toast('已添加', 'success'); document.getElementById('animeForm').reset(); closeBangumi(); performSearch(); updateStats(); } else if (r) toast(r.message || '添加失败', 'error');
        }

        /* Bangumi search */
        async function searchBangumi() {
            const kw = document.getElementById('animeName').value.trim();
            if (!kw) { toast('请先输入番剧名称', 'info'); return; }
            const btn = document.getElementById('btnBangumi');
            btn.disabled = true; btn.textContent = '搜索中...';
            const r = await fetchApi('/api/bangumi/search?keyword=' + encodeURIComponent(kw) + '&limit=8');
            btn.disabled = false; btn.textContent = '从 Bangumi 搜索';
            if (r && r.code === 200) { renderBangumiResults(r.data || []); } else if (r) { toast(r.message || '搜索失败', 'error'); }
        }
        function renderBangumiResults(list) {
            const panel = document.getElementById('bangumiResults'), grid = document.getElementById('bangumiGrid');
            if (!list.length) { toast('未找到相关番剧', 'info'); panel.classList.remove('active'); return; }
            grid.innerHTML = '';
            list.forEach(item => {
                const card = document.createElement('div'); card.className = 'bg-card';
                const cover = item.image ? `<img src="${esc(item.image)}" class="bg-cover" onerror="this.outerHTML='<div class=bg-cover-empty>N/A</div>'">` : '<div class="bg-cover-empty">N/A</div>';
                const displayName = item.nameCn || item.name || '未知';
                const score = item.score ? item.score.toFixed(1) : '-';
                const eps = item.eps || '?';
                const date = item.date || '';
                card.innerHTML = `${cover}<div class="bg-info"><div class="bg-name" title="${esc(displayName)}">${esc(displayName)}</div><div class="bg-sub" title="${esc(item.name)}">${esc(item.name)}</div><div class="bg-meta"><span>ep ${eps}</span><span>${score}</span><span>${esc(date)}</span></div></div>`;
                card.onclick = () => selectBangumi(item, card);
                grid.appendChild(card);
            });
            panel.classList.add('active');
        }
        function selectBangumi(item, card) {
            document.querySelectorAll('.bg-card').forEach(c => c.classList.remove('selected'));
            card.classList.add('selected');
            document.getElementById('animeBangumiId').value = item.id || '';
            const name = item.nameCn || item.name;
            if (name) document.getElementById('animeName').value = name;
            if (item.eps) document.getElementById('totalEpisodes').value = item.eps;
            if (item.score) document.getElementById('animeScore').value = item.score.toFixed(1);
            if (item.image) {
                const imgUrl = item.image.startsWith('//') ? 'https:' + item.image : item.image;
                document.getElementById('animeCover').value = imgUrl;
            }
            if (item.date) {
                document.getElementById('animeStartDate').value = item.date;
                const m = parseInt(item.date.split('-')[1]);
                const y = item.date.split('-')[0];
                if (m && y) {
                    const seasons = { 1:'冬', 2:'冬', 3:'春', 4:'春', 5:'春', 6:'夏', 7:'夏', 8:'夏', 9:'秋', 10:'秋', 11:'秋', 12:'冬' };
                    document.getElementById('animeSeason').value = y + seasons[m];
                }
                // Auto-populate broadcast day from air date
                try {
                    const d = new Date(item.date);
                    const jsDay = d.getDay(); // 0=Sun..6=Sat
                    const broadcastDay = jsDay === 0 ? 7 : jsDay; // 1=Mon..7=Sun
                    document.getElementById('animeBroadcastDay').value = broadcastDay;
                } catch(e) {}
            }
            toast('已填充 ' + name, 'success');
        }
        function closeBangumi() { document.getElementById('bangumiResults').classList.remove('active'); }

        /* trace.moe search */
        async function searchTraceMoe(input) {
            if (!input.files || !input.files[0]) return;
            const file = input.files[0];
            const formData = new FormData();
            formData.append('image', file);
            toast('正在识别...', 'info');
            const r = await fetchApi('/api/tracemoe/search', { method: 'POST', body: formData, headers: {} });
            if (r && r.code === 200 && r.data) {
                showTraceMoeResults(r.data);
            } else if (r) {
                toast(r.message || '识别失败', 'error');
            }
            input.value = '';
        }

        function showTraceMoeResults(data) {
            const existing = document.getElementById('traceMoeModal');
            if (existing) existing.remove();
            const overlay = document.createElement('div');
            overlay.className = 'detail-overlay';
            overlay.id = 'traceMoeModal';
            overlay.onclick = function(e) { if (e.target === overlay) overlay.remove(); };
            const card = document.createElement('div');
            card.className = 'detail-card';
            card.style.maxWidth = '480px';
            const results = data.allResults || [data];
            let html = '<div class="detail-body"><div class="detail-title">以图搜番结果</div>';
            results.forEach((r, i) => {
                const name = r.animeName || '未知';
                const ep = r.episode != null ? `EP${r.episode}` : '';
                const conf = r.confidence != null ? `${r.confidence}%` : '-';
                const preview = r.image ? `<img src="${esc(r.image)}" style="width:100%;border-radius:8px;margin-bottom:8px;" onerror="this.style.display='none'">` : '';
                html += `<div style="padding:12px;margin-bottom:8px;background:var(--bg);border-radius:8px;border:1px solid var(--border-light);">
                    ${preview}
                    <div style="font-weight:600;font-size:0.95em;margin-bottom:4px;">${esc(name)}</div>
                    <div style="font-size:0.82em;color:var(--text-mid);display:flex;gap:12px;">
                        <span>${ep}</span>
                        <span style="color:${r.confidence >= 90 ? 'var(--sage)' : r.confidence >= 70 ? 'var(--amber)' : 'var(--rose)'}">置信度 ${conf}</span>
                    </div>
                    <button class="a-btn" style="margin-top:8px;" onclick="document.getElementById('traceMoeModal').remove();goToAddTracking('${esc(name).replace(/'/g, "\\'")}')">添加追踪</button>
                </div>`;
            });
            html += '</div>';
            card.innerHTML = html;
            overlay.appendChild(card);
            document.body.appendChild(overlay);
            trapFocus(overlay);
        }

        /* Detail modal */
        function openDetailModal(id) {
            const a = _cache[id];
            if (!a) { toast('未找到该番剧', 'error'); return; }
            const pct = a.totalEpisodes > 0 ? Math.round(a.currentEpisode / a.totalEpisodes * 100) : 0;
            const cover = a.coverUrl ? `<img src="${esc(a.coverUrl)}" class="detail-cover" onerror="this.outerHTML='<div class=detail-cover-empty>${esc(a.name.charAt(0))}</div>'">` : `<div class="detail-cover-empty">${esc(a.name.charAt(0))}</div>`;
            const bangumiLink = a.bangumiId ? `<a href="https://bgm.tv/subject/${a.bangumiId}" target="_blank" rel="noopener" class="detail-bangumi-link">在 Bangumi 查看 ↗</a>` : '';
            const overlay = document.createElement('div'); overlay.className = 'detail-overlay'; overlay.id = 'detailModal'; overlay.onclick = function(e) { if (e.target === overlay) closeDetailModal(); };
            const card = document.createElement('div'); card.className = 'detail-card';
            card.innerHTML = `<div class="detail-header">
                    <div class="detail-cover-wrap">${cover}<span class="detail-badge ${a.status}">${SM[a.status] || a.status}</span></div>
                    <div class="detail-info-col">
                        <div class="detail-title">${esc(a.name)}</div>
                        <div class="detail-meta"><span class="detail-tag" style="background:var(--bg);border:1px solid var(--border-light);color:var(--text-mid)">${esc(a.season)}</span><span class="detail-tag" style="font-family:var(--serif);background:${a.score>=8?'var(--sage-soft)':a.score>=6?'var(--amber-soft)':'var(--rose-soft)'};color:${a.score>=8?'#5a8a60':a.score>=6?'#a08050':'#a06070'}">${a.score}</span></div>
                        <div class="detail-info">
                            <div class="detail-info-item"><div class="detail-info-label">状态</div><div class="detail-info-val">${SM[a.status] || a.status}</div></div>
                            <div class="detail-info-item"><div class="detail-info-label">集数</div><div class="detail-info-val">${a.currentEpisode} / ${a.totalEpisodes}</div></div>
                            <div class="detail-info-item"><div class="detail-info-label">开播</div><div class="detail-info-val">${a.startDate || '-'}</div></div>
                            <div class="detail-info-item"><div class="detail-info-label">完结</div><div class="detail-info-val">${a.endDate || '-'}</div></div>
                        </div>
                        ${bangumiLink}
                    </div>
                </div>
                <div class="detail-body">
                    <div class="detail-progress-wrap"><div class="detail-progress-label"><span>进度</span><span>${pct}%</span></div><div class="detail-progress"><div class="detail-progress-bar ${a.status}" style="width:${pct}%"></div></div></div>
                    ${a.remark ? `<div class="detail-remark">${renderRemark(a.remark)}</div>` : ''}
                    <div id="bangumiDetailSection"></div>
                    <div class="detail-actions"><button class="a-btn" onclick="closeDetailModal();openEditModal(${a.id})">编辑</button><button class="a-btn ep-btn" onclick="prevEpisode(${a.id})">上一集</button><button class="a-btn ep-btn" onclick="nextEpisode(${a.id})">下一集</button><button class="a-btn del" onclick="deleteAnime(${a.id});closeDetailModal()">删除</button></div>
                </div>`;
            overlay.appendChild(card); document.body.appendChild(overlay);
            trapFocus(overlay);
            if (a.bangumiId) loadBangumiDetail(a.bangumiId);
            else {
                document.getElementById('bangumiDetailSection').innerHTML = '<div style="text-align:center;padding:12px"><button class="detail-match-btn" onclick="matchBangumiFor(' + a.id + ', this)">🔗 匹配 Bangumi 链接</button></div>';
            }
        }

        async function loadBangumiDetail(bangumiId) {
            const section = document.getElementById('bangumiDetailSection');
            if (!section) return;
            section.innerHTML = '<div class="bangumi-loading"><div class="skeleton skeleton-text long"></div><div class="skeleton skeleton-text medium"></div></div>';

            const [detailRes, episodesRes] = await Promise.all([
                fetchApi(`/api/bangumi/subject/${bangumiId}`),
                fetchApi(`/api/bangumi/subject/${bangumiId}/episodes`)
            ]);

            let html = '';
            if (detailRes && detailRes.code === 200 && detailRes.data) {
                const d = detailRes.data;
                // Community rating
                if (d.rating) {
                    const rDist = d.ratingDetails?.count || {};
                    const total = d.ratingCount || 1;
                    const sageW = ((rDist[8]||0)+(rDist[9]||0)+(rDist[10]||0))/total*100;
                    const amberW = ((rDist[6]||0)+(rDist[7]||0))/total*100;
                    const roseW = ((rDist[1]||0)+(rDist[2]||0)+(rDist[3]||0)+(rDist[4]||0)+(rDist[5]||0))/total*100;
                    html += `<div class="bg-section"><div class="bg-section-title">Bangumi 评分</div>
                        <div style="display:flex;align-items:center;gap:12px;margin-bottom:8px;">
                            <span style="font-size:28px;font-family:var(--serif);font-weight:700;color:var(--text-strong)">${d.rating.toFixed(1)}</span>
                            <span style="color:var(--text-faint);font-size:13px">${d.ratingCount || 0} 人评分</span>
                        </div>
                        <div class="bg-rating-bar"><div class="bg-rating-sage" style="width:${sageW}%"></div><div class="bg-rating-amber" style="width:${amberW}%"></div><div class="bg-rating-rose" style="width:${roseW}%"></div></div>
                        <div style="display:flex;justify-content:space-between;font-size:11px;color:var(--text-faint);margin-top:4px;"><span>8-10</span><span>6-7</span><span>1-5</span></div>
                    </div>`;
                }
                // Tags
                if (d.tags && d.tags.length > 0) {
                    html += `<div class="bg-section"><div class="bg-section-title">社区标签</div><div class="bg-tags">`;
                    d.tags.forEach(t => { html += `<span class="bg-tag">${esc(t.name || t)}</span>`; });
                    html += '</div></div>';
                }
                // Summary
                if (d.summary) {
                    const truncated = d.summary.length > 200 ? d.summary.substring(0, 200) + '...' : d.summary;
                    html += `<div class="bg-section"><div class="bg-section-title">简介</div><div class="bg-summary">${esc(truncated)}</div></div>`;
                }
            }

            if (episodesRes && episodesRes.code === 200 && episodesRes.data && episodesRes.data.length > 0) {
                const eps = episodesRes.data;
                html += `<div class="bg-section"><div class="bg-section-title" onclick="this.nextElementSibling.style.display=this.nextElementSibling.style.display==='none'?'block':'none'" style="cursor:pointer">剧集列表 (${eps.length}) ▾</div><div class="bg-episodes">`;
                eps.slice(0, 24).forEach(ep => {
                    const name = ep.nameCn || ep.name || `EP${ep.sort}`;
                    const date = ep.airdate || '';
                    const aired = date && new Date(date) <= new Date();
                    html += `<div class="bg-ep ${aired ? 'aired' : 'upcoming'}"><span class="bg-ep-num">${ep.sort}</span><span class="bg-ep-name">${esc(name)}</span><span class="bg-ep-date">${esc(date)}</span></div>`;
                });
                if (eps.length > 24) html += `<div style="text-align:center;padding:8px;color:var(--text-faint);font-size:12px;">还有 ${eps.length - 24} 集...</div>`;
                html += '</div></div>';
            }

            section.innerHTML = html || '';
        }

        async function matchBangumiFor(id, btn) {
            btn.disabled = true;
            btn.textContent = '匹配中...';
            const r = await fetchApi('/api/anime/' + id + '/match-bangumi', { method: 'POST' });
            if (r && r.code === 200) {
                toast('匹配成功', 'success');
                _cache[id] = r.data;
                closeDetailModal();
                openDetailModal(id);
                renderList();
            } else {
                toast(r?.message || '匹配失败', 'error');
                btn.disabled = false;
                btn.textContent = '🔗 匹配 Bangumi 链接';
            }
        }

        async function batchMatchBangumi() {
            const btn = document.getElementById('btnBatchMatch');
            if (!btn) return;
            btn.disabled = true;
            btn.textContent = '匹配中，请稍候...';
            const r = await fetchApi('/api/anime/batch-match-bangumi', { method: 'POST' });
            if (r && r.code === 200) {
                const d = r.data;
                toast('匹配完成：成功 ' + d.matched + '，失败 ' + d.failed + '，共 ' + d.total, 'success');
                performSearch();
            } else {
                toast(r?.message || '批量匹配失败', 'error');
            }
            btn.disabled = false;
            btn.textContent = '🔗 补全 Bangumi 链接';
        }

        async function importFromBangumi() {
            const username = prompt('请输入 Bangumi 用户名：');
            if (!username || !username.trim()) return;
            const btn = document.getElementById('btnImportBangumi');
            if (btn) { btn.disabled = true; btn.textContent = '导入中，请稍候...'; }
            toggleSyncMenu();
            const r = await fetchApi('/api/bangumi/import/' + encodeURIComponent(username.trim()), { method: 'POST' });
            if (r && r.code === 200) {
                const d = r.data;
                toast('导入完成：新增 ' + d.created + '，跳过 ' + d.skipped + '，共 ' + d.total, 'success');
                performSearch();
                updateStats();
            } else {
                toast(r?.message || '导入失败', 'error');
            }
            if (btn) { btn.disabled = false; btn.textContent = '📥 从 Bangumi 导入'; }
        }

        function closeDetailModal() { const m = document.getElementById('detailModal'); if (m) m.remove(); }

        function trapFocus(overlay) {
            overlay.addEventListener('keydown', function(e) {
                if (e.key !== 'Tab') return;
                const focusable = overlay.querySelectorAll('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
                if (focusable.length === 0) return;
                const first = focusable[0], last = focusable[focusable.length - 1];
                if (e.shiftKey) { if (document.activeElement === first) { e.preventDefault(); last.focus(); } }
                else { if (document.activeElement === last) { e.preventDefault(); first.focus(); } }
            });
        }

        /* Edit modal */
        function openEditModal(id) {
            const a = _cache[id];
            if (!a) { toast('未找到该番剧', 'error'); return; }
            {
                const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; overlay.id = 'editModal'; overlay.onclick = function(e) { if (e.target === overlay) closeEditModal(); };
                const card = document.createElement('div'); card.className = 'modal-card';
                const statusOpts = ['watching','finished','planning','dropped'].map(s => `<option value="${s}" ${a.status===s?'selected':''}>${SM[s]}</option>`).join('');
                card.innerHTML = `<h3>编辑 · ${esc(a.name)}</h3>
                    <div class="modal-field"><label>番剧名</label><input type="text" id="m-name" value="${esc(a.name)}"></div>
                    <div class="modal-field"><label>总集数</label><input type="number" id="m-total" value="${a.totalEpisodes}" min="1"></div>
                    <div class="modal-field"><label>季度</label><input type="text" id="m-season" value="${esc(a.season)}"></div>
                    <div class="modal-field"><label>评分</label><input type="number" id="m-score" value="${a.score}" min="0" max="10" step="0.1"></div>
                    <div class="modal-field"><label>封面 URL</label><input type="text" id="m-cover" value="${esc(a.coverUrl || '')}"></div>
                    <div class="modal-field"><label>开播日期</label><input type="date" id="m-start" value="${a.startDate || ''}"></div>
                    <div class="modal-field"><label>完结日期</label><input type="date" id="m-end" value="${a.endDate || ''}"></div>
                    <div class="modal-field"><label>备注 (支持 Markdown)</label><textarea id="m-remark" rows="3">${esc(a.remark || '')}</textarea></div>
                    <div class="modal-field"><label>放送日</label><select id="m-broadcastDay"><option value="">未设置</option><option value="1" ${a.broadcastDay===1?'selected':''}>周一</option><option value="2" ${a.broadcastDay===2?'selected':''}>周二</option><option value="3" ${a.broadcastDay===3?'selected':''}>周三</option><option value="4" ${a.broadcastDay===4?'selected':''}>周四</option><option value="5" ${a.broadcastDay===5?'selected':''}>周五</option><option value="6" ${a.broadcastDay===6?'selected':''}>周六</option><option value="7" ${a.broadcastDay===7?'selected':''}>周日</option></select></div>
                    <div class="modal-field"><label>标签</label><input type="text" id="m-tags" value="${esc(a.tags || '')}" placeholder="热血,奇幻"></div>
                    <div class="modal-field"><label>状态</label><select id="m-status">${statusOpts}</select></div>
                    <div class="modal-field"><label>追番开始日</label><input type="date" id="m-watchStart" value="${a.watchStartDate || ''}"></div>
                    <div class="modal-field" style="flex-direction:row;align-items:center;gap:8px;">
                        <input type="checkbox" id="m-legacy" ${a.legacy ? 'checked' : ''} style="width:auto;">
                        <label for="m-legacy" style="text-transform:none;letter-spacing:0;font-size:0.82em;cursor:pointer;">旧番（不计入追番统计）</label>
                    </div>
                    <div class="modal-actions"><button class="btn-h" onclick="closeEditModal()">取消</button><button class="btn-add" onclick="saveEditModal(${id})">保存</button></div>`;
                overlay.appendChild(card); document.body.appendChild(overlay);
                trapFocus(overlay);
            }
        }
        function closeEditModal() { const m = document.getElementById('editModal'); if (m) m.remove(); }
        async function saveEditModal(id) {
            const n = document.getElementById('m-name').value.trim(), s = document.getElementById('m-season').value.trim(), sv = document.getElementById('m-score').value;
            if (!n || !s || !sv) { toast('请填写必填字段', 'error'); return; }
            const bd = document.getElementById('m-broadcastDay').value;
            const body = { name: n, totalEpisodes: parseInt(document.getElementById('m-total').value) || null, season: s, score: parseFloat(sv), coverUrl: document.getElementById('m-cover').value || null, startDate: document.getElementById('m-start').value || null, endDate: document.getElementById('m-end').value || null, remark: document.getElementById('m-remark').value || null, tags: document.getElementById('m-tags').value || null, broadcastDay: bd ? parseInt(bd) : null, watchStartDate: document.getElementById('m-watchStart').value || null, legacy: document.getElementById('m-legacy').checked };
            const st = document.getElementById('m-status').value;
            const r = await fetchApi(`/api/anime/${id}/update`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
            if (!r || r.code !== 200) { if (r) toast(r.message || '保存失败', 'error'); return; }
            if (st) { await fetchApi(`/api/anime/${id}/status?status=${encodeURIComponent(st)}`, { method: 'POST' }); }
            closeEditModal(); toast('已保存', 'success'); performSearch(); updateStats();
        }
        async function deleteAnime(id) { if (!confirm('确定删除这个番剧吗？')) return; const r = await fetchApi(`/api/anime/${id}`, { method: 'DELETE' }); if (r && r.code === 200) { toast('已删除', 'success'); performSearch(); updateStats(); } else if (r) toast(r.message || '删除失败', 'error'); }
        async function changeStatus(id) { const v = document.getElementById('ss-' + id).value; if (!v) return; const r = await fetchApi(`/api/anime/${id}/status?status=${encodeURIComponent(v)}`, { method: 'POST' }); if (r && r.code === 200) { toast('状态已更新', 'success'); performSearch(); updateStats(); } else if (r) toast(r.message || '修改失败', 'error'); }

        /* Search with pagination */
        async function performSearch(reset) {
            if (reset !== false) { currentPage = 0; hasMore = true; clearSelection(); showTableSkeleton(); showDetailSkeleton(); showGallerySkeleton(); }
            if (isLoading || !hasMore) return;
            isLoading = true;
            const n = document.getElementById('searchName').value.trim(), s = document.getElementById('filterStatus').value, sb = document.getElementById('sortBy').value, tg = document.getElementById('filterTag').value.trim();
            let u = `/api/anime/page?page=${currentPage}&size=${PAGE_SIZE}&sortBy=${encodeURIComponent(sb)}`;
            if (n) u += '&name=' + encodeURIComponent(n);
            if (s) u += '&status=' + encodeURIComponent(s);
            if (tg) u += '&tag=' + encodeURIComponent(tg);
            const r = await fetchApi(u);
            isLoading = false;
            if (!r) return;
            if (r.code !== 200) { toast(r.message || '搜索出错', 'error'); return; }
            const pg = r.data;
            hasMore = !pg.last;
            totalElements = pg.totalElements || 0;
            currentPage++;
            if (reset !== false) { loadedCount = pg.content ? pg.content.length : 0; renderView(pg.content); }
            else { loadedCount += pg.content ? pg.content.length : 0; appendResults(pg.content); }
            updatePaginationIndicator();
        }

        /* Render only the active view */
        function renderView(list) {
            const tb = document.querySelector('tbody'), tc = document.querySelector('.table-card'), em = document.getElementById('emptySearch');
            const dc = document.getElementById('detailView'), gc = document.getElementById('galleryView');
            const kw = document.getElementById('searchName').value.trim();
            if (!tb) return;
            if (!list || list.length === 0) {
                tb.innerHTML = '';
                if (tc) tc.style.display = 'none'; if (dc) dc.style.display = 'none'; if (gc) gc.style.display = 'none'; if (em) em.style.display = ''; return;
            }
            if (em) em.style.display = 'none';
            if (tc) tc.style.display = viewMode === 'table' ? '' : 'none';
            if (dc) dc.classList.toggle('active', viewMode === 'detail');
            if (gc) gc.classList.toggle('active', viewMode === 'gallery');
            list.forEach(a => _cache[a.id] = a);
            if (viewMode === 'table') {
                tb.innerHTML = '';
                list.forEach((a, i) => {
                    const r = document.createElement('tr'); r.id = 'r-' + a.id;
                    const cv = a.coverUrl ? `<img src="${esc(a.coverUrl)}" class="cover-img" onerror="this.outerHTML='<div class=cover-empty>N/A</div>'">` : '<div class="cover-empty">N/A</div>';
                    r.innerHTML = `<td class="drag-handle" style="cursor:grab;color:var(--text-faint);padding:8px 4px;">⠿</td><td style="width:36px;padding:12px 8px;text-align:center;"><input type="checkbox" class="batch-cb" data-id="${a.id}" onchange="toggleRowSelect(${a.id}, this.checked)"></td><td>${cv}</td><td style="color:var(--text-dim);font-size:0.82em;">${i+1}</td>
                        <td><span class="clickable-name" onclick="openDetailModal(${a.id})">${highlightText(a.name, kw)}</span>${a.tags ? '<div style="margin-top:4px;">' + renderTags(a.tags) + '</div>' : ''}</td>
                        <td><span class="season-tag">${esc(a.season)}</span></td>
                        <td><select class="e-select" id="ss-${a.id}" onchange="changeStatus(${a.id})"><option value="watching" ${a.status==='watching'?'selected':''}>追中</option><option value="finished" ${a.status==='finished'?'selected':''}>已完成</option><option value="planning" ${a.status==='planning'?'selected':''}>计划</option><option value="dropped" ${a.status==='dropped'?'selected':''}>放弃</option></select></td>
                        <td><span class="score-pill ${scoreClass(a.score)}">${esc(String(a.score))}</span></td>
                        <td class="ep">${a.currentEpisode} / ${a.totalEpisodes}</td>
                        <td><span class="remark-cell" title="${esc(a.remark)}">${esc(a.remark)}</span></td>
                        <td><div class="acts"><button class="a-btn" onclick="openEditModal(${a.id})">编辑</button><button class="a-btn ep-btn" onclick="prevEpisode(${a.id})">-</button><button class="a-btn ep-btn" onclick="nextEpisode(${a.id})">+</button></div></td>`;
                    tb.appendChild(r);
                });
            }
            if (viewMode === 'detail') renderDetail(list, kw);
            if (viewMode === 'gallery') renderGallery(list, kw);
        }

        function renderDetail(list, kw) {
            const g = document.getElementById('detailGrid'); if (!g) return; g.innerHTML = '';
            list.forEach(a => {
                const card = document.createElement('div'); card.className = 'dt-card';
                const pct = a.totalEpisodes > 0 ? Math.round(a.currentEpisode / a.totalEpisodes * 100) : 0;
                const cover = a.coverUrl ? `<img src="${esc(a.coverUrl)}" class="dt-cover" onerror="this.outerHTML='<div class=dt-cover-empty>${esc(a.name.charAt(0))}</div>'">` : `<div class="dt-cover-empty">${esc(a.name.charAt(0))}</div>`;
                card.innerHTML = `<div class="dt-cover-wrap">${cover}<span class="dt-status-badge ${a.status}">${SM[a.status] || a.status}</span></div>
                    <div class="dt-body">
                        <div class="dt-name clickable-name" onclick="openDetailModal(${a.id})">${highlightText(a.name, kw)}</div>
                        <div class="dt-meta"><span class="dt-tag dt-season">${esc(a.season)}</span><span class="dt-tag dt-score ${scoreClass(a.score)}">${a.score}</span>${a.tags ? renderTags(a.tags) : ''}</div>
                        <div class="dt-progress-wrap"><div class="dt-progress-label"><span>${a.currentEpisode} / ${a.totalEpisodes} ep</span><span>${pct}%</span></div><div class="dt-progress"><div class="dt-progress-bar ${a.status}" style="width:${pct}%"></div></div></div>
                        ${a.remark ? `<div class="dt-remark">${renderRemark(a.remark)}</div>` : '<div class="dt-remark" style="visibility:hidden;">-</div>'}
                        <div class="dt-actions"><button class="dt-btn" onclick="openEditModal(${a.id})">编辑</button><button class="dt-btn ep" onclick="prevEpisode(${a.id})">-</button><button class="dt-btn ep" onclick="nextEpisode(${a.id})">+</button><button class="dt-btn del" onclick="deleteAnime(${a.id})">删除</button></div>
                    </div>`;
                g.appendChild(card);
            });
        }

        function renderGallery(list, kw) {
            const g = document.getElementById('galleryGrid'); if (!g) return; g.innerHTML = '';
            list.forEach(a => {
                const card = document.createElement('div'); card.className = 'g-card'; card.id = 'gc-' + a.id;
                const pct = a.totalEpisodes > 0 ? Math.round(a.currentEpisode / a.totalEpisodes * 100) : 0;
                const cover = a.coverUrl ? `<img src="${esc(a.coverUrl)}" class="g-cover" onerror="this.outerHTML='<div class=g-cover-empty>${esc(a.name.charAt(0))}</div>'">` : `<div class="g-cover-empty">${esc(a.name.charAt(0))}</div>`;
                card.innerHTML = `${cover}
                    <div class="g-body">
                        <div class="g-name clickable-name" onclick="openDetailModal(${a.id})">${highlightText(a.name, kw)}</div>
                        <div class="g-meta"><span class="g-tag g-season">${esc(a.season)}</span><span class="g-tag g-score ${scoreClass(a.score)}">${a.score}</span>${a.tags ? renderTags(a.tags) : ''}</div>
                        <div class="g-progress"><div class="g-progress-bar ${a.status}" style="width:${pct}%"></div></div>
                        <div class="g-ep">${a.currentEpisode} / ${a.totalEpisodes} ep &middot; ${SM[a.status] || a.status}</div>
                        <div class="g-actions"><button class="g-btn" onclick="openEditModal(${a.id})">编辑</button><button class="g-btn ep" onclick="prevEpisode(${a.id})">-</button><button class="g-btn ep" onclick="nextEpisode(${a.id})">+</button><button class="g-btn del" onclick="deleteAnime(${a.id})">删</button></div>
                    </div>`;
                g.appendChild(card);
            });
        }

        /* Append results for infinite scroll */
        function appendResults(list) {
            if (!list || list.length === 0) return;
            list.forEach(a => _cache[a.id] = a);
            const kw = document.getElementById('searchName').value.trim();
            const tb = document.querySelector('tbody'), dg = document.getElementById('detailGrid'), gg = document.getElementById('galleryGrid');
            const existing = tb ? tb.querySelectorAll('tr').length : 0;
            list.forEach((a, i) => {
                const r = document.createElement('tr'); r.id = 'r-' + a.id;
                const cv = a.coverUrl ? `<img src="${esc(a.coverUrl)}" class="cover-img" onerror="this.outerHTML='<div class=cover-empty>N/A</div>'">` : '<div class="cover-empty">N/A</div>';
                r.innerHTML = `<td class="drag-handle" style="cursor:grab;color:var(--text-faint);padding:8px 4px;">⠿</td><td style="width:36px;padding:12px 8px;text-align:center;"><input type="checkbox" class="batch-cb" data-id="${a.id}" onchange="toggleRowSelect(${a.id}, this.checked)"></td><td>${cv}</td><td style="color:var(--text-dim);font-size:0.82em;">${existing + i + 1}</td>
                    <td><span class="clickable-name" onclick="openDetailModal(${a.id})">${highlightText(a.name, kw)}</span>${a.tags ? '<div style="margin-top:4px;">' + renderTags(a.tags) + '</div>' : ''}</td>
                    <td><span class="season-tag">${esc(a.season)}</span></td>
                    <td><select class="e-select" id="ss-${a.id}" onchange="changeStatus(${a.id})"><option value="watching" ${a.status==='watching'?'selected':''}>追中</option><option value="finished" ${a.status==='finished'?'selected':''}>已完成</option><option value="planning" ${a.status==='planning'?'selected':''}>计划</option><option value="dropped" ${a.status==='dropped'?'selected':''}>放弃</option></select></td>
                    <td><span class="score-pill ${scoreClass(a.score)}">${esc(String(a.score))}</span></td>
                    <td class="ep">${a.currentEpisode} / ${a.totalEpisodes}</td>
                    <td><span class="remark-cell" title="${esc(a.remark)}">${esc(a.remark)}</span></td>
                    <td><div class="acts"><button class="a-btn" onclick="openEditModal(${a.id})">编辑</button><button class="a-btn ep-btn" onclick="prevEpisode(${a.id})">-</button><button class="a-btn ep-btn" onclick="nextEpisode(${a.id})">+</button></div></td>`;
                tb.appendChild(r);
            });
            if (dg) list.forEach(a => {
                const card = document.createElement('div'); card.className = 'dt-card';
                const pct = a.totalEpisodes > 0 ? Math.round(a.currentEpisode / a.totalEpisodes * 100) : 0;
                const cover = a.coverUrl ? `<img src="${esc(a.coverUrl)}" class="dt-cover" onerror="this.outerHTML='<div class=dt-cover-empty>${esc(a.name.charAt(0))}</div>'">` : `<div class="dt-cover-empty">${esc(a.name.charAt(0))}</div>`;
                card.innerHTML = `<div class="dt-cover-wrap">${cover}<span class="dt-status-badge ${a.status}">${SM[a.status] || a.status}</span></div><div class="dt-body"><div class="dt-name clickable-name" onclick="openDetailModal(${a.id})">${highlightText(a.name, kw)}</div><div class="dt-meta"><span class="dt-tag dt-season">${esc(a.season)}</span><span class="dt-tag dt-score ${scoreClass(a.score)}">${a.score}</span>${a.tags ? renderTags(a.tags) : ''}</div><div class="dt-progress-wrap"><div class="dt-progress-label"><span>${a.currentEpisode} / ${a.totalEpisodes} ep</span><span>${pct}%</span></div><div class="dt-progress"><div class="dt-progress-bar ${a.status}" style="width:${pct}%"></div></div></div>${a.remark ? `<div class="dt-remark">${renderRemark(a.remark)}</div>` : '<div class="dt-remark" style="visibility:hidden;">-</div>'}<div class="dt-actions"><button class="dt-btn" onclick="openEditModal(${a.id})">编辑</button><button class="dt-btn ep" onclick="prevEpisode(${a.id})">-</button><button class="dt-btn ep" onclick="nextEpisode(${a.id})">+</button><button class="dt-btn del" onclick="deleteAnime(${a.id})">删除</button></div></div>`;
                dg.appendChild(card);
            });
            if (gg) list.forEach(a => {
                const card = document.createElement('div'); card.className = 'g-card'; card.id = 'gc-' + a.id;
                const pct = a.totalEpisodes > 0 ? Math.round(a.currentEpisode / a.totalEpisodes * 100) : 0;
                const cover = a.coverUrl ? `<img src="${esc(a.coverUrl)}" class="g-cover" onerror="this.outerHTML='<div class=g-cover-empty>${esc(a.name.charAt(0))}</div>'">` : `<div class="g-cover-empty">${esc(a.name.charAt(0))}</div>`;
                card.innerHTML = `${cover}<div class="g-body"><div class="g-name clickable-name" onclick="openDetailModal(${a.id})">${highlightText(a.name, kw)}</div><div class="g-meta"><span class="g-tag g-season">${esc(a.season)}</span><span class="g-tag g-score ${scoreClass(a.score)}">${a.score}</span>${a.tags ? renderTags(a.tags) : ''}</div><div class="g-progress"><div class="g-progress-bar ${a.status}" style="width:${pct}%"></div></div><div class="g-ep">${a.currentEpisode} / ${a.totalEpisodes} ep &middot; ${SM[a.status] || a.status}</div><div class="g-actions"><button class="g-btn" onclick="openEditModal(${a.id})">编辑</button><button class="g-btn ep" onclick="prevEpisode(${a.id})">-</button><button class="g-btn ep" onclick="nextEpisode(${a.id})">+</button><button class="g-btn del" onclick="deleteAnime(${a.id})">删</button></div></div>`;
                gg.appendChild(card);
            });
        }

        function resetSearch() { document.getElementById('searchName').value = ''; document.getElementById('filterStatus').value = ''; document.getElementById('sortBy').value = 'id-desc'; document.getElementById('filterTag').value = ''; toast('已重置', 'info'); performSearch(); }

        function updatePaginationIndicator() {
            const el = document.getElementById('paginationIndicator');
            if (!el) return;
            if (totalElements <= 0) { el.style.display = 'none'; return; }
            el.style.display = '';
            el.textContent = hasMore ? `已加载 ${loadedCount} / ${totalElements} 条，向下滚动加载更多` : `共 ${totalElements} 条，已全部加载`;
        }

        /* Stats — 只请求 detailed 端点（已包含概览数据） */
        async function updateStats() {
            const r = await fetchApi('/api/anime/stats/detailed');
            if (r && r.code === 200) {
                const d = r.data;
                document.getElementById('stat-total').textContent = d.total || 0;
                document.getElementById('stat-watching').textContent = d.watching || 0;
                document.getElementById('stat-finished').textContent = d.finished || 0;
                document.getElementById('stat-planning').textContent = d.planning || 0;
                document.getElementById('stat-dropped').textContent = d.dropped || 0;
                document.getElementById('stat-progress').textContent = (d.progressPercentage || 0).toFixed(1) + '%';
                document.getElementById('stat-episodes').textContent = (d.watchedEpisodes || 0) + ' / ' + (d.totalEpisodes || 0);
                document.getElementById('stat-avg-score').textContent = (d.averageScore || 0).toFixed(1);
                document.getElementById('stat-high-score').textContent = d.highScore || 0;
                document.getElementById('stat-medium-score').textContent = d.mediumScore || 0;
                document.getElementById('stat-low-score').textContent = d.lowScore || 0;
            }
        }

        /* Charts */
        let ch1 = null, ch2 = null, ch3 = null, ch4 = null, ch5 = null;
        Chart.defaults.font.family = 'Inter';
        Chart.defaults.color = '#a89f94';
        Chart.defaults.borderColor = '#ede8e0';
        async function loadCharts() {
            const r1 = await fetchApi('/api/anime/stats/detailed');
            if (r1 && r1.code === 200) {
                const d = r1.data;
                if (ch1) ch1.destroy();
                ch1 = new Chart(document.getElementById('c1'), { type: 'doughnut', data: { labels: ['追中','已完成','计划','放弃'], datasets: [{ data: [d.watching,d.finished,d.planning,d.dropped], backgroundColor: ['#7a9ec4','#9a8ab8','#d4a574','#c47a8a'], borderWidth: 0 }] }, options: { responsive: true, cutout: '62%', plugins: { legend: { position: 'bottom', labels: { padding: 20, usePointStyle: true, pointStyleWidth: 8, font: { size: 12, weight: '500' } } } } } });
                if (ch2) ch2.destroy();
                ch2 = new Chart(document.getElementById('c2'), { type: 'bar', data: { labels: ['高分 >=8','中等 6~8','低分 <6'], datasets: [{ data: [d.highScore,d.mediumScore,d.lowScore], backgroundColor: ['rgba(124,168,130,0.5)','rgba(212,165,116,0.5)','rgba(196,122,138,0.5)'], borderRadius: 8, borderSkipped: false }] }, options: { responsive: true, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { stepSize: 1 }, grid: { color: '#f0ece5' } }, x: { grid: { display: false } } } } });
            }
            const r2 = await fetchApi('/api/anime/stats/seasons');
            if (r2 && r2.code === 200) { const c = document.getElementById('sg'); c.innerHTML = ''; (r2.data.seasons || []).forEach(s => { const el = document.createElement('div'); el.className = 'season-card'; el.innerHTML = `<div class="sn">${esc(s.season)}</div><div class="sc">${s.count}</div><div class="sa">${s.avgScore != null ? 'avg ' + s.avgScore.toFixed(1) : '-'}</div>`; c.appendChild(el); }); }
            const r3 = await fetchApi('/api/anime/stats/enhanced');
            if (r3 && r3.code === 200) {
                const d = r3.data;
                const gridColor = document.documentElement.getAttribute('data-theme') === 'dark' ? '#3a3630' : '#f0ece5';
                const tickColor = document.documentElement.getAttribute('data-theme') === 'dark' ? '#a09888' : '#8a8278';
                if (d.yearly && d.yearly.length) {
                    const labels = d.yearly.map(y => y.year + '年');
                    if (ch3) ch3.destroy();
                    ch3 = new Chart(document.getElementById('c-yoy'), { type: 'bar', data: {
                        labels: labels,
                        datasets: [
                            { label: '数量', data: d.yearly.map(y => y.count), backgroundColor: 'rgba(122,158,196,0.5)', borderRadius: 6, yAxisID: 'y' },
                            { label: '均分', data: d.yearly.map(y => y.avgScore || 0), type: 'line', borderColor: '#d4a574', backgroundColor: 'transparent', pointBackgroundColor: '#d4a574', tension: 0.3, yAxisID: 'y1' }
                        ]}, options: { responsive: true, plugins: { legend: { position: 'bottom', labels: { usePointStyle: true, pointStyleWidth: 8, font: { size: 11 } } } }, scales: { y: { beginAtZero: true, position: 'left', ticks: { stepSize: 1, color: tickColor }, grid: { color: gridColor } }, y1: { beginAtZero: true, position: 'right', min: 0, max: 10, ticks: { color: tickColor }, grid: { display: false } }, x: { ticks: { color: tickColor }, grid: { display: false } } } } });
                }
                if (d.scoreDistribution && d.scoreDistribution.length) {
                    if (ch4) ch4.destroy();
                    ch4 = new Chart(document.getElementById('c-hist'), { type: 'bar', data: {
                        labels: d.scoreDistribution.map(s => s.bucket + '分'),
                        datasets: [{ label: '数量', data: d.scoreDistribution.map(s => s.count), backgroundColor: d.scoreDistribution.map(s => { const b = s.bucket; if (b >= 8) return 'rgba(124,168,130,0.6)'; if (b >= 6) return 'rgba(212,165,116,0.6)'; return 'rgba(196,122,138,0.6)'; }), borderRadius: 6, borderSkipped: false }]
                    }, options: { responsive: true, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { stepSize: 1, color: tickColor }, grid: { color: gridColor } }, x: { ticks: { color: tickColor }, grid: { display: false } } } } });
                }
                if (d.tags && d.tags.length) {
                    const tagColors = ['#7a9ec4','#9a8ab8','#d4a574','#c47a8a','#7ac4a4','#c4b87a','#8ac47a','#c47a7a','#7a7ac4','#c4a47a','#a47ac4','#7ac4c4','#c47aa4','#7aa4c4','#c4a4a4'];
                    if (ch5) ch5.destroy();
                    ch5 = new Chart(document.getElementById('c-tags'), { type: 'doughnut', data: {
                        labels: d.tags.map(t => t.tag),
                        datasets: [{ data: d.tags.map(t => t.count), backgroundColor: tagColors.slice(0, d.tags.length), borderWidth: 0 }]
                    }, options: { responsive: true, cutout: '50%', plugins: { legend: { position: 'right', labels: { usePointStyle: true, pointStyleWidth: 8, font: { size: 11 }, padding: 12 } } } } });
                }
                // 观看时长 TOP 10
                if (d.watchDuration && d.watchDuration.length > 0) {
                    const top10 = d.watchDuration.slice(0, 10);
                    const ctxDur = document.getElementById('c-duration');
                    if (ctxDur) {
                        new Chart(ctxDur, {
                            type: 'bar',
                            data: {
                                labels: top10.map(d => d.name.length > 8 ? d.name.substring(0, 8) + '...' : d.name),
                                datasets: [{
                                    label: '观看天数',
                                    data: top10.map(d => d.days),
                                    backgroundColor: 'rgba(74,106,208,0.6)',
                                    borderRadius: 4
                                }]
                            },
                            options: { indexAxis: 'y', responsive: true, plugins: { legend: { display: false } }, scales: { x: { title: { display: true, text: '天' } } } }
                        });
                    }
                }
                // 月度完成趋势
                if (d.monthlyReport && Object.keys(d.monthlyReport).length > 0) {
                    const months = Object.keys(d.monthlyReport).sort();
                    const ctxMon = document.getElementById('c-monthly');
                    if (ctxMon) {
                        new Chart(ctxMon, {
                            type: 'line',
                            data: {
                                labels: months,
                                datasets: [{
                                    label: '完成番剧数',
                                    data: months.map(m => d.monthlyReport[m].count),
                                    borderColor: 'rgba(74,106,208,0.8)',
                                    backgroundColor: 'rgba(74,106,208,0.1)',
                                    fill: true, tension: 0.3, pointRadius: 4
                                }, {
                                    label: '平均评分',
                                    data: months.map(m => d.monthlyReport[m].avgScore),
                                    borderColor: 'rgba(90,138,96,0.8)',
                                    backgroundColor: 'rgba(90,138,96,0.1)',
                                    fill: false, tension: 0.3, pointRadius: 4, yAxisID: 'y1'
                                }]
                            },
                            options: {
                                responsive: true,
                                plugins: { legend: { position: 'bottom' } },
                                scales: {
                                    y: { beginAtZero: true, title: { display: true, text: '番剧数' } },
                                    y1: { position: 'right', min: 0, max: 10, title: { display: true, text: '评分' }, grid: { drawOnChartArea: false } }
                                }
                            }
                        });
                    }
                }
                document.getElementById('stat-ep-day').textContent = d.episodesPerDay || '0';
                document.getElementById('stat-ep-month').textContent = d.episodesPerMonth || '0';
                loadRecommendations();
                // 旧番数量提示
                const lc = d.legacyCount || 0;
                const epDaySub = document.getElementById('stat-ep-day')?.closest('.d-card')?.querySelector('.d-sub');
                if (epDaySub) epDaySub.textContent = lc > 0 ? `排除 ${lc} 部旧番` : '观看习惯';
            }
        }

        /* Recommendations */
        async function loadRecommendations() {
            const grid = document.getElementById('recGrid');
            if (!grid) return;
            grid.innerHTML = '<div class="bangumi-loading"><div class="skeleton skeleton-text long"></div></div>';
            const r = await fetchApi('/api/anime/recommendations');
            if (r && r.code === 200 && r.data && r.data.length > 0) {
                grid.innerHTML = '';
                r.data.forEach(item => {
                    const card = document.createElement('div');
                    card.className = 'browse-card';
                    const img = item.image ? `<img src="${esc(item.image)}" class="browse-cover" onerror="this.outerHTML='<div class=browse-cover-empty>${esc((item.nameCn||item.name||'').charAt(0))}</div>'">` : `<div class="browse-cover-empty">${esc((item.nameCn||item.name||'').charAt(0))}</div>`;
                    card.innerHTML = `${img}<div class="browse-info"><div class="browse-name">${esc(item.nameCn || item.name)}</div><div class="browse-meta">${item.score ? '⭐ ' + item.score : ''} ${item.date || ''}</div><div class="browse-reason" style="font-size:0.7em;color:var(--text-faint);margin-top:4px;">${esc(item.reason)}</div></div>`;
                    card.onclick = () => {
                        document.getElementById('animeName').value = item.nameCn || item.name;
                        searchBangumi();
                    };
                    grid.appendChild(card);
                });
            } else {
                grid.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-faint);font-size:0.85em;">暂无推荐，多追几部番就有了</div>';
            }
        }

        /* Calendar */
        const DAY_NAMES = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];
        const DAY_SHORT = ['', '一', '二', '三', '四', '五', '六', '日'];
        let calViewMode = 'mine';
        let calSelectedDay = 0;
        let _scheduleData = null;

        function switchCalView(mode) {
            calViewMode = mode;
            document.getElementById('calViewMine').classList.toggle('active', mode === 'mine');
            document.getElementById('calViewAll').classList.toggle('active', mode === 'all');
            renderCalDay();
        }

        function selectCalDay(day) {
            calSelectedDay = day;
            document.querySelectorAll('.cal-day-btn').forEach(b => b.classList.toggle('active', parseInt(b.dataset.day) === day));
            renderCalDay();
        }

        async function loadCalendar() {
            const r = await fetchApi('/api/anime/airing-schedule');
            if (!r || r.code !== 200) return;
            _scheduleData = r.data;
            renderCalDays();
            selectCalDay(_scheduleData.todayDay || 1);
        }

        function renderCalDays() {
            if (!_scheduleData) return;
            const todayIdx = _scheduleData.todayDay || 1;
            const container = document.getElementById('calDays'); container.innerHTML = '';
            const dataSource = calViewMode === 'mine' ? _scheduleData.mySchedule : _scheduleData.bangumiSchedule;
            for (let day = 1; day <= 7; day++) {
                const count = (dataSource && dataSource[day]) ? dataSource[day].length : 0;
                const btn = document.createElement('div');
                btn.className = 'cal-day-btn' + (day === todayIdx ? ' today' : '') + (day === calSelectedDay ? ' active' : '');
                btn.dataset.day = day;
                btn.innerHTML = `<div class="cal-day-name">${DAY_NAMES[day]}</div><div class="cal-day-num">${count > 0 ? count : '·'}</div>`;
                btn.onclick = () => selectCalDay(day);
                container.appendChild(btn);
            }
        }

        function renderCalDay() {
            if (!_scheduleData) return;
            renderCalDays();
            const content = document.getElementById('calContent'); content.innerHTML = '';
            const dataSource = calViewMode === 'mine' ? _scheduleData.mySchedule : _scheduleData.bangumiSchedule;
            const list = (dataSource && dataSource[calSelectedDay]) || [];

            if (list.length === 0) {
                content.innerHTML = `<div class="cal-empty"><div class="cal-empty-icon">📭</div><div>${DAY_NAMES[calSelectedDay]}暂无${calViewMode === 'mine' ? '追番' : '放送'}</div></div>`;
                return;
            }

            const grid = document.createElement('div'); grid.className = 'cal-grid';
            list.forEach(a => {
                const card = document.createElement('div'); card.className = 'cal-card';
                if (calViewMode === 'mine') {
                    card.onclick = () => openDetailModal(a.id);
                    const cover = a.coverUrl ? `<img src="${esc(a.coverUrl)}" class="cc-cover" onerror="this.outerHTML='<div class=cc-cover-empty>${esc(a.name.charAt(0))}</div>'">` : `<div class="cc-cover-empty">${esc(a.name.charAt(0))}</div>`;
                    const scClass = a.score >= 8 ? 'sc-high' : a.score >= 6 ? 'sc-mid' : 'sc-low';
                    const countdown = a.daysUntilAir != null ? (a.daysUntilAir > 0 ? `<div class="cc-countdown future">开播 ${a.daysUntilAir} 天后</div>` : a.daysUntilAir === 0 ? `<div class="cc-countdown future">今天开播</div>` : `<div class="cc-countdown past">已放送 ${Math.abs(a.daysUntilAir)} 天</div>`) : '';
                    const airDateHtml = a.airDate ? `<div class="cc-airdate">${a.airDate}</div>` : '';
                    card.innerHTML = `${cover}<span class="cc-badge ${a.status}">${SM[a.status] || a.status}</span>
                        <div class="cc-body">
                            <div class="cc-name">${esc(a.name)}</div>
                            <div class="cc-meta"><span class="cc-tag cc-score ${scClass}">${a.score || '-'}</span></div>
                            <div class="cc-ep">${a.currentEpisode}/${a.totalEpisodes} ep</div>
                            ${airDateHtml}${countdown}
                        </div>`;
                } else {
                    const imgUrl = a.image ? (a.image.startsWith('//') ? 'https:' + a.image : a.image) : '';
                    const cover = imgUrl ? `<img src="${esc(imgUrl)}" class="cc-cover" onerror="this.outerHTML='<div class=cc-cover-empty>${esc((a.nameCn||a.name||'?').charAt(0))}</div>'">` : `<div class="cc-cover-empty">${esc((a.nameCn||a.name||'?').charAt(0))}</div>`;
                    const displayName = a.nameCn || a.name || '';
                    const scoreNum = a.score ? Number(a.score) : 0;
                    const scClass = scoreNum >= 8 ? 'sc-high' : scoreNum >= 6 ? 'sc-mid' : 'sc-low';
                    const eps = a.eps || '?';
                    const badgeText = a.isTracked ? '已追' : `${eps}集`;
                    const countdown = a.daysUntilAir != null ? (a.daysUntilAir > 0 ? `<div class="cc-countdown future">开播 ${a.daysUntilAir} 天后</div>` : a.daysUntilAir === 0 ? `<div class="cc-countdown future">今天开播</div>` : '') : '';
                    const airDateHtml = a.airDate ? `<div class="cc-airdate">${a.airDate}</div>` : '';
                    card.innerHTML = `${cover}<span class="cc-badge ${a.isTracked ? 'tracked' : ''}">${badgeText}</span>
                        <div class="cc-body">
                            <div class="cc-name">${esc(displayName)}</div>
                            <div class="cc-meta"><span class="cc-tag cc-score ${scClass}">${scoreNum > 0 ? scoreNum.toFixed(1) : '-'}</span></div>
                            ${airDateHtml}${countdown}
                        </div>`;
                    if (a.isTracked) {
                        card.onclick = () => {
                            const localId = Object.keys(_cache).find(id => _cache[id].bangumiId === a.id);
                            if (localId) openDetailModal(parseInt(localId));
                            else openBangumiDetailModal(a.id);
                        };
                    } else {
                        card.onclick = () => openBangumiDetailModal(a.id);
                    }
                }
                grid.appendChild(card);
            });
            content.appendChild(grid);
        }

        /* Calendar mode switch (calendar / season / rank) */
        let calMode = 'calendar';
        let _seasonSort = 'score', _seasonOffset = 0, _rankSort = 'rank', _rankOffset = 0;
        function switchCalMode(mode) {
            calMode = mode;
            document.getElementById('modeCal').classList.toggle('active', mode === 'calendar');
            document.getElementById('modeSeason').classList.toggle('active', mode === 'season');
            document.getElementById('modeRank').classList.toggle('active', mode === 'rank');
            document.getElementById('calModeCalendar').style.display = mode === 'calendar' ? '' : 'none';
            document.getElementById('calModeSeason').style.display = mode === 'season' ? '' : 'none';
            document.getElementById('calModeRank').style.display = mode === 'rank' ? '' : 'none';
            if (mode === 'season' && !document.getElementById('seasonGrid').children.length) loadSeasonAnime('rank');
            if (mode === 'rank' && !document.getElementById('rankGrid').children.length) loadRankings('rank');
        }

        async function loadSeasonAnime(sort, append) {
            if (sort) { _seasonSort = sort; _seasonOffset = 0; }
            const grid = document.getElementById('seasonGrid');
            if (!append) grid.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-faint);">加载中...</div>';
            const r = await fetchApi(`/api/bangumi/season?sort=${_seasonSort}&limit=20&offset=${_seasonOffset}`);
            if (!r || r.code !== 200) { grid.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-faint);">加载失败</div>'; return; }
            const list = r.data || [];
            if (!append) grid.innerHTML = '';
            list.forEach(item => grid.appendChild(renderBrowseCard(item)));
            _seasonOffset += list.length;
            document.getElementById('seasonLoadMore').style.display = list.length >= 20 ? '' : 'none';
            // 更新按钮 active 状态
            document.querySelectorAll('#calModeSeason .cal-toggle-btn').forEach(b => b.classList.remove('active'));
            const labels = { rank: '排名', date: '时间' };
            document.querySelectorAll('#calModeSeason .cal-toggle-btn').forEach(b => { if (b.textContent === labels[_seasonSort]) b.classList.add('active'); });
        }

        async function loadRankings(sort, append) {
            if (sort) { _rankSort = sort; _rankOffset = 0; }
            const grid = document.getElementById('rankGrid');
            if (!append) grid.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-faint);">加载中...</div>';
            const r = await fetchApi(`/api/bangumi/rankings?sort=${_rankSort}&limit=20&offset=${_rankOffset}`);
            if (!r || r.code !== 200) { grid.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-faint);">加载失败</div>'; return; }
            const list = r.data || [];
            if (!append) grid.innerHTML = '';
            list.forEach(item => grid.appendChild(renderBrowseCard(item)));
            _rankOffset += list.length;
            document.getElementById('rankLoadMore').style.display = list.length >= 20 ? '' : 'none';
            document.querySelectorAll('#calModeRank .cal-toggle-btn').forEach(b => b.classList.remove('active'));
            const labels = { rank: '排名', date: '时间' };
            document.querySelectorAll('#calModeRank .cal-toggle-btn').forEach(b => { if (b.textContent === labels[_rankSort]) b.classList.add('active'); });
        }

        function renderBrowseCard(item) {
            const card = document.createElement('div'); card.className = 'browse-card';
            const isTracked = Object.values(_cache).some(a => a.bangumiId === item.id);
            const imgUrl = item.image ? (item.image.startsWith('//') ? 'https:' + item.image : item.image) : '';
            const cover = imgUrl ? `<img src="${esc(imgUrl)}" class="browse-cover" onerror="this.outerHTML='<div class=browse-cover-empty>${esc((item.nameCn||item.name||'?').charAt(0))}</div>'">` : `<div class="browse-cover-empty">${esc((item.nameCn||item.name||'?').charAt(0))}</div>`;
            const displayName = item.nameCn || item.name || '';
            const scoreNum = item.score ? Number(item.score) : 0;
            const scClass = scoreNum >= 8 ? 'sc-high' : scoreNum >= 6 ? 'sc-mid' : 'sc-low';
            const eps = item.eps || '?';
            const rankBadge = item.rank ? `<span class="browse-rank">#${item.rank}</span>` : '';
            card.innerHTML = `${cover}<div class="browse-info">
                <div class="browse-name" title="${esc(displayName)}">${esc(displayName)}</div>
                <div class="browse-sub" title="${esc(item.name)}">${esc(item.name)}</div>
                <div class="browse-meta">${rankBadge}<span class="browse-score ${scClass}">${scoreNum > 0 ? scoreNum.toFixed(1) : '-'}</span><span>${eps} 集</span><span>${esc(item.date || '')}</span></div>
                <div class="browse-actions">${isTracked ? '<span class="browse-btn tracked">已追</span>' : `<button class="browse-btn" onclick="event.stopPropagation();addToTrackingFromBrowse(${item.id},'${esc(item.nameCn || item.name || '')}',${item.eps || 0},'${esc(item.date || '')}','${esc(imgUrl)}')">+ 添加</button>`}</div>
            </div>`;
            if (!isTracked) card.onclick = () => openBangumiDetailModal(item.id);
            return card;
        }

        function addToTrackingFromBrowse(bangumiId, name, eps, date, image) {
            document.getElementById('animeBangumiId').value = bangumiId;
            document.getElementById('animeName').value = name;
            if (eps) document.getElementById('totalEpisodes').value = eps;
            if (image) document.getElementById('animeCover').value = image;
            if (date) {
                document.getElementById('animeStartDate').value = date;
                const m = parseInt(date.split('-')[1]);
                const y = date.split('-')[0];
                if (m && y) {
                    const seasons = { 1:'冬',2:'冬',3:'春',4:'春',5:'春',6:'夏',7:'夏',8:'夏',9:'秋',10:'秋',11:'秋',12:'冬' };
                    document.getElementById('animeSeason').value = y + seasons[m];
                }
                try {
                    const d = new Date(date);
                    const jsDay = d.getDay();
                    document.getElementById('animeBroadcastDay').value = jsDay === 0 ? 7 : jsDay;
                } catch(e) {}
            }
            switchTab('list');
            setTimeout(() => document.querySelector('.add-form')?.scrollIntoView({ behavior: 'smooth' }), 200);
            toast('已填充 ' + name, 'success');
        }

        /* Bangumi detail modal for untracked anime */
        async function openBangumiDetailModal(bangumiId) {
            const overlay = document.createElement('div'); overlay.className = 'detail-overlay'; overlay.id = 'detailModal';
            overlay.onclick = function(e) { if (e.target === overlay) closeDetailModal(); };
            const card = document.createElement('div'); card.className = 'detail-card';
            card.innerHTML = '<div class="detail-body" style="text-align:center;padding:40px;"><div class="skeleton skeleton-text long" style="margin:0 auto 12px;"></div><div class="skeleton skeleton-text medium" style="margin:0 auto;"></div></div>';
            overlay.appendChild(card); document.body.appendChild(overlay);
            trapFocus(overlay);

            const [detailRes, epsRes] = await Promise.all([
                fetchApi(`/api/bangumi/subject/${bangumiId}`),
                fetchApi(`/api/bangumi/subject/${bangumiId}/episodes`)
            ]);

            if (!detailRes || detailRes.code !== 200 || !detailRes.data) {
                card.innerHTML = '<div class="detail-body"><div class="detail-title">加载失败</div><div class="detail-actions"><button class="a-btn" onclick="closeDetailModal()">关闭</button></div></div>';
                return;
            }
            const d = detailRes.data;
            const displayName = d.nameCn || d.name || '';
            const cover = d.coverUrl ? `<img src="${esc(d.coverUrl)}" class="detail-cover" onerror="this.outerHTML='<div class=detail-cover-empty>${esc(displayName.charAt(0))}</div>'">` : `<div class="detail-cover-empty">${esc(displayName.charAt(0))}</div>`;

            let tagsHtml = '';
            if (d.tags && d.tags.length > 0) {
                tagsHtml = '<div class="bg-section"><div class="bg-section-title">标签</div><div class="bg-tags">';
                d.tags.forEach(t => { tagsHtml += `<span class="bg-tag">${esc(t.name || t)}</span>`; });
                tagsHtml += '</div></div>';
            }

            let ratingHtml = '';
            if (d.rating) {
                const rDist = d.ratingDetails?.count || {};
                const total = d.ratingCount || 1;
                const sageW = ((rDist[8]||0)+(rDist[9]||0)+(rDist[10]||0))/total*100;
                const amberW = ((rDist[6]||0)+(rDist[7]||0))/total*100;
                const roseW = 100 - sageW - amberW;
                ratingHtml = `<div class="bg-section"><div class="bg-section-title">Bangumi 评分</div>
                    <div style="display:flex;align-items:center;gap:12px;margin-bottom:8px;">
                        <span style="font-size:28px;font-family:var(--serif);font-weight:700;color:var(--text-strong)">${d.rating.toFixed(1)}</span>
                        <span style="color:var(--text-faint);font-size:13px">${d.ratingCount || 0} 人评分</span>
                    </div>
                    <div class="bg-rating-bar"><div class="bg-rating-sage" style="width:${sageW}%"></div><div class="bg-rating-amber" style="width:${amberW}%"></div><div class="bg-rating-rose" style="width:${roseW}%"></div></div>
                    <div style="display:flex;justify-content:space-between;font-size:11px;color:var(--text-faint);margin-top:4px;"><span>8-10</span><span>6-7</span><span>1-5</span></div>
                </div>`;
            }

            let summaryHtml = '';
            if (d.summary) {
                const truncated = d.summary.length > 300 ? d.summary.substring(0, 300) + '...' : d.summary;
                summaryHtml = `<div class="bg-section"><div class="bg-section-title">简介</div><div class="bg-summary">${esc(truncated)}</div></div>`;
            }

            let epsHtml = '';
            if (epsRes && epsRes.code === 200 && epsRes.data && epsRes.data.length > 0) {
                const eps = epsRes.data;
                epsHtml = `<div class="bg-section"><div class="bg-section-title">剧集 (${eps.length})</div><div class="bg-episodes">`;
                eps.slice(0, 12).forEach(ep => {
                    const name = ep.nameCn || ep.name || `EP${ep.sort}`;
                    const date = ep.airdate || '';
                    const aired = date && new Date(date) <= new Date();
                    epsHtml += `<div class="bg-ep ${aired ? 'aired' : 'upcoming'}"><span class="bg-ep-num">${ep.sort}</span><span class="bg-ep-name">${esc(name)}</span><span class="bg-ep-date">${esc(date)}</span></div>`;
                });
                if (eps.length > 12) epsHtml += `<div style="text-align:center;padding:8px;color:var(--text-faint);font-size:12px;">还有 ${eps.length - 12} 集...</div>`;
                epsHtml += '</div></div>';
            }

            const scoreNum = d.rating || 0;
            const scClass = scoreNum >= 8 ? 'sc-high' : scoreNum >= 6 ? 'sc-mid' : 'sc-low';
            const totalEps = d.totalEpisodes || '?';
            const airDate = d.airDate || '-';

            card.innerHTML = `<div class="detail-cover-wrap">${cover}<span class="detail-badge planning">Bangumi</span></div>
                <div class="detail-body">
                    <div class="detail-title">${esc(displayName)}</div>
                    <div class="detail-meta"><span class="detail-tag" style="background:var(--bg);border:1px solid var(--border-light);color:var(--text-mid)">${esc(airDate)}</span><span class="detail-tag" style="font-family:var(--serif);background:${scoreNum>=8?'var(--sage-soft)':scoreNum>=6?'var(--amber-soft)':'var(--rose-soft)'};color:${scoreNum>=8?'#5a8a60':scoreNum>=6?'#a08050':'#a06070'}">${scoreNum > 0 ? scoreNum.toFixed(1) : '-'}</span></div>
                    <div class="detail-info">
                        <div class="detail-info-item"><div class="detail-info-label">集数</div><div class="detail-info-val">${totalEps}</div></div>
                        <div class="detail-info-item"><div class="detail-info-label">开播</div><div class="detail-info-val">${esc(airDate)}</div></div>
                    </div>
                    ${ratingHtml}${tagsHtml}${summaryHtml}${epsHtml}
                    <div class="detail-actions"><button class="a-btn" onclick="goToAddTracking(this.dataset.name)" data-name="${esc(displayName)}">添加追踪</button><button class="a-btn" onclick="closeDetailModal()">关闭</button></div>
                </div>`;
        }

        /* Timeline */
        let timelineMode = 'watch';
        function switchTimelineMode(mode) {
            timelineMode = mode;
            document.getElementById('tlViewWatch').classList.toggle('active', mode === 'watch');
            document.getElementById('tlViewAir').classList.toggle('active', mode === 'air');
            document.getElementById('tlTitle').textContent = mode === 'air' ? '开播时间线' : '追番时间线';
            loadTL();
        }
        async function loadTL() {
            const r = await fetchApi('/api/anime/timeline?mode=' + timelineMode);
            if (!r || r.code !== 200) return;
            const c = document.getElementById('tl'); c.innerHTML = '';
            if (!r.data || r.data.length === 0) { c.innerHTML = '<p style="text-align:center;color:var(--text-faint);padding:40px 0;">暂无数据</p>'; return; }
            r.data.forEach(a => {
                const dateField = timelineMode === 'air' ? a.startDate : (a.watchStartDate || a.startDate);
                const dateLabel = timelineMode === 'air' ? '开播' : '追番';
                const el = document.createElement('div'); el.className = 'tl';
                el.innerHTML = `<div class="t-date">${esc(dateField || 'Unknown')} · ${dateLabel}</div><div class="t-name">${esc(a.name)}${a.legacy ? ' <span style="font-size:0.7em;color:var(--text-faint);">旧番</span>' : ''}</div><div class="t-meta">${SM[a.status] || a.status} · ${a.currentEpisode}/${a.totalEpisodes} ep · ${esc(String(a.score))}</div>`;
                c.appendChild(el);
            });
        }

        /* Export / Import */
        async function exportData() { const r = await fetchApi('/api/anime/export', { responseType: 'blob' }); if (!r || !r.ok) { toast('导出失败', 'error'); return; } const b = await r.blob(); const u = URL.createObjectURL(b), a = document.createElement('a'); a.href = u; a.download = 'otakulog_export.json'; a.click(); URL.revokeObjectURL(u); toast('导出成功', 'success'); }
        async function importData(e) { const f = e.target.files[0]; if (!f) return; const rd = new FileReader(); rd.onload = async function(ev) { const r = await fetchApi('/api/anime/import', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: ev.target.result }); if (r && r.code === 200) { toast(r.message || '导入成功', 'success'); performSearch(); updateStats(); } else if (r) toast(r.message || '导入失败', 'error'); }; rd.readAsText(f); e.target.value = ''; }

        /* Sync */
        function toggleSyncMenu() { const m = document.getElementById('syncMenu'); m.style.display = m.style.display === 'none' ? 'block' : 'none'; }
        document.addEventListener('click', function(e) { const m = document.getElementById('syncMenu'); const b = document.getElementById('syncBtn'); if (m && b && !m.contains(e.target) && e.target !== b) m.style.display = 'none'; });
        async function syncPush() {
            document.getElementById('syncMenu').style.display = 'none';
            toast('正在推送到 WebDAV...', 'info');
            const r = await fetchApi('/api/sync/push', { method: 'POST' });
            if (r && r.code === 200) { toast(r.data?.message || '推送成功', 'success'); } else if (r) { toast(r.message || '推送失败', 'error'); }
        }
        async function syncPull() {
            document.getElementById('syncMenu').style.display = 'none';
            if (!confirm('从 WebDAV 拉取会合并数据，确定继续？')) return;
            toast('正在从 WebDAV 拉取...', 'info');
            const r = await fetchApi('/api/sync/pull', { method: 'POST' });
            if (r && r.code === 200) { toast(r.data?.message || '拉取成功', 'success'); performSearch(); updateStats(); } else if (r) { toast(r.message || '拉取失败', 'error'); }
        }
        async function syncStatus() {
            document.getElementById('syncMenu').style.display = 'none';
            const r = await fetchApi('/api/sync/status');
            if (r && r.code === 200 && r.data) {
                const d = r.data;
                let msg = d.configured ? `WebDAV: ${d.url}\n连接: ${d.connected ? '正常' : '失败'}` : 'WebDAV 未配置';
                if (d.lastSyncTime) msg += `\n最后同步: ${d.lastSyncTime} (${d.lastSyncType === 'push' ? '推送' : '拉取'})`;
                alert(msg);
            }
        }

        /* Init */
        function init() {
            if (window.__i) return; window.__i = true;
            const savedTheme = localStorage.getItem('otakulog-theme');
            if (savedTheme === 'dark' || (!savedTheme && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
                document.documentElement.setAttribute('data-theme', 'dark');
                document.getElementById('themeToggle').textContent = '☀';
            }
            if (typeof i18n !== 'undefined') i18n.translatePage();
            if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
            updateStats(); performSearch();
            const savedTab = localStorage.getItem('otakulog-tab');
            if (savedTab && savedTab !== 'list') switchTab(savedTab);
            if (viewMode !== 'table') {
                document.querySelector('.table-card').classList.add('hidden');
                document.getElementById('viewTable').classList.remove('active');
                if (viewMode === 'detail') { document.getElementById('detailView').classList.add('active'); document.getElementById('viewDetail').classList.add('active'); }
                if (viewMode === 'gallery') { document.getElementById('galleryView').classList.add('active'); document.getElementById('viewGallery').classList.add('active'); }
            }
            document.getElementById('searchName').addEventListener('input', debounce(() => performSearch(), 300));
            document.getElementById('filterStatus').addEventListener('change', () => performSearch());
            document.getElementById('sortBy').addEventListener('change', () => performSearch());
            document.getElementById('filterTag').addEventListener('input', debounce(() => performSearch(), 300));
            document.getElementById('animeName').addEventListener('keydown', function(e) { if (e.key === 'Enter') { e.preventDefault(); searchBangumi(); } });

            // Escape 关闭弹窗
            document.addEventListener('keydown', function(e) {
                if (e.key === 'Escape') {
                    const editModal = document.getElementById('editModal');
                    const detailModal = document.getElementById('detailModal');
                    const traceMoeModal = document.getElementById('traceMoeModal');
                    if (editModal) closeEditModal();
                    else if (traceMoeModal) traceMoeModal.remove();
                    else if (detailModal) closeDetailModal();
                }
            });

            // Gallery 移动端触摸切换 actions 显示
            document.addEventListener('click', function(e) {
                const card = e.target.closest('.g-card');
                if (!card) {
                    document.querySelectorAll('.g-card.touch-active').forEach(c => c.classList.remove('touch-active'));
                    return;
                }
                // 如果点击的是操作按钮，不切换
                if (e.target.closest('.g-actions')) return;
                const wasActive = card.classList.contains('touch-active');
                document.querySelectorAll('.g-card.touch-active').forEach(c => c.classList.remove('touch-active'));
                if (!wasActive) card.classList.add('touch-active');
            });
            const sentinel = document.getElementById('scrollSentinel');
            if (sentinel) { const obs = new IntersectionObserver(entries => { if (entries[0].isIntersecting && !isLoading && hasMore) performSearch(false); }, { rootMargin: '200px' }); obs.observe(sentinel); }
            const tbody = document.querySelector('tbody');
            if (tbody && window.Sortable) {
                Sortable.create(tbody, { animation: 150, handle: '.drag-handle', ghostClass: 'dragging', onEnd: async function() {
                    const rows = tbody.querySelectorAll('tr');
                    const orders = []; rows.forEach((r, i) => { const id = parseInt(r.id.replace('r-', '')); if (id) orders.push({ id, sortOrder: i }); });
                    if (orders.length) { const r = await fetchApi('/api/anime/reorder', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(orders) }); if (r && r.code === 200) toast('排序已保存', 'success'); }
                }});
            }
        }
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
