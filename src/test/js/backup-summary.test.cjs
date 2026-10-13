const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/anime-app.js'), 'utf8');
const start = source.indexOf('function confirmBackupImport(');
const end = source.indexOf('async function importBackupJson(', start);
assert.ok(start >= 0 && end > start);
test('当备份含外部收藏与导入历史时应该在确认前说明数量', () => {
    let message; const context = { confirm: text => { message = text; return true; }, alert() {} };
    vm.createContext(context); vm.runInContext(source.slice(start, end), context);
    assert.equal(context.confirmBackupImport({ valid: true, externalCollections: 2, newExternalCollections: 1,
        importBatches: 3, newImportBatches: 2, recommendationPreferences: 1, newRecommendationPreferences: 1, recommendationFeedback: 2, newRecommendationFeedback: 1 }, '恢复预览'), true);
    assert.match(message, /外部收藏 2 条，其中新增 1 条/); assert.match(message, /导入历史 3 条，其中新增 2 条/);
    assert.match(message, /推荐条件 1 条，其中新增 1 条/); assert.match(message, /反馈历史 2 条，其中新增 1 条/);
});
test('当外部备份校验失败时应该展示冲突而不询问恢复', () => {
    let alert; const context = { confirm: () => assert.fail('不能确认无效备份'), alert: text => { alert = text; } };
    vm.createContext(context); vm.runInContext(source.slice(start, end), context);
    assert.equal(context.confirmBackupImport({ valid: false, conflicts: ['外部编号冲突'] }, '恢复预览'), false);
    assert.match(alert, /外部编号冲突/);
});
