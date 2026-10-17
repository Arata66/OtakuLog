const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const packages = [
    { name: 'chart.js', version: '4.4.1', script: 'dist/chart.umd.js', licenses: ['LICENSE.md'] },
    { name: 'sortablejs', version: '1.15.0', script: 'Sortable.min.js', licenses: ['LICENSE'] },
    { name: 'marked', version: '12.0.2', script: 'marked.min.js', licenses: ['LICENSE.md'] },
    { name: 'dompurify', version: '3.4.16', script: 'dist/purify.min.js', licenses: ['LICENSE', 'LICENSE-MPL'] }
];

function vendorAssets({ repository = path.resolve(__dirname, '..'), check = false } = {}) {
    const project = JSON.parse(fs.readFileSync(path.join(repository, 'package.json'), 'utf8'));
    const lock = JSON.parse(fs.readFileSync(path.join(repository, 'package-lock.json'), 'utf8'));
    const destination = path.join(repository, 'src/main/resources/static/js/vendor');
    const outputs = [];
    const manifest = { packages: packages.map(item => {
        const installed = JSON.parse(fs.readFileSync(path.join(repository, 'node_modules', item.name, 'package.json'), 'utf8'));
        const versions = [project.devDependencies?.[item.name], lock.packages?.['']?.devDependencies?.[item.name],
            lock.packages?.['node_modules/' + item.name]?.version, installed.version];
        if (versions.some(version => version !== item.version)) throw new Error(item.name + ' 版本与指定版本不一致');
        const files = [{ source: item.script, path: path.posix.basename(item.script) },
            ...item.licenses.map(source => ({ source, path: 'licenses/' + item.name + '-' + source }))];
        return { name: item.name, version: item.version, files: files.map(file => {
            const bytes = fs.readFileSync(path.join(repository, 'node_modules', item.name, file.source));
            outputs.push({ path: file.path, bytes });
            return { ...file, sha256: crypto.createHash('sha256').update(bytes).digest('hex') };
        }) };
    }) };
    outputs.push({ path: 'manifest.json', bytes: Buffer.from(JSON.stringify(manifest, null, 2) + '\n') });
    // 只读核验逐字对照安装包，不信任已生成清单，也不自动修复篡改。
    for (const file of outputs) {
        const target = path.join(destination, file.path);
        if (check) {
            if (!fs.existsSync(target) || !fs.readFileSync(target).equals(file.bytes))
                throw new Error('本地资源缺失或内容不同：' + file.path);
        } else {
            fs.mkdirSync(path.dirname(target), { recursive: true });
            fs.writeFileSync(target, file.bytes);
        }
    }
}

if (require.main === module) {
    try {
        const args = process.argv.slice(2);
        if (args.length && !(args.length === 1 && args[0] === '--check')) throw new Error('仅支持 --check 只读核验');
        vendorAssets({ check: args.includes('--check') });
        console.log(args.length ? '本地脚本、许可与版本哈希核验通过。' : '本地脚本、许可与版本哈希已生成。');
    } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
    }
}

module.exports = { vendorAssets };
