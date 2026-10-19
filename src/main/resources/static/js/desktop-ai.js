let desktopAiDialog;

async function openDesktopAiSettings() {
    if (!window.otakulogDesktop || desktopAiDialog) return;
    const dialog = document.createElement('dialog');
    dialog.className = 'modal-card desktop-ai-settings';
    dialog.setAttribute('aria-labelledby', 'desktopAiTitle');
    dialog.innerHTML = `<h3 id="desktopAiTitle">AI 服务</h3>
        <p class="memory-note">AI 辅助整理为可选功能，需配置你自己的 API Key。调用可能产生费用，由模型服务商收取；只有你明确发送的文字会交给该服务处理。</p>
        <p class="memory-note">服务商：DeepSeek · 模型：deepseek-flash。<a href="https://platform.deepseek.com/api_keys" target="_blank" rel="noopener noreferrer">管理自己的 API Key</a></p>
        <form id="desktopAiForm" autocomplete="off"><fieldset disabled>
        <label class="desktop-ai-enabled"><input id="desktopAiEnabled" type="checkbox"> 启用 AI 辅助整理</label>
        <p id="desktopAiConfigured" class="memory-note"></p>
        <div class="modal-field"><label for="desktopAiKey">自己的 API Key</label><input id="desktopAiKey" type="password" maxlength="512" autocomplete="new-password" spellcheck="false" placeholder="输入新密钥；留空保留已保存密钥"></div>
        <div class="modal-field"><label for="desktopAiLimit">每日调用次数上限</label><input id="desktopAiLimit" type="number" min="0" max="1000" step="1" value="20" required></div>
        <p class="memory-note">次数按本应用记录，失败也可能计费。更换密钥不会重置次数。保存设置不会调用 AI。</p>
        <div class="modal-actions"><button type="button" class="btn-h" id="desktopAiClear">清除已保存密钥</button><button class="btn-add" type="submit">保存设置</button></div>
        </fieldset></form><p class="memory-note" id="desktopAiStatus" role="status" aria-live="polite">正在读取配置…</p>
        <div class="modal-actions"><button type="button" class="btn-h" id="desktopAiClose">关闭</button></div>`;
    desktopAiDialog = dialog;
    document.body.appendChild(dialog);
    const enabled = dialog.querySelector('#desktopAiEnabled'), key = dialog.querySelector('#desktopAiKey');
    const limit = dialog.querySelector('#desktopAiLimit'), fieldset = dialog.querySelector('fieldset');
    const status = dialog.querySelector('#desktopAiStatus'), clear = dialog.querySelector('#desktopAiClear');
    let initial, saving = false;
    const dirty = () => initial && (key.value !== '' || enabled.checked !== initial.enabled || Number(limit.value) !== initial.dailyLimit);
    const apply = settings => {
        initial = settings;
        enabled.checked = settings.enabled; limit.value = settings.dailyLimit;
        dialog.querySelector('#desktopAiConfigured').textContent = settings.hasKey
            ? '已保存密钥；不会显示或读回完整密钥。' : '尚未配置可用密钥，仍可正常记番和手工整理观点。';
        status.textContent = settings.warning || '';
        clear.disabled = !settings.hasKey && settings.storage === 'ready';
        fieldset.disabled = false;
    };
    const close = () => {
        if (saving || (dirty() && !confirm('AI 设置尚未保存，关闭会放弃本次输入。是否关闭？'))) return;
        key.value = ''; dialog.close(); dialog.remove(); desktopAiDialog = undefined;
    };
    const save = async clearing => {
        if (saving || !initial) return;
        saving = true; fieldset.disabled = true;
        const patch = { enabled: clearing ? false : enabled.checked, dailyLimit: Number(limit.value), keyAction: clearing ? 'clear' : key.value ? 'replace' : 'keep' };
        if (!clearing && key.value) patch.apiKey = key.value;
        // 提交后立即清空输入框，失败也不将密钥写回页面或放入提示。
        key.value = '';
        status.textContent = '正在保存…';
        try {
            const result = await window.otakulogDesktop.updateAiSettings(patch);
            if (result?.ok) {
                apply(result.settings); status.textContent = clearing ? '密钥已清除，AI 已关闭；历史观点和调用次数保留。' : '设置已保存，没有发送 AI 请求。';
                if (typeof invalidateTastePreview === 'function') invalidateTastePreview();
                if (typeof refreshTasteUsage === 'function') refreshTasteUsage();
            } else status.textContent = result?.message || '设置未能保存，请重新打开后核对。';
        } catch { status.textContent = '设置未能保存，请重新打开后核对。'; }
        finally { delete patch.apiKey; fieldset.disabled = false; saving = false; }
    };
    dialog.querySelector('#desktopAiForm').addEventListener('submit', event => { event.preventDefault(); save(false); });
    clear.addEventListener('click', () => save(true));
    dialog.querySelector('#desktopAiClose').addEventListener('click', close);
    // 原生菜单可叠加设置窗口，按键不能继续触发底层作品弹窗的快捷操作。
    dialog.addEventListener('keydown', event => {
        event.stopPropagation();
        if (event.key === 'Escape' && (event.isComposing || event.keyCode === 229)) event.preventDefault();
    });
    dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
    const preventExit = event => { if (saving || dirty()) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', preventExit);
    dialog.addEventListener('close', () => window.removeEventListener('beforeunload', preventExit), { once: true });
    dialog.showModal();
    try {
        const result = await window.otakulogDesktop.getAiSettings();
        if (result?.ok) apply(result.settings);
        else status.textContent = result?.message || 'AI 配置读取失败，请重新打开程序。';
    } catch { status.textContent = 'AI 配置读取失败，请重新打开程序。'; }
}

window.addEventListener('otakulog:open-ai-settings', openDesktopAiSettings);
window.otakulogDesktop?.getAiSettings().then(result => {
    if (result?.ok && result.settings.warning) toast(result.settings.warning, 'error');
}).catch(() => {});
