const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/anime-app.js'), 'utf8');
const start = source.indexOf('async function importFromBangumi()');
const end = source.indexOf('function closeDetailModal(', start);
assert.ok(start >= 0 && end > start, '收藏导入函数的测试边界应存在');
const body = source.slice(start, end);

test('当选择Bangumi导入时应该委托预览面板而不直接发请求', async () => {
    let opened = 0;
    const context = { openBangumiImport: () => opened++ };
    vm.createContext(context); vm.runInContext(body, context);
    await context.importFromBangumi(); assert.equal(opened, 1);
});