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

const getNativeBridge = () => window.OpenClaw ?? window.AndroidBridge;

export const AndroidBridge = {
  isAvailable: () => typeof getNativeBridge() !== 'undefined',

  checkInstallation: (): InstallationStatus | null => {
    const bridge = getNativeBridge();
    if (!bridge) return null;
    try {
      return JSON.parse(bridge.checkInstallation());
    } catch {
      return null;
    }
  },

  startInstallation: () => {
    getNativeBridge()?.startInstallation();
  },

  pickMigrationFile: () => {
    getNativeBridge()?.pickMigrationFile();
  },

  pickPayloadFile: () => {
    getNativeBridge()?.pickPayloadFile();
  },

  startGateway: () => {
    getNativeBridge()?.startGateway();
  },

  stopGateway: () => {
    getNativeBridge()?.stopGateway();
  },

  openTerminal: () => {
    getNativeBridge()?.openTerminal();
  },
  
  showTerminal: () => {
    getNativeBridge()?.showTerminal();
  },

  launchInteractiveCommand: (cmd: string) => {
    getNativeBridge()?.launchInteractiveCommand(cmd);
  },

  runCommand: (cmd: string): string => {
    return getNativeBridge()?.runCommand(cmd) ?? '';
  },

  getSystemInfo: (): string => {
    return getNativeBridge()?.getSystemInfo() ?? '{}';
  },

  getAppInfo: (): string => {
    return getNativeBridge()?.getAppInfo() ?? '';
  },

  getStorageInfo: (): string => {
    return getNativeBridge()?.getStorageInfo() ?? '';
  },

  getGatewayToken: (): string => {
    return getNativeBridge()?.getGatewayToken() ?? '';
  },

  getAuthToken: (): string => {
    return getNativeBridge()?.getAuthToken() ?? window.__OPENCLAW_TOKEN ?? '';
  },

  getLogs: (lines: number): string => {
    return getNativeBridge()?.getLogs?.(lines) ?? '[]';
  },

  clearLogs: () => {
    getNativeBridge()?.clearLogs?.();
  },

  getToken: (): string => {
    return AndroidBridge.getAuthToken();
  },

  getNativeGatewayState: (): string => {
    return getNativeBridge()?.getGatewayState() ?? 'UNKNOWN';
  },

  notifyReady: () => {
    getNativeBridge()?.notifyReady?.();
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
