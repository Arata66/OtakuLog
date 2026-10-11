const { randomUUID, randomBytes } = require('node:crypto');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

function createDemoConfig({ port = 18080, env = process.env } = {}) {
    if (!/^\d+$/.test(String(port)) || !Number.isInteger(Number(port)) || Number(port) < 1 || Number(port) > 65535 || Number(port) === 8080)
        throw new Error('演示端口必须为 1–65535，且不能占用日常预览 8080');
    const server = /^(127\.0\.0\.1|localhost):(\d+)$/.exec(env.OTAKULOG_MYSQL_SERVER || '127.0.0.1:3306');
    if (!server || Number(server[2]) < 1 || Number(server[2]) > 65535) throw new Error('演示数据库地址必须为本地回环地址和有效端口');
    const webdavUrl = env.OTAKULOG_DEMO_WEBDAV_URL || '';
    if (webdavUrl) {
        let parsed;
        try { parsed = new URL(webdavUrl); } catch { throw new Error('演示 WebDAV 地址必须为回环 HTTP 目录'); }
        if (parsed.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(parsed.hostname)
            || parsed.username || parsed.password || parsed.search || parsed.hash)
            throw new Error('演示 WebDAV 地址必须为回环 HTTP 目录，且不携带凭据或查询参数');
    }
    const aiUrl = env.OTAKULOG_DEMO_AI_URL || '';
    if (aiUrl) {
        let parsed;
        try { parsed = new URL(aiUrl); } catch { throw new Error('演示 AI 地址必须为完整的回环 HTTP 接口'); }
        if (parsed.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname)
            || !aiUrl.startsWith('http://') || parsed.pathname === '/' || aiUrl.split('/')[2].includes('@') || /[?#]/.test(aiUrl))
            throw new Error('演示 AI 地址必须为完整的回环 HTTP 接口，且不携带凭据或查询参数');
    }
    return { port: Number(port), url: `http://127.0.0.1:${port}`, env: { ...env },
        webdavUrl, aiUrl,
        host: '127.0.0.1', mysqlPort: Number(server[2]), user: env.DB_USER || 'root', password: env.DB_PASS || '123456',
        database: 'otakulog_demo_' + randomUUID().replaceAll('-', ''), loginUser: 'demo', loginPassword: randomBytes(24).toString('base64url'),
        root: path.resolve(__dirname, '../..'), java: env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java' };
}

function assertDemoDatabase(config) {
    if (!/^otakulog_demo_[a-f0-9]{32}$/.test(config.database)) throw new Error('拒绝操作非随机演示库名');
}

function javaArguments(config) {
    assertDemoDatabase(config);
    return ['-jar', path.join(config.root, 'target/otakulog-1.0.0.jar'),
        '--server.address=127.0.0.1', `--server.port=${config.port}`, '--spring.config.location=optional:classpath:/',
        `--spring.datasource.url=jdbc:mysql://${config.host}:${config.mysqlPort}/${config.database}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true`,
        `--spring.datasource.username=${config.user}`, '--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver',
        '--spring.jpa.hibernate.ddl-auto=validate', '--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect',
        '--spring.flyway.enabled=true', '--spring.flyway.locations=classpath:db/migration,classpath:com/otakulog',
        '--spring.flyway.out-of-order=true'];
}

function javaEnvironment(config) {
    // 父环境可能带真实密钥或附加配置，JVM 参数也会直接输出到日志。
    const env = Object.fromEntries(Object.entries(config.env).filter(([key]) =>
        !/^(AI_|DEEPSEEK_|APP_AI_|SPRING_CONFIG_|SPRING_PROFILES_)/i.test(key)
        && !/^(SPRING_APPLICATION_JSON|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)$/i.test(key)));
    return { ...env, SPRING_APPLICATION_JSON: JSON.stringify({
        'spring.datasource.password': config.password,
        'app.admin.username': config.loginUser, 'app.admin.password': config.loginPassword,
        'otakulog.bangumi.base-url': config.env.OTAKULOG_BANGUMI_BASE_URL || 'https://api.bgm.tv',
        // 演示只连接显式传入的回环测试服务，默认覆盖并关闭自用 WebDAV 配置。
        'otakulog.webdav.url': config.webdavUrl,
        'otakulog.webdav.username': config.webdavUrl ? config.env.OTAKULOG_DEMO_WEBDAV_USERNAME || 'demo' : '',
        'otakulog.webdav.password': config.webdavUrl ? config.env.OTAKULOG_DEMO_WEBDAV_PASSWORD || '' : '',
        'otakulog.webdav.filename': 'demo-backup.json',
        // 只有显式指定的本地假服务可启用 AI，避免演示误发自用文本或扣费。
        'app.ai.enabled': Boolean(config.aiUrl),
        'app.ai.api-key': config.aiUrl ? 'demo-ai-only' : '',
        'app.ai.endpoint': config.aiUrl || 'http://127.0.0.1:1/chat/completions',
        'app.ai.daily-limit': 20,
        'app.ai.connect-timeout-ms': 5000,
        'app.ai.response-timeout-ms': 30000,
        'app.ai.input-price-per-million': 0,
        'app.ai.output-price-per-million': 0
    }) };
}

function mysqlOptions(config) {
    return { encoding: 'utf8', timeout: 15000, windowsHide: true, env: { ...config.env, MYSQL_PWD: config.password } };
}

function executeSql(config, sql) {
    const result = spawnSync('mysql', ['--protocol=TCP', '--host=' + config.host, '--port=' + config.mysqlPort,
        '--user=' + config.user, '--batch', '--skip-column-names', '--execute=' + sql], mysqlOptions(config));
    if (result.error || result.status !== 0) throw new Error('MySQL 命令失败，请检查 mysql 命令、本地连接和建库权限');
    return result.stdout.trim();
}

async function withDemoDatabase(config, run, execute = sql => executeSql(config, sql)) {
    assertDemoDatabase(config);
    await execute(`CREATE DATABASE \`${config.database}\` CHARACTER SET utf8mb4`);
    // 只有本次创建成功才拥有清理权，创建失败不能删除同名库。
    let preserve = false;
    try { return await run(); }
    catch (error) { preserve = error.preserveDemoDatabase === true; throw error; }
    finally { if (!preserve) await execute(`DROP DATABASE \`${config.database}\``); }
}

module.exports = { createDemoConfig, javaArguments, javaEnvironment, mysqlOptions, executeSql, withDemoDatabase };
