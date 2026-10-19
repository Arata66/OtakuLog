// 命令只来自固定映射，数据参数不参与函数查找或代码执行。
function uiAction(name, args = [], type = 'click') {
    const escapeAttribute = value => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    return 'data-ui-' + type + '="' + escapeAttribute(name) + '" data-ui-args="' + escapeAttribute(JSON.stringify(args)) + '"';
}

const uiCallbacks = new Map([
    ['addAnime', (_element, event) => addAnime(event)],
    ['addToTrackingFromBrowse', (_element, _event, args) => addToTrackingFromBrowse(...args)],
    ['batchDelete', (_element, _event, args) => batchDelete(...args)],
    ['batchMatchBangumi', (_element, _event, args) => batchMatchBangumi(...args)],
    ['batchStatus', element => { batchChangeStatus(element.value); element.value = ''; }],
    ['cancelAnimeMemoryEdit', (_element, _event, args) => cancelAnimeMemoryEdit(...args)],
    ['cancelTastePreview', (_element, _event, args) => cancelTastePreview(...args)],
    ['changeEpisodePage', (_element, _event, args) => changeEpisodePage(...args)],
    ['changeRecommendationMode', (_element, _event, args) => changeRecommendationMode(...args)],
    ['changeStatus', (_element, _event, args) => changeStatus(...args)],
    ['chooseTraceImage', () => document.getElementById('traceMoeInput').click()],
    ['clearSelection', (_element, _event, args) => clearSelection(...args)],
    ['closeAnnualReport', (element, event) => { if (element.dataset.uiBackdrop !== 'true' || event.target === element) closeAnnualReport(); }],
    ['closeBangumi', (_element, _event, args) => closeBangumi(...args)],
    ['closeBangumiImport', (_element, _event, args) => closeBangumiImport(...args)],
    ['closeDetailModal', (_element, _event, args) => closeDetailModal(...args)],
    ['closeEditModal', (_element, _event, args) => closeEditModal(...args)],
    ['closeGroupPanel', (_element, _event, args) => closeGroupPanel(...args)],
    ['closeRecommendationModal', (_element, _event, args) => closeRecommendationModal(...args)],
    ['closeTasteMemory', (_element, _event, args) => closeTasteMemory(...args)],
    ['confirmBangumiImport', (_element, _event, args) => confirmBangumiImport(...args)],
    ['copyBangumiComment', (_element, _event, args) => copyBangumiComment(...args)],
    ['createGroup', (_element, _event, args) => createGroup(...args)],
    ['deleteAnime', (_element, _event, args) => deleteAnime(...args)],
    ['deleteAnimeMemory', (_element, _event, args) => deleteAnimeMemory(...args)],
    ['deleteGroup', (_element, _event, args) => deleteGroup(...args)],
    ['deleteTasteInsight', (_element, _event, args) => deleteTasteInsight(...args)],
    ['disableRecommendationBasis', (_element, _event, args) => disableRecommendationBasis(...args)],
    ['editAnimeMemory', (_element, _event, args) => editAnimeMemory(...args)],
    ['editDetail', (_element, _event, args) => { if (closeDetailModal()) openEditModal(args[0]); }],
    ['editTasteInsight', (_element, _event, args) => editTasteInsight(...args)],
    ['exportData', (_element, _event, args) => exportData(...args)],
    ['feedbackRecommendation', (_element, _event, args) => feedbackRecommendation(...args)],
    ['focusAnimeMemories', (_element, _event, args) => focusAnimeMemories(...args)],
    ['generateSummaryCard', (_element, _event, args) => generateSummaryCard(...args)],
    ['goToAddTracking', (_element, _event, args) => goToAddTracking(...args)],
    ['importData', (_element, event) => importData(event)],
    ['importFromBangumi', (_element, _event, args) => importFromBangumi(...args)],
    ['invalidateRecommendationChoice', (_element, _event, args) => invalidateRecommendationChoice(...args)],
    ['invalidateTastePreview', (_element, _event, args) => invalidateTastePreview(...args)],
    ['linkInsightBasis', element => openRecommendationBases(element.dataset.insight)],
    ['loadAnimeMemories', (_element, _event, args) => loadAnimeMemories(...args)],
    ['loadDailyWatch', (_element, _event, args) => loadDailyWatch(...args)],
    ['loadEpisodeHistory', (_element, _event, args) => loadEpisodeHistory(...args)],
    ['loadRankings', (_element, _event, args) => loadRankings(...args)],
    ['loadRecommendations', (_element, _event, args) => loadRecommendations(...args)],
    ['loadSeasonAnime', (_element, _event, args) => loadSeasonAnime(...args)],
    ['matchBangumiFor', (element, _event, args) => matchBangumiFor(args[0], element)],
    ['mobileTab', (_element, _event, args) => { switchTab(args[0]); updateMobileNav(args[1]); }],
    ['newTasteAiRequest', (_element, _event, args) => newTasteAiRequest(...args)],
    ['nextEpisode', (_element, _event, args) => nextEpisode(...args)],
    ['openAnimeMemory', (_element, _event, args) => openAnimeMemory(...args)],
    ['openBangumiSubject', (_element, _event, args) => { const id = Number(args[0]); if (Number.isSafeInteger(id) && id > 0) window.open('https://bgm.tv/subject/' + id, '_blank'); }],
    ['openDailyAnime', (_element, _event, args) => openDailyAnime(...args)],
    ['openDesktopAiSettings', () => { if (typeof openDesktopAiSettings === 'function') openDesktopAiSettings(); }],
    ['openDetailModal', (_element, _event, args) => openDetailModal(...args)],
    ['openEditModal', (_element, _event, args) => openEditModal(...args)],
    ['openRecommendationBases', (_element, _event, args) => openRecommendationBases(...args)],
    ['openRecommendationFeedback', (_element, _event, args) => openRecommendationFeedback(...args)],
    ['openRecommendationSettings', (_element, _event, args) => openRecommendationSettings(...args)],
    ['openTasteEvidence', (_element, _event, args) => openTasteEvidence(...args)],
    ['openTasteMemory', (_element, _event, args) => openTasteMemory(...args)],
    ['prevEpisode', (_element, _event, args) => prevEpisode(...args)],
    ['previewRecommendationBasis', (_element, _event, args) => previewRecommendationBasis(...args)],
    ['previewTasteAi', (_element, _event, args) => previewTasteAi(...args)],
    ['readBangumiImport', (_element, _event, args) => readBangumiImport(...args)],
    ['readPreviousTasteCall', (_element, _event, args) => readPreviousTasteCall(...args)],
    ['readRecommendationBases', (_element, _event, args) => readRecommendationBases(...args)],
    ['readRecommendationSettings', (_element, _event, args) => readRecommendationSettings(...args)],
    ['recommendationBasisFormChanged', (_element, _event, args) => recommendationBasisFormChanged(...args)],
    ['recommendationBasisKindChanged', (_element, _event, args) => recommendationBasisKindChanged(...args)],
    ['recommendationFeedbackPage', (_element, _event, args) => recommendationFeedbackPage(...args)],
    ['reconcileTasteAi', (_element, _event, args) => reconcileTasteAi(...args)],
    ['refreshTasteProfile', (_element, _event, args) => refreshTasteProfile(...args)],
    ['refreshTasteSummary', () => { refreshTasteProfile(); refreshTasteUsage(); }],
    ['removeBangumiCollection', (_element, _event, args) => removeBangumiCollection(...args)],
    ['removeOverlay', element => element.closest('.detail-overlay').remove()],
    ['removeOverlayBackdrop', ((element, event) => { if (event.target === element) element.remove(); })],
    ['removeRecommendationFeedback', (_element, _event, args) => removeRecommendationFeedback(...args)],
    ['renderBangumiImportPreview', (_element, _event, args) => renderBangumiImportPreview(...args)],
    ['resetSearch', (_element, _event, args) => resetSearch(...args)],
    ['resetTasteForm', (_element, _event, args) => resetTasteForm(...args)],
    ['retryRecommendationFeedback', (_element, _event, args) => retryRecommendationFeedback(...args)],
    ['retryUnknownTasteAi', (_element, _event, args) => retryUnknownTasteAi(...args)],
    ['saveAnimeMemory', (_element, _event, args) => saveAnimeMemory(...args)],
    ['saveEditModal', (_element, _event, args) => saveEditModal(...args)],
    ['saveEpisodeDate', (_element, _event, args) => saveEpisodeDate(...args)],
    ['saveRecommendationBasis', (_element, _event, args) => saveRecommendationBasis(...args)],
    ['saveRecommendationSettings', (_element, _event, args) => saveRecommendationSettings(...args)],
    ['saveTasteInsight', (_element, _event, args) => saveTasteInsight(...args)],
    ['scrollToAdd', () => document.querySelector('.add-form').scrollIntoView({ behavior: 'smooth' })],
    ['searchBangumi', (_element, _event, args) => searchBangumi(...args)],
    ['searchTraceMoe', element => searchTraceMoe(element)],
    ['selectRecommendation', (_element, _event, args) => selectRecommendation(...args)],
    ['selectRecommendationBasisSource', (_element, _event, args) => selectRecommendationBasisSource(...args)],
    ['sendTasteAi', (_element, _event, args) => sendTasteAi(...args)],
    ['setAccent', (_element, _event, args) => setAccent(...args)],
    ['setTasteInsightStatus', (_element, _event, args) => setTasteInsightStatus(...args)],
    ['shareAnimeCard', (_element, _event, args) => shareAnimeCard(...args)],
    ['showAddToGroup', (_element, _event, args) => showAddToGroup(...args)],
    ['showAnnualReport', (_element, _event, args) => showAnnualReport(...args)],
    ['showBangumiCollections', (_element, _event, args) => showBangumiCollections(...args)],
    ['switchCalMode', (_element, _event, args) => switchCalMode(...args)],
    ['switchCalView', (_element, _event, args) => switchCalView(...args)],
    ['switchTab', (_element, _event, args) => switchTab(...args)],
    ['switchTimelineMode', (_element, _event, args) => switchTimelineMode(...args)],
    ['syncPull', (_element, _event, args) => syncPull(...args)],
    ['syncPush', (_element, _event, args) => syncPush(...args)],
    ['syncStatus', (_element, _event, args) => syncStatus(...args)],
    ['toggleAccentPicker', (_element, _event, args) => toggleAccentPicker(...args)],
    ['toggleAnimeGroup', (element, _event, args) => toggleAnimeGroup(args[0], args[1], element.checked)],
    ['toggleGroupPanel', (_element, _event, args) => toggleGroupPanel(...args)],
    ['toggleLanguage', () => i18n.setLang(i18n.lang === 'zh' ? 'en' : 'zh')],
    ['toggleNextSection', element => element.nextElementSibling.classList.toggle('is-hidden')],
    ['toggleRecommendationBasis', element => toggleRecommendationBasis(element.dataset.key, element.checked)],
    ['toggleRowSelect', (element, _event, args) => toggleRowSelect(args[0], element.checked)],
    ['toggleSelectAll', element => toggleSelectAll(element)],
    ['toggleSyncMenu', (_element, _event, args) => toggleSyncMenu(...args)],
    ['toggleTheme', (_element, _event, args) => toggleTheme(...args)],
    ['toggleView', (_element, _event, args) => toggleView(...args)],
    ['traceTracking', (_element, _event, args) => { closeTraceMoeModal(); goToAddTracking(args[0]); }],
    ['trackNamedAnime', element => goToAddTracking(element.dataset.name)],
    ['useRecommendationSeed', (_element, _event, args) => useRecommendationSeed(...args)],
    ['viewGroup', (_element, _event, args) => viewGroup(...args)],
    ['writeAnimeReflection', (_element, _event, args) => writeAnimeReflection(...args)]
]);

function uiEventTarget(event) {
    const target = event.target?.closest ? event.target : event.target?.parentElement;
    // 用户原文即使保留或伪造了应用属性，也不能获得按钮权限。
    if (!target || target.closest('.dt-remark, .detail-remark')) return null;
    const element = target.closest('[data-ui-' + event.type + ']');
    if (!element || element.disabled || element.getAttribute('aria-disabled') === 'true') return null;
    return element;
}

function dispatchUiEvent(event, capture = false) {
    const element = uiEventTarget(event);
    if (!element || (element.dataset.uiStop === 'true') !== capture) return;
    const name = element.dataset['ui' + event.type[0].toUpperCase() + event.type.slice(1)];
    const callback = uiCallbacks.get(name);
    if (!callback) return;
    let args;
    try { args = JSON.parse(element.dataset.uiArgs || '[]'); } catch { return; }
    if (!Array.isArray(args)) return;
    if (event.type === 'submit') event.preventDefault();
    // 嵌套添加按钮必须在程序式父卡片 onclick 之前停止传播。
    if (capture) event.stopPropagation();
    callback(element, event, args);
}

for (const type of ['click', 'change', 'input', 'submit']) {
    document.addEventListener(type, event => dispatchUiEvent(event));
}
document.addEventListener('click', event => dispatchUiEvent(event, true), true);

// 图片 error 不冒泡，用捕获监听覆盖后来重绘的图片。
document.addEventListener('error', event => {
    const image = event.target;
    if (image?.tagName !== 'IMG' || image.closest('.dt-remark, .detail-remark')) return;
    if (image.dataset.uiImage === 'hide') image.hidden = true;
    else if (image.dataset.uiImage === 'hidden-class') image.classList.add('is-hidden');
    else if (image.dataset.uiImage === 'placeholder') {
        const placeholder = document.createElement('div');
        placeholder.className = image.dataset.uiEmptyClass || '';
        placeholder.textContent = image.dataset.uiEmptyText || '';
        image.replaceWith(placeholder);
    }
}, true);
