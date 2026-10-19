const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const scripts = path.resolve(__dirname, '../../main/resources/static/js');

function element(id = '') {
    const listeners = new Map(), attributes = new Map(), classes = new Set();
    return {
        id, children: [], dataset: {}, value: '', checked: false, type: 'text', open: false,
        classList: { add: value => classes.add(value), remove: value => classes.delete(value), contains: value => classes.has(value),
            toggle(value, active) { if (active) classes.add(value); else classes.delete(value); } },
        setAttribute: (name, value) => attributes.set(name, value), getAttribute: name => attributes.get(name),
        removeAttribute: name => attributes.delete(name),
        addEventListener(name, callback) { const list = listeners.get(name) || []; list.push(callback); listeners.set(name, list); },
        dispatch(name, options = {}) {
            const event = { type: name, defaultPrevented: false, stopped: false,
                preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; }, ...options };
            for (const callback of listeners.get(name) || []) callback(event);
            return event;
        },
        appendChild(child) {
            if (child.parentElement) child.parentElement.children = child.parentElement.children.filter(node => node !== child);
            this.children.push(child); child.parentElement = this; return child;
        },
        append(...children) { for (const child of children) this.appendChild(child); },
        prepend(child) { this.appendChild(child); this.children = [child, ...this.children.filter(node => node !== child)]; },
        insertBefore(child, next) { this.appendChild(child); this.children = this.children.filter(node => node !== child); this.children.splice(this.children.indexOf(next), 0, child); },
        remove() { if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(node => node !== this); this.parentElement = null; },
        get nextSibling() { return this.parentElement?.children[this.parentElement.children.indexOf(this) + 1]; },
        focus() { this.focused = true; }, showModal() { this.open = true; }, close() { this.open = false; this.dispatch('close'); },
        getClientRects() { return this.visible === false ? [] : [{}]; },
        querySelector() { return null; }, querySelectorAll() { return []; }
    };
}

function app({ managed = true, readyState = 'complete' } = {}) {
    const ids = new Map(), selectors = new Map(), confirms = [], tabs = [];
    for (const id of ['tw', 'desktopCollectionNav', 'desktopAddButton', 'desktopAddDialog', 'desktopAddClose', 'desktopAddContent',
        'animeForm', 'animeName', 'bangumiResults', 'tasteProfile', 'tab-insights', 'tab-charts',
        'desktopAppearanceControls', 'desktopDataControls', 'desktopAiControls', 'desktopPageTitle', 'desktopPageDescription']) ids.set(id, element(id));
    const form = ids.get('animeForm'), name = ids.get('animeName');
    form.elements = [name]; form.reset = () => { name.value = ''; form.resets = (form.resets || 0) + 1; };
    const add = element(), overview = element(), root = element(), body = element();
    add.append(form, ids.get('bangumiResults')); root.append(add, overview, ids.get('tasteProfile'));
    body.append(ids.get('tw'), root);
    selectors.set('.add-form', add); selectors.set('.overview', overview);
    const navButtons = ['list', 'insights', 'charts', 'calendar', 'timeline', 'settings'].map(tab => {
        const button = element(); button.dataset.desktopTab = tab; return button;
    });
    ids.get('desktopCollectionNav').querySelectorAll = () => navButtons;
    const doc = element();
    Object.assign(doc, { readyState, body, activeElement: ids.get('desktopAddButton'),
        getElementById: id => ids.get(id),
        querySelector: selector => selector.includes('otakulog-desktop-managed') ? { content: String(managed) }
            : selector === '.tab-content.active' ? { id: 'tab-list' } : selectors.get(selector),
        querySelectorAll: selector => selector === '[data-desktop-tab]' ? navButtons : [],
        contains: () => true
    });
    const win = element();
    const context = vm.createContext({ document: doc, window: win,
        confirm(message) { confirms.push(message); return context.confirmResult; },
        confirmResult: false, pendingDesktopWrites: 0, switchTab: tab => tabs.push(tab),
        setTimeout: callback => callback(), requestAnimationFrame: callback => callback()
    });
    const file = path.join(scripts, 'desktop-collection.js');
    if (fs.existsSync(file)) vm.runInContext(fs.readFileSync(file, 'utf8'), context);
    return { context, doc, win, ids, form, name, add, overview, navButtons, confirms, tabs, api: win.otakulogDesktopCollection };
}

test('当网站模式加载桌面控制器时应该保留原表单位置且不注册桌面对象', () => {
    const a = app({ managed: false });
    assert.equal(a.api, undefined); assert.equal(a.form.parentElement, a.add);
});

test('当桌面页面初始化时应该移动原表单与总结统计且只暴露三个受限动作', () => {
    const a = app();
    assert.ok(a.api, '应初始化桌面收藏界面');
    assert.ok(Object.isFrozen(a.api));
    assert.deepEqual(Object.keys(a.api).sort(), ['afterAnimeSaved', 'openAdd', 'syncNavigation']);
    assert.equal(a.add.parentElement, a.ids.get('desktopAddContent'));
    assert.equal(a.form.parentElement, a.add);
    assert.equal(a.ids.get('tasteProfile').parentElement, a.ids.get('tab-insights'));
    assert.equal(a.overview.parentElement, a.ids.get('tab-charts'));
});

test('当页面仍在解析时应该等原节点就绪后只初始化一次', () => {
    const a = app({ readyState: 'loading' });
    assert.equal(a.api, undefined);
    a.doc.dispatch('DOMContentLoaded'); const first = a.win.otakulogDesktopCollection;
    assert.ok(first); a.doc.dispatch('DOMContentLoaded'); assert.equal(a.win.otakulogDesktopCollection, first);
});

test('当侧栏切换页面时应该同步唯一的活动状态和标题', () => {
    const a = app(); assert.ok(a.api);
    a.api.syncNavigation('settings');
    assert.equal(a.navButtons.find(button => button.dataset.desktopTab === 'settings').getAttribute('aria-current'), 'page');
    assert.equal(a.navButtons.filter(button => button.getAttribute('aria-current') === 'page').length, 1);
    assert.equal(a.ids.get('desktopPageTitle').textContent, '设置');
});

test('当取消有草稿的添加窗口时应该先确认且拒绝后保留输入', () => {
    const a = app(); assert.ok(a.api); a.api.openAdd(); a.name.value = '我的草稿';
    const event = a.ids.get('desktopAddDialog').dispatch('cancel');
    assert.equal(event.defaultPrevented, true); assert.equal(a.confirms.length, 1);
    assert.equal(a.ids.get('desktopAddDialog').open, true); assert.equal(a.name.value, '我的草稿');
    a.context.confirmResult = true; a.ids.get('desktopAddClose').dispatch('click');
    assert.equal(a.ids.get('desktopAddDialog').open, false); assert.equal(a.name.value, '');
    assert.equal(a.ids.get('desktopAddButton').focused, true);
});

test('当中文输入使用Escape时应该保留窗口且所有按键不传给底层快捷键', () => {
    const a = app(); assert.ok(a.api); a.api.openAdd();
    const dialog = a.ids.get('desktopAddDialog');
    const composing = dialog.dispatch('keydown', { key: 'Escape', isComposing: true });
    assert.equal(composing.defaultPrevented, true); assert.equal(composing.stopped, true);
    assert.equal(dialog.open, true); assert.equal(a.confirms.length, 0);
    assert.equal(dialog.dispatch('keydown', { key: '2' }).stopped, true);
});

test('当添加请求仍在途中时应该阻止关闭并保留草稿', () => {
    const a = app(); assert.ok(a.api); a.api.openAdd(); a.name.value = '保存中';
    a.context.pendingDesktopWrites = 1; a.context.confirmResult = true;
    a.ids.get('desktopAddClose').dispatch('click');
    assert.equal(a.ids.get('desktopAddDialog').open, true); assert.equal(a.name.value, '保存中');
    assert.equal(a.confirms.length, 0);
});

test('当原新增流程完成复位后应该关闭窗口且不再提示丢弃草稿', () => {
    const a = app(); assert.ok(a.api); a.api.openAdd(); a.name.value = '已保存';
    a.form.reset(); a.api.afterAnimeSaved();
    assert.equal(a.ids.get('desktopAddDialog').open, false); assert.equal(a.confirms.length, 0);
    assert.equal(a.win.dispatch('beforeunload').defaultPrevented, false);
});

test('当已有草稿遇到搜索填充时应该确认替换且拒绝后不能覆盖', () => {
    const a = app(); assert.ok(a.api); a.api.openAdd(); a.name.value = '手工草稿';
    assert.equal(a.api.openAdd(true), false); assert.equal(a.name.value, '手工草稿');
    a.context.confirmResult = true;
    assert.equal(a.api.openAdd(true), true); assert.equal(a.name.value, '');
});

test('当添加草稿未保存时应该参与桌面退出保护', () => {
    const a = app(); assert.ok(a.api); a.api.openAdd(); a.name.value = '尚未保存';
    assert.equal(a.win.dispatch('beforeunload').defaultPrevented, true);
});

test('当从设置打开添加窗口时应该回到收藏且关闭后焦点落在可见入口', () => {
    const a = app(); assert.ok(a.api);
    const settingButton = element(); settingButton.visible = false; a.doc.activeElement = settingButton;
    a.api.openAdd(); assert.deepEqual(a.tabs, ['list']);
    a.api.afterAnimeSaved(); assert.equal(a.ids.get('desktopAddButton').focused, true);
    assert.equal(settingButton.focused, undefined);
});

test('当添加窗口打开或关闭时应该让同一个提示区域随当前可见窗口移动再返回原位', () => {
    const a = app(); assert.ok(a.api); const notifications = a.ids.get('tw');
    assert.equal(a.doc.body.children[0], notifications);
    a.api.openAdd(); assert.equal(notifications.parentElement, a.ids.get('desktopAddDialog'));
    a.api.afterAnimeSaved(); assert.equal(a.doc.body.children[0], notifications);
    assert.equal(a.ids.get('tw'), notifications);
});

function initialView(managed, saved) {
    const context = vm.createContext({
        localStorage: { getItem: () => saved }, window: {}, Chart: { defaults: { font: {} } },
        document: { readyState: 'loading', addEventListener() {}, querySelector: () => ({ content: String(managed) }) }
    });
    vm.runInContext(fs.readFileSync(path.join(scripts, 'anime-app.js'), 'utf8'), context);
    return vm.runInContext('viewMode', context);
}

test('当没有视图偏好时应该桌面默认画廊且网站默认表格', () => {
    assert.equal(initialView(true, null), 'gallery'); assert.equal(initialView(false, null), 'table');
});

test('当已有视图偏好时应该保留用户选择', () => {
    for (const view of ['table', 'detail', 'gallery']) assert.equal(initialView(true, view), view);
});

function addApp() {
    const ids = new Map();
    for (const id of ['animeForm', 'animeBroadcastDay', 'animeBangumiId', 'animeStatus', 'animeName', 'totalEpisodes',
        'animeSeason', 'animeScore', 'animeRemark', 'animeCover', 'animeStartDate', 'animeEndDate', 'animeTags', 'animeWatchSeason', 'animeLegacy']) ids.set(id, element(id));
    const order = [], requests = [];
    ids.get('animeName').value = '真实地址样例'; ids.get('animeCover').value = 'https://images.example.test/actual-cover.png';
    ids.get('totalEpisodes').value = '12'; ids.get('animeScore').value = '8'; ids.get('animeSeason').value = '2026秋';
    ids.get('animeForm').reset = () => { order.push('reset'); ids.get('animeName').value = ''; };
    const context = vm.createContext({ window: { otakulogDesktopCollection: { afterAnimeSaved: () => order.push('close') } },
        document: { readyState: 'loading', addEventListener() {}, getElementById: id => ids.get(id), querySelector: () => ({ content: 'true' }) },
        localStorage: { getItem: () => null }, Chart: { defaults: { font: {} } }
    });
    vm.runInContext(fs.readFileSync(path.join(scripts, 'anime-app.js'), 'utf8'), context);
    Object.assign(context, { toast() {}, closeBangumi() {}, performSearch() {}, updateStats() {},
        esc: value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;'),
        fetchApi: async (url, options) => { requests.push({ url, options }); return { code: 200 }; } });
    return { context, ids, order, requests, submit: () => context.addAnime({ preventDefault() {} }) };
}

test('当新增成功时应该先复位原表单再关闭桌面窗口且封面地址原样提交', async () => {
    const a = addApp(); await a.submit();
    assert.deepEqual(a.order, ['reset', 'close']);
    assert.equal(JSON.parse(a.requests[0].options.body).coverUrl, 'https://images.example.test/actual-cover.png');
});

test('当新增返回失败时应该保留表单与窗口', async () => {
    const a = addApp(); a.context.fetchApi = async () => ({ code: 400, message: '无法添加' });
    await a.submit(); assert.deepEqual(a.order, []); assert.equal(a.ids.get('animeName').value, '真实地址样例');
});

test('当新增正在等待服务响应时应该冻结表单并阻止重复提交且失败后可继续编辑', async () => {
    const a = addApp(); let finish, count = 0;
    a.context.fetchApi = () => { count++; return new Promise(resolve => { finish = resolve; }); };
    const saving = a.submit();
    assert.equal(a.ids.get('animeForm').inert, true);
    await a.submit(); assert.equal(count, 1);
    finish({ code: 400 }); await saving;
    assert.equal(a.ids.get('animeForm').inert, false); assert.deepEqual(a.order, []);
});

test('当桌面作品缺少封面或加载失败时应该明确占位且不补写其他图片地址', () => {
    const a = addApp();
    assert.match(a.context.coverHtml({ name: '没有封面的作品', coverUrl: null }, 'g-cover', 'g-cover-empty'), /暂无封面/);
    const image = a.context.coverHtml({ name: '原始封面', coverUrl: 'https://images.example.test/original.png' }, 'g-cover', 'g-cover-empty');
    assert.match(image, /data-src="https:\/\/images\.example\.test\/original\.png"/);
    assert.match(image, /封面暂不可用/);
    assert.equal(a.context.coverFallbackText('详情作品', false), '暂无封面');
    assert.equal(a.context.coverFallbackText('详情作品', true), '封面暂不可用');
});

test('当网站表格缺图或图片错误时应该分别保留原无图文字和作品首字', () => {
    const a = addApp(); a.context.document.querySelector = () => ({ content: 'false' });
    assert.match(a.context.coverHtml({ name: '原网站样例' }, 'cover-img', 'cover-empty', 'N/A'), />N\/A</);
    assert.match(a.context.coverHtml({ name: '原网站样例', coverUrl: 'https://images.example.test/original.png' }, 'cover-img', 'cover-empty', 'N/A'), /data-ui-empty-text="原"/);
});

function traceApp({ desktop = true, open = true } = {}) {
    const a = addApp(), body = element(), dialog = element('desktopAddDialog'), trigger = element('traceTrigger');
    dialog.open = open; a.ids.set('desktopAddDialog', dialog); body.append(dialog);
    const firstButton = element('traceTracking');
    const find = (node, id) => node.id === id ? node : node.children.map(child => find(child, id)).find(Boolean);
    Object.assign(a.context.document, { body, activeElement: trigger, contains: () => true,
        getElementById: id => a.ids.get(id) || find(body, id),
        createElement() {
            const node = element();
            node.querySelectorAll = () => node.innerHTML?.includes('traceTracking') || node.children.some(child => child.innerHTML?.includes('traceTracking')) ? [firstButton] : [];
            node.querySelector = () => node.querySelectorAll()[0];
            return node;
        }
    });
    if (!desktop) delete a.context.window.otakulogDesktopCollection;
    a.context.uiAction = (name, args) => `data-ui-click="${name}" data-ui-args='${JSON.stringify(args)}'`;
    return { ...a, body, dialog, trigger, firstButton,
        show() { a.context.showTraceMoeResults({ allResults: [{ animeName: '虚构图片识别作品', episode: 1, confidence: 99 }] }); return a.context.document.getElementById('traceMoeModal'); } };
}

test('当桌面添加窗口内识图成功时应该在当前窗口内展示可交互结果并聚焦操作', () => {
    const a = traceApp(), overlay = a.show();
    assert.equal(overlay.parentElement, a.dialog);
    assert.match(overlay.children[0].innerHTML, /虚构图片识别作品/);
    assert.equal(a.firstButton.focused, true);
});

test('当网站或未打开桌面添加窗口时识图应该沿用页面结果层', () => {
    for (const options of [{ desktop: false }, { open: false }]) {
        const a = traceApp(options); assert.equal(a.show().parentElement, a.body);
    }
});

test('当按Escape关闭识图结果时应该取消原生默认关闭且保留外层草稿', () => {
    const a = traceApp(), overlay = a.show(), draft = a.ids.get('animeName').value;
    const event = overlay.dispatch('keydown', { key: 'Escape' });
    assert.equal(event.defaultPrevented, true); assert.equal(event.stopped, true);
    assert.equal(a.context.document.getElementById('traceMoeModal'), undefined);
    assert.equal(a.dialog.open, true); assert.equal(a.ids.get('animeName').value, draft);
    assert.equal(a.trigger.focused, true);
});

test('当识图结果内中文输入或按Tab时应该保留窗口并约束焦点', () => {
    const a = traceApp(), overlay = a.show();
    overlay.dispatch('keydown', { key: 'Escape', isComposing: true });
    assert.equal(a.context.document.getElementById('traceMoeModal'), overlay);
    a.context.document.activeElement = a.firstButton;
    assert.equal(overlay.dispatch('keydown', { key: 'Tab' }).defaultPrevented, true);
    assert.equal(a.firstButton.focused, true);
});
