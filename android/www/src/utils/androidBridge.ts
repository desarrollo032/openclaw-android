/**
 * src/utils/androidBridge.ts
 * Wrapper TypeScript para window.AndroidBridge (§2.6).
 */

export interface InstallationStatus {
  payloadReady: boolean;
  payloadAvailable: boolean;
  migrationAvailable: boolean;
  freeSpaceMB: number;
  requiredSpaceMB: number;
  payloadSource?: 'apk' | 'local' | 'missing';
  migrationSource?: 'apk' | 'local' | 'missing';
  payloadSizeBytes?: number;
  migrationSizeBytes?: number;
}

export interface InstallProgress {
  step: number;
  totalSteps: number;
  extractedMB: number;
  totalMB: number;
  percent: number;
  currentFile: string;
  stepName: string;
}

export const AndroidBridge = {
  isAvailable: () => typeof window.AndroidBridge !== 'undefined',

  checkInstallation: (): InstallationStatus | null => {
    const bridge = window.AndroidBridge;
    if (!bridge) return null;
    try {
      return JSON.parse(bridge.checkInstallation());
    } catch {
      return null;
    }
  },

  startInstallation: () => {
    window.AndroidBridge?.startInstallation();
  },

  pickMigrationFile: () => {
    window.AndroidBridge?.pickMigrationFile();
  },

  pickPayloadFile: () => {
    window.AndroidBridge?.pickPayloadFile();
  },

  startGateway: () => {
    window.AndroidBridge?.startGateway();
  },

  stopGateway: () => {
    window.AndroidBridge?.stopGateway();
  },

  openTerminal: () => {
    window.AndroidBridge?.openTerminal();
  },
  
  showTerminal: () => {
    window.AndroidBridge?.showTerminal();
  },

  launchInteractiveCommand: (cmd: string) => {
    window.AndroidBridge?.launchInteractiveCommand(cmd);
  },

  runCommand: (cmd: string): string => {
    return window.AndroidBridge?.runCommand(cmd) ?? '';
  },

  getSystemInfo: (): string => {
    return window.AndroidBridge?.getSystemInfo() ?? '{}';
  },

  getAppInfo: (): string => {
    return window.AndroidBridge?.getAppInfo() ?? '';
  },

  getStorageInfo: (): string => {
    return window.AndroidBridge?.getStorageInfo() ?? '';
  },

  getGatewayToken: (): string => {
    return window.AndroidBridge?.getGatewayToken() ?? '';
  },

  getAuthToken: (): string => {
    return window.AndroidBridge?.getAuthToken() ?? window.__OPENCLAW_TOKEN ?? '';
  },

  getLogs: (lines: number): string => {
    return window.AndroidBridge?.getLogs(lines) ?? '[]';
  },

  clearLogs: () => {
    window.AndroidBridge?.clearLogs();
  },

  getToken: (): string => {
    return AndroidBridge.getAuthToken();
  },

  getNativeGatewayState: (): string => {
    return window.AndroidBridge?.getGatewayState() ?? 'UNKNOWN';
  },

  notifyReady: () => {
    window.AndroidBridge?.notifyReady?.();
  },

  onTokenRefresh: (callback: (token: string) => void) => {
    return AndroidBridge.on('onTokenRefresh', (data: { token: string }) => {
      if (data.token) {
        window.__OPENCLAW_TOKEN = data.token;
        callback(data.token);
      }
    });
  },

  // Escuchar eventos de Android -> React
  on: <T = unknown>(event: string, callback: (data: T) => void) => {
    const handler = (e: Event) => {
      const customEvent = e as CustomEvent<T>;
      callback(customEvent.detail);
    };
    window.addEventListener(`android:${event}`, handler);
    return handler;
  },

  off: (event: string, handler: EventListenerOrEventListenerObject) => {
    window.removeEventListener(`android:${event}`, handler);
  }
};

export const getToken = AndroidBridge.getToken;
export const notifyReady = AndroidBridge.notifyReady;
export const onTokenRefresh = AndroidBridge.onTokenRefresh;
export const getNativeGatewayState = AndroidBridge.getNativeGatewayState;
