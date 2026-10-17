const version = process.argv[2];
process.stdin.once('data', data => {
  const input = JSON.parse(data.toString());
  if (!/^[0-9a-f]{64}$/.test(input.controlToken)) process.exit(2);
  if (version !== 'silent')
    process.stdout.write('OTAKULOG_DESKTOP_READY ' + JSON.stringify({ port: Number(process.argv[3] || 12345), version }) + '\n');
});
process.stdin.on('end', () => process.exit(0));
process.stdin.resume();
