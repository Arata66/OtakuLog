const { setInterval } = require('node:timers');
const version = process.argv[2];
process.stdin.once('data', data => {
  const input = JSON.parse(data.toString());
  if (!/^[0-9a-f]{64}$/.test(input.controlToken)) process.exit(2);
  if (version === 'upgrade-delay' || version === 'upgrade-flood') {
    process.stdout.write('OTAKULOG_DESKTOP_UPGRADING\n');
    if (version === 'upgrade-delay') {
      setTimeout(() => process.stdout.write('OTAKULOG_DESKTOP_READY ' + JSON.stringify({
        port: 12345, version: '1.1.0-beta.1'
      }) + '\n'), 350);
    } else {
      setInterval(() => process.stdout.write('OTAKULOG_DESKTOP_UPGRADING\n'), 50);
      setTimeout(() => process.exit(28), 1500);
    }
    return;
  }
  if (version.startsWith('failure:')) {
    const code = version.slice('failure:'.length);
    process.stdout.write('OTAKULOG_DESKTOP_FAILURE ' + JSON.stringify({ code, message: '不可信异常正文' }) + '\n');
    process.stdout.write('OTAKULOG_DESKTOP_READY ' + JSON.stringify({ port: 12345, version: '1.1.0-beta.1' }) + '\n');
    setTimeout(() => {
      process.stdout.write('本次故障清理已结束\n');
      process.exit(27);
    }, 50);
    return;
  }
  if (version !== 'silent')
    process.stdout.write('OTAKULOG_DESKTOP_READY ' + JSON.stringify({ port: Number(process.argv[3] || 12345), version }) + '\n');
});
process.stdin.on('end', () => process.exit(0));
process.stdin.resume();
