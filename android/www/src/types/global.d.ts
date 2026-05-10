export {};

declare global {
  interface Window {
    OpenClaw?: {
      checkInstallation(): string;
      startInstallation(): void;
      pickPayloadFile(): void;
      pickMigrationFile(): void;
      startGateway(): void;
      stopGateway(): void;
      getAuthToken(): string;
      getGatewayState(): string;
      notifyReady(): void;
      openTerminal(): void;
      showTerminal(): void;
      launchInteractiveCommand(command: string): void;
      runCommand(command: string): string;
      getSystemInfo(): string;
      getAppInfo(): string;
      getStorageInfo(): string;
      getGatewayToken(): string;
    };
    __oc?: {
      emit(type: string, data: unknown): void;
    };
    __OPENCLAW_TOKEN?: string;
  }
}
