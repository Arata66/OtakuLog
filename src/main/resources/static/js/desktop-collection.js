(function () {
    if (document.querySelector('meta[name="otakulog-desktop-managed"]')?.content !== 'true') return;

    function initDesktopCollection() {
        if (window.otakulogDesktopCollection) return;
        const dialog = document.getElementById('desktopAddDialog');
        const form = document.getElementById('animeForm');
        const addButton = document.getElementById('desktopAddButton');
        if (!dialog || !form || !addButton) return;
        const notifications = document.getElementById('tw');
        const notificationHome = notifications?.parentElement;
        const notificationNext = notifications?.nextSibling;

        // 移动原节点，保留已绑定的监听、输入草稿和 DOM 缓存。
        const move = (node, destination) => { if (node && destination) destination.appendChild(node); };
        move(document.querySelector('.add-form'), document.getElementById('desktopAddContent'));
        move(document.getElementById('tasteProfile'), document.getElementById('tab-insights'));
        const overview = document.querySelector('.overview');
        if (overview) document.getElementById('tab-charts').prepend(overview);
        const appearance = document.getElementById('desktopAppearanceControls');
        move(document.getElementById('accentBtn')?.parentElement, appearance);
        move(document.getElementById('themeToggle'), appearance);
        move(document.getElementById('langToggle'), appearance);
        const data = document.getElementById('desktopDataControls');
        move(document.getElementById('syncBtn')?.parentElement, data);
        move(document.querySelector('.header-right [data-ui-click="exportData"]'), data);
        move(document.querySelector('.header-right .file-btn'), data);
        move(document.getElementById('desktopSettingsBtn'), document.getElementById('desktopAiControls'));
        move(document.getElementById('desktopLocalNote'), document.getElementById('desktopBackupNotes'));

        let composing = false, lastFocus = addButton;
        const snapshot = () => JSON.stringify(Array.from(form.elements)
            .filter(field => field.tagName !== 'BUTTON' && field.type !== 'submit' && field.type !== 'file')
            .map(field => [field.id, field.type === 'checkbox' || field.type === 'radio' ? field.checked : field.value]));
        let baseline = snapshot();
        const dirty = () => snapshot() !== baseline;
        const saving = () => typeof pendingDesktopWrites !== 'undefined' && pendingDesktopWrites > 0;
        const clearDraft = () => {
            form.reset();
            document.getElementById('bangumiResults')?.classList.remove('active');
            baseline = snapshot();
        };
        const closeAdd = () => {
            if (composing || saving()) return;
            if (dirty()) {
                if (!confirm('添加番剧尚有未保存内容，关闭会放弃本次输入。是否关闭？')) return;
                clearDraft();
            }
            dialog.close();
        };
        function openAdd(replaceDraft = false) {
            if (saving()) return false;
            if (replaceDraft && dirty()) {
                if (!confirm('添加番剧尚有未保存内容，是否放弃草稿并填入所选作品？')) return false;
                clearDraft();
            }
            if (typeof switchTab === 'function') switchTab('list');
            if (!dialog.open) {
                lastFocus = document.activeElement;
                move(notifications, dialog);
                dialog.showModal();
            }
            document.getElementById('animeName').focus();
            return true;
        }
        function afterAnimeSaved() {
            // 仅由原新增流程在请求成功且表单复位后调用。
            baseline = snapshot();
            if (dialog.open) dialog.close();
        }
        function syncNavigation(tab) {
            const pages = {
                list: ['我的番剧', '继续观看，或者记下今天的感想。'],
                insights: ['观影总结', '从写下的感想里，慢慢认识自己的喜好。'],
                memories: ['感想回顾', '翻阅不同作品里，曾经写下的感受。'],
                charts: ['数据图表', '回看追番足迹，也了解统计的依据。'],
                calendar: ['番剧日历', '看看正在放送的故事，安排自己的观看节奏。'],
                timeline: ['时间线', '沿着时间，回望看过的作品。'],
                settings: ['设置', '把外观和资料，整理成习惯的样子。']
            };
            const current = pages[tab] ? tab : 'list';
            document.getElementById('desktopCollectionNav').querySelectorAll('[data-desktop-tab]').forEach(button => {
                const active = button.dataset.desktopTab === current;
                button.classList.toggle('active', active);
                if (active) button.setAttribute('aria-current', 'page');
                else button.removeAttribute('aria-current');
            });
            document.getElementById('desktopPageTitle').textContent = pages[current][0];
            document.getElementById('desktopPageDescription').textContent = pages[current][1];
        }

        addButton.addEventListener('click', () => openAdd());
        document.getElementById('desktopAddClose').addEventListener('click', closeAdd);
        dialog.addEventListener('compositionstart', () => { composing = true; });
        dialog.addEventListener('compositionend', () => { composing = false; });
        dialog.addEventListener('keydown', event => {
            event.stopPropagation();
            if (event.key === 'Escape' && (composing || event.isComposing || event.keyCode === 229)) event.preventDefault();
        });
        dialog.addEventListener('cancel', event => { event.preventDefault(); closeAdd(); });
        dialog.addEventListener('close', () => {
            composing = false;
            if (notifications && notificationHome) {
                if (notificationNext?.parentElement === notificationHome) notificationHome.insertBefore(notifications, notificationNext);
                else notificationHome.appendChild(notifications);
            }
            if (lastFocus && document.contains(lastFocus) && lastFocus.getClientRects().length) lastFocus.focus();
            else addButton.focus();
        });
        window.addEventListener('beforeunload', event => {
            if (saving() || dirty()) { event.preventDefault(); event.returnValue = ''; }
        });

        window.otakulogDesktopCollection = Object.freeze({ openAdd, afterAnimeSaved, syncNavigation });
        syncNavigation(document.querySelector('.tab-content.active')?.id.replace('tab-', '') || 'list');
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initDesktopCollection, { once: true });
    else initDesktopCollection();
})();
