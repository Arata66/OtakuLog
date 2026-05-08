# 番剧详情弹窗优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复老番剧缺少 Bangumi 链接的问题，并优化详情弹窗 UI 布局。

**Architecture:** 后端新增单集/批量匹配 Bangumi 端点，前端在弹窗中增加匹配按钮和外链，并将弹窗布局改为左侧竖版封面 + 右侧信息的卡片式设计。

**Tech Stack:** Spring Boot 3.2, Java 17, Thymeleaf, vanilla JS, JUnit 5

---

### Task 1: 后端 — 新增匹配 Bangumi 仓库方法

**Files:**
- Modify: `src/main/java/com/otakulog/repository/AnimeRepository.java`

- [ ] **Step 1: 添加查询方法**

在 `AnimeRepository.java` 中添加：

```java
List<Anime> findByBangumiIdIsNull();
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/otakulog/repository/AnimeRepository.java
git commit -m "feat(repo): 添加按 bangumiId 为空查询的方法"
```

---

### Task 2: 后端 — 新增匹配 Bangumi 服务方法

**Files:**
- Modify: `src/main/java/com/otakulog/service/AnimeService.java`
- Modify: `src/main/java/com/otakulog/service/impl/AnimeServiceImpl.java`

- [ ] **Step 1: 在接口添加方法**

在 `AnimeService.java` 中添加：

```java
AnimeVO matchBangumi(Long id);

Map<String, Object> batchMatchBangumi();
```

- [ ] **Step 2: 实现匹配逻辑**

在 `AnimeServiceImpl.java` 中注入 `BangumiService` 并实现：

```java
private final BangumiService bangumiService;

public AnimeServiceImpl(AnimeRepository animeRepository, BangumiService bangumiService) {
    this.animeRepository = animeRepository;
    this.bangumiService = bangumiService;
}

@Override
@Transactional
public AnimeVO matchBangumi(Long id) {
    Anime anime = animeRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("未找到该番剧"));

    if (anime.getBangumiId() != null) {
        return toVO(anime); // 已有链接，直接返回
    }

    List<BangumiResult> results = bangumiService.search(anime.getName(), 5);
    for (BangumiResult r : results) {
        if (anime.getName().equalsIgnoreCase(r.getName())
                || anime.getName().equalsIgnoreCase(r.getNameCn())) {
            anime.setBangumiId(r.getId());
            if (anime.getCoverUrl() == null && r.getImage() != null) {
                anime.setCoverUrl(r.getImage());
            }
            return toVO(animeRepository.save(anime));
        }
    }
    throw new IllegalArgumentException("未在 Bangumi 找到匹配结果");
}

@Override
@Transactional
public Map<String, Object> batchMatchBangumi() {
    List<Anime> noBangumi = animeRepository.findByBangumiIdIsNull();
    int matched = 0;
    int failed = 0;
    for (Anime anime : noBangumi) {
        try {
            matchBangumi(anime.getId());
            matched++;
            Thread.sleep(500); // 限流
        } catch (Exception e) {
            failed++;
        }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("matched", matched);
    result.put("failed", failed);
    result.put("total", noBangumi.size());
    return result;
}
```

需要在文件顶部添加 import：

```java
import com.otakulog.dto.BangumiResult;
import com.otakulog.service.BangumiService;
```

- [ ] **Step 3: 运行测试验证编译通过**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/otakulog/service/AnimeService.java src/main/java/com/otakulog/service/impl/AnimeServiceImpl.java
git commit -m "feat(service): 添加单集和批量匹配 Bangumi 方法"
```

---

### Task 3: 后端 — 新增匹配 API 端点

**Files:**
- Modify: `src/main/java/com/otakulog/controller/AnimeController.java`

- [ ] **Step 1: 添加端点**

在 `AnimeController.java` 中添加：

```java
@Operation(summary = "匹配Bangumi链接")
@PostMapping("/api/anime/{id}/match-bangumi")
@ResponseBody
public ResponseEntity<ApiResponse<AnimeVO>> matchBangumi(@PathVariable Long id) {
    try {
        AnimeVO vo = animeService.matchBangumi(id);
        return ResponseEntity.ok(ApiResponse.success("匹配成功", vo));
    } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
    }
}

@Operation(summary = "批量匹配Bangumi链接")
@PostMapping("/api/anime/batch-match-bangumi")
@ResponseBody
public ResponseEntity<ApiResponse<Map<String, Object>>> batchMatchBangumi() {
    try {
        Map<String, Object> result = animeService.batchMatchBangumi();
        return ResponseEntity.ok(ApiResponse.success("批量匹配完成", result));
    } catch (Exception e) {
        return ResponseEntity.status(500).body(ApiResponse.error(500, "批量匹配失败: " + e.getMessage()));
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -pl . -q`
Expected: 所有测试通过

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/otakulog/controller/AnimeController.java
git commit -m "feat(api): 添加匹配 Bangumi 链接端点"
```

---

### Task 4: 前端 — 弹窗布局重构为左侧封面

**Files:**
- Modify: `src/main/resources/static/js/anime-app.js:290-315`
- Modify: `src/main/resources/static/css/anime.css:504-544`

- [ ] **Step 1: 修改 CSS**

将 `.detail-card` 和相关样式改为 flex 布局。在 `anime.css` 中替换：

```css
.detail-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.45); z-index: 1000; display: flex; align-items: center; justify-content: center; animation: fadeUp 0.2s ease; }
.detail-card { background: var(--card); border-radius: var(--radius); box-shadow: 0 16px 48px rgba(0,0,0,0.15); padding: 0; width: 640px; max-width: 92vw; max-height: 90vh; overflow-y: auto; }
.detail-header { display: flex; gap: 20px; padding: 24px 28px; }
.detail-cover-wrap { position: relative; flex-shrink: 0; width: 140px; }
.detail-cover { width: 140px; height: 210px; object-fit: cover; display: block; border-radius: var(--radius-sm); }
.detail-cover-empty { width: 140px; height: 210px; background: linear-gradient(135deg, var(--bg-warm), var(--border-light)); display: flex; align-items: center; justify-content: center; font-family: var(--serif); font-size: 3em; color: var(--text-faint); border-radius: var(--radius-sm); }
.detail-badge { position: absolute; top: 8px; right: 8px; padding: 4px 10px; border-radius: 16px; font-size: 0.7em; font-weight: 600; backdrop-filter: blur(8px); }
.detail-badge.watching { background: rgba(18,46,138,0.85); color: white; }
.detail-badge.finished { background: rgba(122,106,170,0.85); color: white; }
.detail-badge.planning { background: rgba(196,149,90,0.85); color: white; }
.detail-badge.dropped { background: rgba(196,122,138,0.85); color: white; }
.detail-info-col { flex: 1; min-width: 0; display: flex; flex-direction: column; justify-content: center; gap: 8px; }
.detail-body { padding: 0 28px 28px; }
.detail-title { font-family: var(--serif); font-size: 1.3em; color: var(--text); font-weight: 400; line-height: 1.3; }
.detail-meta { display: flex; gap: 8px; flex-wrap: wrap; }
.detail-tag { font-size: 0.75em; padding: 4px 12px; border-radius: 8px; font-weight: 500; }
.detail-info { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.detail-info-item { font-size: 0.82em; }
.detail-info-label { color: var(--text-faint); font-size: 0.72em; text-transform: uppercase; letter-spacing: 1px; font-weight: 600; margin-bottom: 2px; }
.detail-info-val { color: var(--text); font-weight: 500; }
.detail-bangumi-link { display: inline-flex; align-items: center; gap: 4px; margin-top: 4px; padding: 4px 12px; font-size: 0.78em; color: var(--primary); border: 1px solid var(--primary); border-radius: 16px; text-decoration: none; transition: all 0.2s; }
.detail-bangumi-link:hover { background: var(--primary); color: white; }
.detail-progress-wrap { margin-bottom: 18px; }
.detail-progress-label { display: flex; justify-content: space-between; font-size: 0.8em; color: var(--text-dim); margin-bottom: 6px; }
.detail-progress { height: 6px; background: var(--border-light); border-radius: 3px; overflow: hidden; }
.detail-progress-bar { height: 100%; border-radius: 3px; transition: width 0.4s ease; }
.detail-progress-bar.watching { background: var(--primary); }
.detail-progress-bar.finished { background: var(--purple); }
.detail-progress-bar.planning { background: var(--amber); }
.detail-progress-bar.dropped { background: var(--rose); }
.detail-remark { font-size: 0.88em; color: var(--text-dim); line-height: 1.5; margin-bottom: 20px; padding: 12px 16px; background: var(--bg); border-radius: var(--radius-sm); border: 1px solid var(--border-light); }
.detail-remark p { margin: 0 0 8px; }
.detail-remark p:last-child { margin-bottom: 0; }
.detail-remark ul, .detail-remark ol { margin: 4px 0 8px 20px; }
.detail-remark li { margin-bottom: 2px; }
.detail-remark code { background: var(--border-light); padding: 2px 6px; border-radius: 4px; font-size: 0.9em; }
.detail-remark pre { background: var(--bg); padding: 12px; border-radius: 8px; overflow-x: auto; margin: 8px 0; border: 1px solid var(--border-light); }
.detail-remark pre code { background: none; padding: 0; }
.detail-remark blockquote { border-left: 3px solid var(--amber); padding-left: 12px; margin: 8px 0; color: var(--text-mid); }
.detail-remark a { color: var(--coral); text-decoration: underline; }
.detail-match-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; font-size: 0.82em; color: var(--primary); border: 1px solid var(--primary); border-radius: var(--radius-sm); background: transparent; cursor: pointer; transition: all 0.2s; }
.detail-match-btn:hover { background: var(--primary); color: white; }
.detail-match-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.detail-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 20px; }
.detail-actions .a-btn { padding: 8px 18px; font-size: 0.82em; }
```

添加响应式规则（在已有的 `@media (max-width: 640px)` 块中）：

```css
.detail-header { flex-direction: column; align-items: center; text-align: center; }
.detail-cover-wrap { width: 120px; }
.detail-cover { width: 120px; height: 180px; }
.detail-cover-empty { width: 120px; height: 180px; }
.detail-info { grid-template-columns: 1fr; }
```

- [ ] **Step 2: 修改 JS 弹窗 HTML 结构**

在 `anime-app.js` 中替换 `openDetailModal` 函数的 `card.innerHTML` 部分：

```javascript
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
        document.getElementById('bangumiDetailSection').innerHTML = `<div style="text-align:center;padding:12px"><button class="detail-match-btn" onclick="matchBangumiFor(${a.id}, this)">🔗 匹配 Bangumi 链接</button></div>`;
    }
}
```

- [ ] **Step 3: 添加匹配函数**

在 `anime-app.js` 中添加（放在 `closeDetailModal` 函数之前）：

```javascript
async function matchBangumiFor(id, btn) {
    btn.disabled = true;
    btn.textContent = '匹配中...';
    const r = await fetchApi(`/api/anime/${id}/match-bangumi`, { method: 'POST' });
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
```

- [ ] **Step 4: 验证**

Run: `mvn spring-boot:run`，打开浏览器查看弹窗布局是否正确。

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/static/js/anime-app.js src/main/resources/static/css/anime.css
git commit -m "feat(ui): 弹窗布局改为左侧封面 + 右侧信息，添加 Bangumi 外链和匹配按钮"
```

---

### Task 5: 前端 — 添加批量匹配入口

**Files:**
- Modify: `src/main/resources/templates/anime.html`
- Modify: `src/main/resources/static/js/anime-app.js`

- [ ] **Step 1: 在设置区域添加按钮**

在 `anime.html` 中找到设置/管理相关的区域（或在 stats 面板附近），添加：

```html
<button class="a-btn" onclick="batchMatchBangumi()" id="btnBatchMatch">批量补全 Bangumi 链接</button>
```

- [ ] **Step 2: 添加批量匹配 JS 函数**

在 `anime-app.js` 中添加：

```javascript
async function batchMatchBangumi() {
    const btn = document.getElementById('btnBatchMatch');
    if (!btn) return;
    btn.disabled = true;
    btn.textContent = '匹配中，请稍候...';
    const r = await fetchApi('/api/anime/batch-match-bangumi', { method: 'POST' });
    if (r && r.code === 200) {
        const d = r.data;
        toast(`匹配完成：成功 ${d.matched}，失败 ${d.failed}，共 ${d.total}`, 'success');
        performSearch();
    } else {
        toast(r?.message || '批量匹配失败', 'error');
    }
    btn.disabled = false;
    btn.textContent = '批量补全 Bangumi 链接';
}
```

- [ ] **Step 3: 验证**

Run: `mvn spring-boot:run`，点击按钮验证批量匹配功能。

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/templates/anime.html src/main/resources/static/js/anime-app.js
git commit -m "feat(ui): 添加批量补全 Bangumi 链接入口"
```

---

### Task 6: 整体验证与清理

- [ ] **Step 1: 运行后端测试**

Run: `mvn test -pl .`
Expected: 所有测试通过

- [ ] **Step 2: 浏览器手动验证**

1. 打开应用，点击一个有 bangumiId 的番剧 → 弹窗应显示左侧封面 + 右侧信息 + Bangumi 外链
2. 点击一个没有 bangumiId 的番剧 → 弹窗应显示「匹配 Bangumi 链接」按钮
3. 点击匹配按钮 → 应自动匹配并刷新弹窗
4. 移动端视图 → 弹窗应变为上下布局
5. 点击「批量补全 Bangumi 链接」→ 应逐个匹配并显示结果

- [ ] **Step 3: 最终提交**

```bash
git add -A
git commit -m "feat: 番剧详情弹窗优化完成 — Bangumi 链接修复 + UI 重构"
```
