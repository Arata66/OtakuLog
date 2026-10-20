const test = require('node:test');
const assert = require('node:assert/strict');
const { createAppMenuBridge } = require('../dist/app-menu.js');

function setup() {
  const origin = 'http://127.0.0.1:54321', shown = [];
  const owner = { isDestroyed: () => false, webContents: { mainFrame: { url: origin + '/' } } };
  const event = { sender: owner.webContents, senderFrame: owner.webContents.mainFrame };
  let currentOrigin = origin;
  const show = createAppMenuBridge({ getWindow: () => owner, getOrigin: () => currentOrigin, showMenu: window => shown.push(window) });
  return { origin, owner, event, show, shown, disable: () => { currentOrigin = undefined; } };
}

test('当主窗口已就绪时应该只为该窗口展开应用菜单', () => {
  const a = setup(); assert.equal(a.show(a.event), true); assert.deepEqual(a.shown, [a.owner]);
});
test('当启动页或其他来源请求菜单时应该拒绝', () => {
  for (const url of ['file:///startup.html', 'https://example.invalid/', 'http://127.0.0.1:54322/', 'http://127.0.0.1:54321/api/anime']) {
    const a = setup(); a.owner.webContents.mainFrame.url = url;
    assert.equal(a.show(a.event), false); assert.deepEqual(a.shown, []);
  }
});
test('当子框架或其他窗口请求时应该拒绝打开菜单', () => {
  const a = setup();
  assert.equal(a.show({ ...a.event, sender: {} }), false);
  assert.equal(a.show({ ...a.event, senderFrame: { url: a.origin + '/' } }), false);
  assert.deepEqual(a.shown, []);
});
test('当请求带参数或应用退出时应该拒绝', () => {
  const a = setup(); assert.equal(a.show(a.event, '打开资料目录'), false);
  a.disable(); assert.equal(a.show(a.event), false); assert.deepEqual(a.shown, []);
});
