const http = require('node:http');

async function createAiServer(handler = () => ({ status: 200, body: {
    choices: [{ finish_reason: 'stop', message: { content: '{"insights":[]}' } }],
    usage: { prompt_tokens: 12, completion_tokens: 3 }
} })) {
    const requests = [];
    const server = http.createServer(async (req, res) => {
        try {
            const chunks = [];
            for await (const chunk of req) chunks.push(chunk);
            const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
            // 记录仅用于核对假服务调用，额外凭据不进入失败报告。
            const headers = { 'content-type': req.headers['content-type'] };
            if (req.headers.authorization === 'Bearer demo-ai-only') headers.authorization = req.headers.authorization;
            const request = { method: req.method, url: req.url, body, headers };
            requests.push(request);
            const response = await handler(request);
            res.writeHead(response.status ?? 200, { 'Content-Type': 'application/json', ...response.headers });
            res.end(typeof response.body === 'string' ? response.body : JSON.stringify(response.body));
        } catch {
            if (!res.headersSent) res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end('{"error":"本地假服务处理失败"}');
        }
    });
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
    return { url: `http://127.0.0.1:${server.address().port}/chat/completions`, requests,
        close: () => new Promise((resolve, reject) => {
            server.close(error => error ? reject(error) : resolve());
            // 失败用例也要释放仍在等待响应的连接，避免阻塞后续隔离演示。
            server.closeAllConnections();
        }) };
}

module.exports = { createAiServer };
