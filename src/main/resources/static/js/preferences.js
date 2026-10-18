(function () {
    const desktop = document.querySelector('meta[name="otakulog-desktop-managed"]')?.content === 'true';
    const keys = { theme: 'otakulog-theme', accent: 'otakulog-accent', language: 'otakulog-lang' };
    let values = { theme: 'system', accent: 'indigo', language: 'zh' }, pending = 0, warning;
    const failed = '桌面设置无法读取，已使用默认设置。请重新打开程序或检查资料目录中的设置文件。';
    function applyAppearance() {
        const dark = values.theme === 'dark' || (values.theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
        document.documentElement.setAttribute('data-theme', dark ? 'dark' : '');
        document.documentElement.setAttribute('data-accent', values.accent);
    }
    const ready = desktop ? (async () => {
        try {
            const result = await window.otakulogDesktop.getPreferences();
            if (result?.ok !== true) throw new Error();
            values = result.preferences; warning = result.warning;
        } catch { warning = failed; }
        applyAppearance();
    })() : Promise.resolve().then(() => {
        for (const key of Object.keys(keys)) values[key] = localStorage.getItem(keys[key]) || values[key];
        applyAppearance();
    });
    window.addEventListener('beforeunload', event => {
        if (!desktop || !pending) return;
        event.preventDefault(); event.returnValue = '';
    });
    window.otakulogPreferences = Object.freeze({
        ready,
        get: key => values[key],
        showWarning() { if (warning) { toast(warning, 'error'); warning = undefined; } },
        async set(key, value) {
            if (!Object.hasOwn(keys, key)) return;
            values[key] = value;
            if (!desktop) { localStorage.setItem(keys[key], value); return; }
            pending++;
            try {
                const result = await window.otakulogDesktop.updatePreferences({ [key]: value });
                if (result?.ok !== true) toast(result?.message || '设置未能保存，请检查资料目录的写入权限。', 'error');
            } catch { toast('设置未能保存，请重新打开程序后重试。', 'error'); }
            finally { pending--; }
        }
    });
})();
