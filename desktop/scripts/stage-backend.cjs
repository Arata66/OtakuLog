const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const runtimeDefinition = require('../runtime-windows-x64.json');

function checkTree(directory) {
  const stat = fs.lstatSync(directory);
  if (stat.isSymbolicLink()) throw new Error('运行时不能包含符号链接');
  if (!stat.isDirectory()) return;
  for (const name of fs.readdirSync(directory)) checkTree(path.join(directory, name));
}

function stageBackend({ jar, runtime, destination, version, commit }) {
  if (![jar, runtime, destination].every(value => typeof value === 'string' && path.isAbsolute(value)))
    throw new Error('构建输入与目标必须是绝对路径');
  if (!/^\d+\.\d+\.\d+(?:-[\w.-]+)?$/.test(version) || !/^[0-9a-f]{40}$/.test(commit))
    throw new Error('程序版本或提交编号无效');
  if (fs.existsSync(destination) && fs.readdirSync(destination).length)
    throw new Error('目标目录必须为空，已有文件不会被覆盖');
  const runtimePath = fs.realpathSync(runtime);
  const targetPath = path.resolve(destination);
  const relative = path.relative(runtimePath, targetPath);
  if (!relative || (!relative.startsWith('..' + path.sep) && !path.isAbsolute(relative)))
    throw new Error('目标目录不能位于运行时内部');
  checkTree(runtime);
  if (!fs.lstatSync(jar).isFile() || fs.lstatSync(jar).isSymbolicLink())
    throw new Error('应用 JAR 必须是普通文件');
  const release = fs.readFileSync(path.join(runtime, 'release'), 'utf8');
  const javaVersion = /^JAVA_VERSION="([^"]+)"/m.exec(release)?.[1];
  if (javaVersion !== runtimeDefinition.version.split('+')[0])
    throw new Error('Java 运行时版本与锁定版本不一致');
  if (!fs.statSync(path.join(runtime, 'bin', 'java.exe')).isFile()
      || !fs.statSync(path.join(runtime, 'legal', 'java.base', 'LICENSE')).isFile())
    throw new Error('Java 运行时缺少启动文件或许可');
  const jarSha256 = crypto.createHash('sha256').update(fs.readFileSync(jar)).digest('hex');
  fs.mkdirSync(path.join(destination, 'backend'), { recursive: true });
  // 只收集这两个明确输入，避免带走开发目录中的私人配置。
  fs.copyFileSync(jar, path.join(destination, 'backend', 'otakulog.jar'));
  fs.cpSync(runtime, path.join(destination, 'runtime'), { recursive: true, dereference: false });
  fs.writeFileSync(path.join(destination, 'build-info.json'), JSON.stringify({
    version, commit, jarSha256, runtime: runtimeDefinition
  }, null, 2) + '\n');
}

if (require.main === module) {
  try {
    const repository = path.resolve(__dirname, '../..');
    const options = {};
    for (let i = 2; i < process.argv.length; i += 2) {
      const name = process.argv[i];
      if (!['--jar', '--runtime', '--destination'].includes(name) || !process.argv[i + 1])
        throw new Error('仅接受 --jar、--runtime、--destination 路径参数');
      options[name.slice(2)] = path.resolve(process.argv[i + 1]);
    }
    if (!options.jar || !options.runtime) throw new Error('请明确传入应用 JAR 与校验过的 Java 运行时目录');
    stageBackend({ ...options,
      destination: options.destination || path.resolve(__dirname, '../staging'),
      version: require('../package.json').version,
      commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repository, encoding: 'utf8' }).trim()
    });
    console.log('应用与 Java 运行时已准备，未收集个人数据。');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

module.exports = { stageBackend };
