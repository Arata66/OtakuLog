import path from 'node:path';

export function resolveDataRoot(args: string[], env: NodeJS.ProcessEnv): string {
  const matches = args.filter(value => value.startsWith('--data-dir='));
  if (matches.length > 1) throw new Error('只能指定一个资料目录');
  const selected = matches[0]?.slice('--data-dir='.length);
  if (selected === '') throw new Error('显式资料目录不能为空');
  if (!selected && !env.LOCALAPPDATA) throw new Error('无法取得 Windows LOCALAPPDATA 用户目录');
  const directory = selected || path.join(env.LOCALAPPDATA!, 'OtakuLog');
  if (!path.isAbsolute(directory)) throw new Error('资料目录必须使用绝对路径');
  if (directory.includes(';')) throw new Error('资料目录不能包含分号');
  return path.normalize(directory);
}
