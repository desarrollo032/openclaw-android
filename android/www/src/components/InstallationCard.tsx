import { useInstallation } from '../hooks/useInstallation';
import { AndroidBridge } from '../utils/androidBridge';

export function InstallationCard() {
  const {
    status,
    progress,
    isInstalling,
    isDone,
    error,
    install,
    pickMigration
  } = useInstallation();

  // No mostrar si ya está instalado o no estamos en Android
  if (isDone && !error) return null;
  if (!AndroidBridge.isAvailable()) return null;

  return (
    <div className="installation-card-container">
      <div className="installation-card">
        {/* Header */}
        <div className="card-header">
          <span className="header-icon">{isInstalling ? '⚙️' : error ? '❌' : '📦'}</span>
          <div className="header-text">
            <h3>{isInstalling ? 'Instalando Entorno' : error ? 'Error de Instalación' : 'Entorno No Instalado'}</h3>
            <p>{isInstalling ? 'No cierres la aplicación' : 'Se requiere descargar archivos base'}</p>
          </div>
        </div>

        {/* STATE: Ready to install */}
        {status && !isInstalling && !error && (
          <div className="card-body">
            <div className="asset-list">
              <div className="asset-item">
                <span className={`status-dot ${status.payloadAvailable ? 'ok' : 'error'}`}></span>
                <div className="asset-info">
                  <strong>payload-v2.tar.xz</strong>
                  <span>Node.js + glibc + OpenClaw (186MB)</span>
                </div>
              </div>

              <div className="asset-item">
                <span className={`status-dot ${status.migrationAvailable ? 'ok' : 'warn'}`}></span>
                <div className="asset-info">
                  <strong>openclaw-apk-migration.tar.gz</strong>
                  <span>{status.migrationAvailable ? 'Configuración incluida' : 'Configuración opcional'}</span>
                  {!status.migrationAvailable && (
                    <button className="btn-text" onClick={pickMigration}>📁 Cargar manualmente</button>
                  )}
                </div>
              </div>
            </div>

            <div className="storage-info">
              <p>Espacio requerido: 400MB</p>
              <p className={status.freeSpaceMB < 400 ? 'text-error' : 'text-ok'}>
                Disponible: {status.freeSpaceMB}MB {status.freeSpaceMB < 400 ? '⚠️' : '✅'}
              </p>
            </div>

            <button 
              className="btn-primary" 
              onClick={install}
              disabled={!status.payloadAvailable || status.freeSpaceMB < 400}
            >
              INSTALAR AHORA
            </button>
          </div>
        )}

        {/* STATE: Installing */}
        {isInstalling && progress && (
          <div className="card-body">
            <div className="progress-container">
              <div className="progress-info">
                <span>Paso {progress.step} de {progress.totalSteps}</span>
                <span>{progress.percent}%</span>
              </div>
              <div className="progress-bar-bg">
                <div className="progress-bar-fill" style={{ width: `${progress.percent}%` }}></div>
              </div>
              <p className="current-file">{progress.currentFile}</p>
              <p className="stats">{progress.extractedMB}MB / {progress.totalMB}MB</p>
            </div>
          </div>
        )}

        {/* STATE: Error */}
        {error && (
          <div className="card-body">
            <p className="error-msg">{error}</p>
            <button className="btn-primary" onClick={install}>↺ REINTENTAR</button>
          </div>
        )}
      </div>

      <style>{`
        .installation-card-container {
          padding: 16px;
          animation: slideDown 0.4s ease-out;
        }
        .installation-card {
          background: rgba(30, 30, 45, 0.95);
          border: 1px solid rgba(99, 102, 241, 0.3);
          border-radius: 16px;
          padding: 20px;
          box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
          backdrop-filter: blur(10px);
        }
        .card-header {
          display: flex;
          align-items: center;
          gap: 16px;
          margin-bottom: 20px;
        }
        .header-icon { font-size: 24px; }
        .header-text h3 { margin: 0; font-size: 18px; color: #fff; }
        .header-text p { margin: 4px 0 0; font-size: 13px; color: #a0a0c0; }
        
        .asset-list { display: flex; flexDirection: column; gap: 12px; margin-bottom: 20px; }
        .asset-item { display: flex; align-items: flex-start; gap: 12px; }
        .status-dot { width: 10px; height: 10px; border-radius: 50%; margin-top: 6px; flex-shrink: 0; }
        .status-dot.ok { background: #4ade80; box-shadow: 0 0 8px #4ade80; }
        .status-dot.error { background: #f87171; box-shadow: 0 0 8px #f87171; }
        .status-dot.warn { background: #fbbf24; }
        
        .asset-info strong { display: block; font-size: 14px; color: #e2e2e2; }
        .asset-info span { font-size: 12px; color: #8888aa; }
        
        .btn-text { background: none; border: none; color: #6366f1; font-size: 12px; padding: 0; cursor: pointer; text-decoration: underline; margin-top: 4px; }
        
        .storage-info { background: rgba(0,0,0,0.2); padding: 12px; border-radius: 8px; margin-bottom: 20px; }
        .storage-info p { margin: 4px 0; font-size: 12px; color: #8888aa; }
        .text-error { color: #f87171; font-weight: bold; }
        .text-ok { color: #4ade80; }
        
        .btn-primary { 
          width: 100%; height: 48px; border-radius: 12px; border: none;
          background: #6366f1; color: #fff; font-weight: bold; font-size: 15px;
          cursor: pointer; transition: transform 0.2s;
        }
        .btn-primary:active { transform: scale(0.98); }
        .btn-primary:disabled { background: #334155; color: #64748b; cursor: not-allowed; }
        
        .progress-container { text-align: center; }
        .progress-info { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 13px; color: #6366f1; font-weight: bold; }
        .progress-bar-bg { height: 8px; background: #1e1e2d; border-radius: 4px; overflow: hidden; margin-bottom: 12px; }
        .progress-bar-fill { height: 100%; background: #6366f1; transition: width 0.3s ease; }
        .current-file { font-size: 11px; color: #8888aa; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-family: monospace; }
        .stats { font-size: 12px; color: #a0a0c0; margin-top: 8px; }
        
        .error-msg { color: #f87171; font-size: 13px; margin-bottom: 16px; }
        
        @keyframes slideDown {
          from { transform: translateY(-20px); opacity: 0; }
          to { transform: translateY(0); opacity: 1; }
        }
      `}</style>
    </div>
  );
}
