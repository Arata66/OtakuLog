const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../../main/resources');
function app() {
    const listeners = [], calls = [];
    const context = vm.createContext({
        document: { addEventListener(type, callback, capture = false) { listeners.push({ type, callback, capture }); }, createElement() { return {}; } },
        openAnimeMemory: (...args) => calls.push(args), saveAnimeMemory: (...args) => calls.push(args),
        closeAnnualReport: event => calls.push(event), addToTrackingFromBrowse: (...args) => calls.push(args)
    });
    const file = path.join(assets, 'static/js/ui-events.js');
    if (fs.existsSync(file)) vm.runInContext(fs.readFileSync(file, 'utf8'), context);
    function element(dataset = {}, extra = {}) {
        return { dataset, disabled: false, getAttribute(name) { return name === 'aria-disabled' ? this.ariaDisabled : null; },
            closest(selector) { return selector.includes('.dt-remark') ? this.userContent : selector.includes('data-ui-') ? this : null; }, ...extra };
    }
    function dispatch(type, target, other = {}) {
        const event = { type, target, stopped: false, defaultPrevented: false,
            stopPropagation() { this.stopped = true; }, preventDefault() { this.defaultPrevented = true; }, ...other };
        for (const capture of [true, false]) for (const listener of listeners)
            if (listener.type === type && listener.capture === capture && !event.stopped) listener.callback(event);
        return event;
    }
    return { context, listeners, calls, element, dispatch };
}

test('当动态按钮重复重绘后点击时应该每次仅调用一次合法回调', () => {
    const a = app();
    for (let i = 0; i < 3; i++) a.dispatch('click', a.element({ uiClick: 'openAnimeMemory', uiArgs: '[7]' }));
    assert.deepEqual(a.calls.map(args => [...args]), [[7], [7], [7]]);
});

test('当未知动作或非法参数出现时应该忽略且不影响随后合法操作', () => {
    const a = app();
    for (const name of ['constructor', '__proto__', 'toString', 'missing']) a.dispatch('click', a.element({ uiClick: name, uiArgs: '[]' }));
    a.dispatch('click', a.element({ uiClick: 'openAnimeMemory', uiArgs: 'window.alert(1)' }));
    a.dispatch('click', a.element({ uiClick: 'openAnimeMemory', uiArgs: '{}' }));
    a.dispatch('click', a.element({ uiClick: 'openAnimeMemory', uiArgs: '[8]' }));
    assert.deepEqual(a.calls.map(args => [...args]), [[8]]);
});

test('当用户内容伪造已知命令时应该拒绝执行', () => {
    const a = app();
    a.dispatch('click', a.element({ uiClick: 'openAnimeMemory', uiArgs: '[7]' }, { userContent: {} }));
    assert.deepEqual(a.calls, []);
});

test('当按钮已禁用或声明不可用时应该拒绝触发', () => {
    const a = app();
    a.dispatch('click', a.element({ uiClick: 'openAnimeMemory', uiArgs: '[7]' }, { disabled: true }));
    a.dispatch('click', a.element({ uiClick: 'openAnimeMemory', uiArgs: '[7]' }, { ariaDisabled: 'true' }));
    assert.deepEqual(a.calls, []);
});

test('当提交感想表单时应该取消导航且仅保存一次', () => {
    const a = app(), event = a.dispatch('submit', a.element({ uiSubmit: 'saveAnimeMemory', uiArgs: '[7]' }));
    assert.equal(event.defaultPrevented, true);
    assert.deepEqual(a.calls.map(args => [...args]), [[7]]);
});

test('当点击年报内容和遮罩时应该仅由遮罩关闭', () => {
    const a = app(), overlay = a.element({ uiClick: 'closeAnnualReport', uiBackdrop: 'true' });
    const child = a.element({}, { closest: selector => selector.includes('.dt-remark') ? null : overlay });
    a.dispatch('click', child);
    assert.equal(a.calls.length, 0);
    a.dispatch('click', overlay);
    assert.equal(a.calls.length, 1);
});

test('当嵌套卡片的添加按钮触发时应该在父动作前停止传播且保留名称原文', () => {
    const a = app(), name = `虚构'作品"<&>`;
    const event = a.dispatch('click', a.element({ uiClick: 'addToTrackingFromBrowse', uiArgs: JSON.stringify([5, name, 12, '', '']), uiStop: 'true' }));
    assert.equal(event.stopped, true);
    assert.deepEqual(a.calls.map(args => [...args]), [[5, name, 12, '', '']]);
});

test('当图片加载失败时应该使用纯文本占位且忽略用户内容图片的命令', () => {
    const a = app(), replacements = [];
    const image = a.element({ uiImage: 'placeholder', uiEmptyClass: 'cover-empty', uiEmptyText: '<虚构>' }, { tagName: 'IMG', replaceWith: node => replacements.push(node) });
    a.dispatch('error', image);
    assert.equal(replacements.length, 1);
    assert.equal(replacements[0].className, 'cover-empty');
    assert.equal(replacements[0].textContent, '<虚构>');
    image.userContent = {}; a.dispatch('error', image);
    assert.equal(replacements.length, 1);
});

test('当生成动态命令属性时应该完整转义名称参数且不生成事件属性', () => {
    const a = app();
    assert.equal(typeof a.context.uiAction, 'function');
    const html = a.context.uiAction('openAnimeMemory', ['"<&>']);
    assert.match(html, /data-ui-click="openAnimeMemory"/);
    assert.match(html, /&quot;/); assert.match(html, /&lt;/); assert.match(html, /&amp;/);
    assert.doesNotMatch(html, /\bonclick=/);
});

test('当主页面及动态模板声明交互时应该使用外部事件绑定', () => {
    const files = ['templates/anime.html', ...['anime-app', 'anime-memory', 'bangumi-import', 'daily-watch', 'episode-history', 'recommendation-bases', 'recommendations', 'taste-profile'].map(name => `static/js/${name}.js`)];
    for (const file of files) assert.doesNotMatch(fs.readFileSync(path.join(assets, file), 'utf8'), /\son(?:click|change|submit|input|error)\s*=\s*["']/i, file);
});
