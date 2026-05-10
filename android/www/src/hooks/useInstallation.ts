import { useState, useEffect } from 'react';
import { AndroidBridge, type InstallationStatus, type InstallProgress } from '../utils/androidBridge';

export function useInstallation() {
  const [status, setStatus] = useState<InstallationStatus | null>(() => AndroidBridge.checkInstallation());
  const [progress, setProgress] = useState<InstallProgress | null>(null);
  const [isInstalling, setIsInstalling] = useState(false);
  const [isDone, setIsDone] = useState(status?.payloadReady ?? false);
  const [error, setError] = useState<string | null>(null);

  const refreshStatus = () => {
    const s = AndroidBridge.checkInstallation();
    setStatus(s);
    if (s?.payloadReady) setIsDone(true);
  };

  useEffect(() => {
    // Escuchar progreso de instalación

    const hProgress = AndroidBridge.on('onInstallProgress', (data: InstallProgress) => {
      setProgress(data);
      setIsInstalling(true);
    });

    const hComplete = AndroidBridge.on('onInstallComplete', () => {
      setIsInstalling(false);
      setIsDone(true);
      setProgress(null);
      refreshStatus();
    });

    const hError = AndroidBridge.on('onInstallError', (data: { error: string }) => {
      setIsInstalling(false);
      setError(data.error);
    });

    const hFilePicked = (data: { filename: string; sizeMB: number }) => {
      console.log('File picked:', data);
      refreshStatus();
    };
    const hFile = AndroidBridge.on('onMigrationFilePicked', hFilePicked);

    return () => {
      AndroidBridge.off('onInstallProgress', hProgress);
      AndroidBridge.off('onInstallComplete', hComplete);
      AndroidBridge.off('onInstallError', hError);
      AndroidBridge.off('onMigrationFilePicked', hFile);
    };
  }, []);

  const install = () => {
    setError(null);
    setIsInstalling(true);
    AndroidBridge.startInstallation();
  };

  const pickMigration = () => {
    AndroidBridge.pickMigrationFile();
  };

  return {
    status,
    progress,
    isInstalling,
    isDone,
    error,
    install,
    pickMigration
  };
}
