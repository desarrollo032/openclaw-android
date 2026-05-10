export {};

declare global {
  interface Window {
    AndroidBridge?: {
      checkInstallation(): string;
      startInstallation(): void;
      pickMigrationFile(): void;
      startGateway(): void;
      stopGateway(): void;
      getAuthToken(): string;
      getGatewayState(): string;
      notifyReady(): void;
      openTerminal(): void;
    };
    __oc?: {
      emit(type: string, data: unknown): void;
    };
    __OPENCLAW_TOKEN?: string;
  }
}
