import { useState, useEffect } from 'react';
import { AndroidBridge, type InstallationStatus, type InstallProgress } from '../utils/androidBridge';

export function useInstallation() {
  const [status, setStatus] = useState<InstallationStatus | null>(() => AndroidBridge.checkSetup());
  const [progress, setProgress] = useState<InstallProgress | null>(null);
  const [isInstalling, setIsInstalling] = useState(false);
  const [isDone, setIsDone] = useState(status?.alpineReady ?? false);
  const [error, setError] = useState<string | null>(null);

  const refreshStatus = () => {
    const s = AndroidBridge.checkSetup();
    setStatus(s);
    if (s?.alpineReady) setIsDone(true);
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

    return () => {
      AndroidBridge.off('onInstallProgress', hProgress);
      AndroidBridge.off('onInstallComplete', hComplete);
      AndroidBridge.off('onInstallError', hError);
    };
  }, []);

  const install = () => {
    setError(null);
    setIsInstalling(true);
    AndroidBridge.startSetup();
  };

  return {
    status,
    progress,
    isInstalling,
    isDone,
    error,
    install
  };
}
