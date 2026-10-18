const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('当隔离预加载脚本初始化时应该只向主框架暴露无参数的固定导出操作', async () => {
  for (const isMainFrame of [true, false]) {
    const exposed = [], calls = [];
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../dist/preload.js'), 'utf8'), {
      exports: {}, process: { isMainFrame },
      require(name) {
        assert.equal(name, 'electron', '沙箱预加载不应依赖Node文件或其他模块');
        return { contextBridge: { exposeInMainWorld: (key, value) => exposed.push({ key, value }) },
          ipcRenderer: { invoke: async (...args) => { calls.push(args); return { status: 'cancelled' }; } } };
      }
    });
    if (!isMainFrame) { assert.deepEqual(exposed, []); continue; }
    assert.equal(exposed[0].key, 'otakulogDesktop');
    assert.deepEqual(Object.keys(exposed[0].value), ['exportBackup']);
    assert.equal(Object.isFrozen(exposed[0].value), true);
    assert.deepEqual(await exposed[0].value.exportBackup('/任意路径', '任意通道'), { status: 'cancelled' });
    assert.deepEqual(calls, [['otakulog:backup-export']]);
  }
});
