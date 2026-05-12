export {};

declare global {
  interface Window {
    AndroidBridge?: {
      checkInstallation(): string;
      startInstallation(): void;
      pickPayloadFile(): void;
      pickMigrationFile(): void;
      startGateway(): void;
      stopGateway(): void;
      getGatewayState(): string;
      getAuthToken(): string;
      notifyReady(): void;
      openTerminal(): void;
      showTerminal(): void;
      launchInteractiveCommand(command: string): void;
      runCommand(command: string): string;
      getSystemInfo(): string;
      getAppInfo(): string;
      getStorageInfo(): string;
      getGatewayToken(): string;
      getLogs(lines: number): string;
      clearLogs(): void;
    };
    __OPENCLAW_TOKEN?: string;
    __OPENCLAW_ANDROID?: boolean;
  }
}
