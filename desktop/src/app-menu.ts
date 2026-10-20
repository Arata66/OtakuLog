import type { BrowserWindow, IpcMainInvokeEvent } from 'electron';
import { trustedSender } from './backup-export';

export function createAppMenuBridge(options: {
  getWindow: () => BrowserWindow | undefined; getOrigin: () => string | undefined;
  showMenu: (owner: BrowserWindow) => void;
}) {
  return (event: IpcMainInvokeEvent, ...args: unknown[]): boolean => {
    const owner = options.getWindow();
    if (args.length || !trustedSender(event, owner, options.getOrigin())) return false;
    options.showMenu(owner!);
    return true;
  };
}
