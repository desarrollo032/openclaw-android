export {};

declare global {
  interface Window {
    /** Bridge nativo Android (window.OpenClaw) - interfaz principal */
    OpenClaw?: {
      // Terminal
      showTerminal(): void
      showWebView(): void
      createSession(): string
      switchSession(id: string): void
      closeSession(id: string): void
      getTerminalSessions(): string
      writeToTerminal(id: string, data: string): void
      launchInteractiveCommand(command: string): void
      openTerminal(): void

      // Setup / Installation
      getSetupStatus(): string
      startSetup(): void
      startSetupWithChannel(channel: string): void
      pickFile(callbackId: string): void
      installFromUri(uri: string, configUri: string): void
      getAssetStatus(): string

      // Tools & Platforms
      saveToolSelections(json: string): void
      getAvailablePlatforms(): string
      getInstalledPlatforms(): string
      installPlatform(id: string): void
      uninstallPlatform(id: string): void
      switchPlatform(id: string): void
      getActivePlatform(): string
      getInstalledTools(): string
      installTool(id: string): void
      uninstallTool(id: string): void
      isToolInstalled(id: string): string

      // Commands
      runCommand(command: string): string
      runOpenClawCommand(cmd: string): string
      runCommandAsync(callbackId: string, command: string): void

      // System
      getSystemInfo(): string
      getAppInfo(): string
      getApkUpdateInfo(): string
      getBatteryOptimizationStatus(): string
      requestBatteryOptimizationExclusion(): void
      openSystemSettings(page: string): void
      copyToClipboard(text: string): void
      getStorageInfo(): string
      clearCache(): void
      openUrl(url: string): void

      // Background execution
      isBackgroundExecutionEnabled(): string
      setBackgroundExecutionEnabled(enabled: boolean): void

      // OpenClaw config file
      readOpenclawJson(): string
      writeOpenclawJson(content: string): string

      // Gateway & logs
      getGatewayToken(): string
      getGatewayUrl(): string
      getGatewayState(): string
      getGatewayLogs(): string
      getLogs(lines: number): string
      clearGatewayLogs(): void
      clearLogs(): void
      getGatewayUptime(): string
      startGateway(): void
      stopGateway(): void
      restartGateway(): void

      // Auth
      getAuthToken(): string

      // Locale & Theme
      getLocale(): string
      getSystemTheme(): string

      // Updates
      checkForUpdates(): string
      applyUpdate(component: string): void

      // Legacy / misc
      notifyReady(): void
    }

    /** Bridge legacy (window.AndroidBridge) - para retrocompatibilidad */
    AndroidBridge?: {
      startGateway(): void
      stopGateway(): void
      getGatewayState(): string
      getAuthToken(): string
      getGatewayToken(): string
      notifyReady(): void
      openTerminal(): void
      showTerminal(): void
      launchInteractiveCommand(command: string): void
      runCommand(command: string): string
      getSystemInfo(): string
      getAppInfo(): string
      getStorageInfo(): string
      getLogs(lines: number): string
      clearLogs(): void
      getLocale(): string
      getSystemTheme(): string
    }

    /** Token de autenticación del gateway (cacheado desde el bridge) */
    __OPENCLAW_TOKEN?: string

    /** Indicador de que se está ejecutando dentro del WebView Android */
    __OPENCLAW_ANDROID?: boolean
  }
}
