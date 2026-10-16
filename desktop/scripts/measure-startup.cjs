const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const { launchApplication } = require('../e2e/helpers/application.cjs');

function processTree(pid) {
  assert.ok(Number.isSafeInteger(pid) && pid > 0);
  const script = '$items = @(Get-CimInstance Win32_Process); $ids = @(' + pid + '); '
    + 'do { $found = @($items | Where-Object { $_.ParentProcessId -in $ids -and $_.ProcessId -notin $ids }); '
    + '$ids += @($found.ProcessId) } while ($found.Count -gt 0); '
    + 'ConvertTo-Json -Compress -InputObject @($items | Where-Object { $_.ProcessId -in $ids } '
    + '| Select-Object ProcessId,ParentProcessId,Name,WorkingSetSize)';
  return JSON.parse(execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script],
    { encoding: 'utf8', windowsHide: true }));
}

async function measure() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-desktop-measure-'));
  const report = { platform: os.release(), cpu: os.cpus()[0].model,
    totalMemoryBytes: os.totalmem(), firstLaunchMs: [], reopenMs: [], idle: undefined };
  let current;
  try {
    for (let index = 0; index < 3; index++) {
      const directory = path.join(root, String(index));
      current = await launchApplication(directory);
      report.firstLaunchMs.push(current.startupMs);
      await current.application.close();
      current = undefined;
      current = await launchApplication(directory);
      report.reopenMs.push(current.startupMs);
      if (index === 2) {
        console.log('正在测量空闲两分钟后的进程树。');
        await current.page.evaluate(() => globalThis.scrollTo(0, 0));
        fs.mkdirSync(path.resolve(__dirname, '../test-results'), { recursive: true });
        await current.page.screenshot({ path: path.resolve(__dirname, '../test-results/desktop-home.png') });
        for (let part = 0; part < 4; part++) {
          await delay(30000);
          console.log('空闲采样等待：' + (part + 1) * 30 + ' 秒。');
        }
        const tree = processTree(current.application.process().pid);
        assert.ok(tree.some(item => item.Name === 'java.exe'), '进程树必须包含内置 Java');
        report.idle = { processCount: tree.length,
          workingSetBytes: tree.reduce((sum, item) => sum + Number(item.WorkingSetSize), 0), processes: tree };
      }
      const tree = processTree(current.application.process().pid);
      await current.application.close();
      current = undefined;
      // 只核对本次持有的进程，不按进程名处理其他程序。
      assert.deepEqual(tree.filter(item => {
        try { process.kill(item.ProcessId, 0); return true; } catch { return false; }
      }), [], '关闭后不能遗留本次启动的进程');
      console.log('完成首次启动与重开采样：' + (index + 1));
    }
    const output = path.resolve(__dirname, '../test-results/startup-measurement.json');
    fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report));
  } finally {
    await current?.application.close();
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  }
}

measure().catch(error => { console.error(error); process.exitCode = 1; });
