const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');

const script = path.resolve(__dirname, '../../../scripts/vendor-assets.cjs');
const packages = [
    ['chart.js', '4.4.1', 'dist/chart.umd.js', ['LICENSE.md']],
    ['sortablejs', '1.15.0', 'Sortable.min.js', ['LICENSE']],
    ['marked', '12.0.2', 'marked.min.js', ['LICENSE.md']],
    ['dompurify', '3.4.16', 'dist/purify.min.js', ['LICENSE', 'LICENSE-MPL']]
];

function fixture(t) {
    const repository = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-vendor-test-'));
    t.after(() => {
        assert.ok(path.resolve(repository).startsWith(path.resolve(os.tmpdir()) + path.sep));
        assert.match(path.basename(repository), /^otakulog-vendor-test-/);
        fs.rmSync(repository, { recursive: true, force: true });
    });
    const dependencies = {}, locked = {};
    for (const [name, version, asset, licenses] of packages) {
        dependencies[name] = version;
        locked['node_modules/' + name] = { version };
        const directory = path.join(repository, 'node_modules', name);
        fs.mkdirSync(path.dirname(path.join(directory, asset)), { recursive: true });
        fs.writeFileSync(path.join(directory, 'package.json'), JSON.stringify({ name, version }));
        fs.writeFileSync(path.join(directory, asset), '// 固定测试资源：' + name + '\n');
        for (const license of licenses) fs.writeFileSync(path.join(directory, license), '原许可：' + name + '/' + license + '\n保留换行原文\n');
    }
    fs.writeFileSync(path.join(repository, 'package.json'), JSON.stringify({ devDependencies: dependencies }));
    fs.writeFileSync(path.join(repository, 'package-lock.json'), JSON.stringify({ packages: { '': { devDependencies: dependencies }, ...locked } }));
    return repository;
}

function vendor() {
    assert.ok(fs.existsSync(script), '应该提供可复现的本地资源脚本');
    return require(script).vendorAssets;
}

test('当生成本地依赖时应该逐字保留脚本许可并生成可重复核验的版本哈希清单', t => {
    const repository = fixture(t), run = vendor();
    run({ repository });
    const destination = path.join(repository, 'src/main/resources/static/js/vendor');
    const manifest = JSON.parse(fs.readFileSync(path.join(destination, 'manifest.json'), 'utf8'));
    assert.deepEqual(manifest.packages.map(value => [value.name, value.version]), packages.map(([name, version]) => [name, version]));
    for (const item of manifest.packages) for (const file of item.files) {
        assert.match(file.sha256, /^[a-f0-9]{64}$/);
        assert.deepEqual(fs.readFileSync(path.join(destination, file.path)), fs.readFileSync(path.join(repository, 'node_modules', item.name, file.source)));
    }
    const before = fs.statSync(path.join(destination, 'manifest.json')).mtimeMs;
    run({ repository, check: true });
    assert.equal(fs.statSync(path.join(destination, 'manifest.json')).mtimeMs, before);
});

test('当脚本被篡改或许可资产遗漏时应该核验失败且不修复文件', t => {
    const repository = fixture(t), run = vendor();
    run({ repository });
    const destination = path.join(repository, 'src/main/resources/static/js/vendor');
    fs.writeFileSync(path.join(destination, 'chart.umd.js'), '篡改');
    assert.throws(() => run({ repository, check: true }), /chart\.umd\.js/);
    assert.equal(fs.readFileSync(path.join(destination, 'chart.umd.js'), 'utf8'), '篡改');
    run({ repository });
    fs.unlinkSync(path.join(destination, 'licenses/dompurify-LICENSE-MPL'));
    assert.throws(() => run({ repository, check: true }), /dompurify-LICENSE-MPL/);
    assert.equal(fs.existsSync(path.join(destination, 'licenses/dompurify-LICENSE-MPL')), false);
});

test('当已安装或锁定版本不同于指定版本时应该拒绝生成资源', t => {
    const repository = fixture(t), run = vendor();
    const installed = path.join(repository, 'node_modules/marked/package.json');
    fs.writeFileSync(installed, JSON.stringify({ version: '12.0.3' }));
    assert.throws(() => run({ repository }), /marked.*版本/);
    assert.equal(fs.existsSync(path.join(repository, 'src')), false);
    fs.writeFileSync(installed, JSON.stringify({ version: '12.0.2' }));
    const lockFile = path.join(repository, 'package-lock.json');
    const lock = JSON.parse(fs.readFileSync(lockFile));
    lock.packages['node_modules/marked'].version = '12.0.3';
    fs.writeFileSync(lockFile, JSON.stringify(lock));
    assert.throws(() => run({ repository }), /marked.*版本/);
});

test('当Windows检出本地依赖时应该逐字保留许可脚本和哈希清单', t => {
    const repository = fixture(t), run = vendor();
    run({ repository });
    const attributes = path.resolve(__dirname, '../../../.gitattributes');
    assert.ok(fs.existsSync(attributes), '应该明确关闭本地资源的换行转换');
    fs.copyFileSync(attributes, path.join(repository, '.gitattributes'));
    const git = args => execFileSync('git', ['-c', 'core.autocrlf=true', '-C', repository, ...args], { stdio: 'pipe' });
    git(['init', '--quiet']);
    git(['add', '--', '.gitattributes', 'src/main/resources/static/js/vendor']);
    git(['checkout-index', '--all', '--force']);
    run({ repository, check: true });
});

test('当暂存保留上游空白的两份许可时应该通过检查且继续拒绝第一方脚本行末空格', t => {
    const repository = fixture(t), run = vendor();
    fs.writeFileSync(path.join(repository, 'node_modules/sortablejs/LICENSE'), '上游许可原文\r\n保留原生换行\r\n');
    fs.writeFileSync(path.join(repository, 'node_modules/dompurify/LICENSE-MPL'), '上游许可原文 \n保留原生行末空格\n');
    run({ repository });
    fs.copyFileSync(path.resolve(__dirname, '../../../.gitattributes'), path.join(repository, '.gitattributes'));
    const args = ['-c', 'core.autocrlf=true', '-c', 'core.whitespace=blank-at-eol,blank-at-eof,space-before-tab', '-C', repository];
    const git = options => execFileSync('git', [...args, ...options], { stdio: 'pipe' });
    const check = () => spawnSync('git', [...args, 'diff', '--cached', '--check'], { encoding: 'utf8' });
    git(['init', '--quiet']);
    git(['add', '--', '.gitattributes', 'src/main/resources/static/js/vendor']);
    const licenses = check();
    assert.equal(licenses.status, 0, licenses.stdout + licenses.stderr);
    git(['checkout-index', '--all', '--force']);
    run({ repository, check: true });

    const firstParty = 'src/main/resources/static/js/first-party-check.js';
    fs.writeFileSync(path.join(repository, firstParty), 'const fixture = true; \n');
    git(['add', '--', firstParty]);
    const application = check();
    assert.notEqual(application.status, 0);
    assert.match(application.stdout, /first-party-check\.js:1: trailing whitespace/);
    assert.doesNotMatch(application.stdout, /licenses\/(?:sortablejs-LICENSE|dompurify-LICENSE-MPL)/);
});
