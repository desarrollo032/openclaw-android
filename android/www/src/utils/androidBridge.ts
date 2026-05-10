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
  isAvailable: () => typeof window.OpenClaw !== 'undefined',

  checkInstallation: (): InstallationStatus | null => {
    const bridge = window.OpenClaw;
    if (!bridge) return null;
    try {
      return JSON.parse(bridge.checkInstallation());
    } catch {
      return null;
    }
  },

  startInstallation: () => {
    window.OpenClaw?.startInstallation();
  },

  pickMigrationFile: () => {
    window.OpenClaw?.pickMigrationFile();
  },

  pickPayloadFile: () => {
    window.OpenClaw?.pickPayloadFile();
  },

  startGateway: () => {
    window.OpenClaw?.startGateway();
  },

  stopGateway: () => {
    window.OpenClaw?.stopGateway();
  },

  openTerminal: () => {
    window.OpenClaw?.openTerminal();
  },
  
  showTerminal: () => {
    window.OpenClaw?.showTerminal();
  },

  launchInteractiveCommand: (cmd: string) => {
    window.OpenClaw?.launchInteractiveCommand(cmd);
  },

  runCommand: (cmd: string): string => {
    return window.OpenClaw?.runCommand(cmd) ?? '';
  },

  getSystemInfo: (): string => {
    return window.OpenClaw?.getSystemInfo() ?? '{}';
  },

  getAppInfo: (): string => {
    return window.OpenClaw?.getAppInfo() ?? '';
  },

  getStorageInfo: (): string => {
    return window.OpenClaw?.getStorageInfo() ?? '';
  },

  getGatewayToken: (): string => {
    return window.OpenClaw?.getGatewayToken() ?? '';
  },

  getAuthToken: (): string => {
    return window.OpenClaw?.getAuthToken() ?? window.__OPENCLAW_TOKEN ?? '';
  },

  getToken: (): string => {
    return AndroidBridge.getAuthToken();
  },

  getNativeGatewayState: (): string => {
    return window.OpenClaw?.getGatewayState() ?? 'UNKNOWN';
  },

  notifyReady: () => {
    window.OpenClaw?.notifyReady?.();
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
