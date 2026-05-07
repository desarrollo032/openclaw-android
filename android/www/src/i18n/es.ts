export const es = {
  // App tab bar
  tab_terminal: 'Terminal',
  tab_dashboard: 'Panel',
  tab_settings: 'Ajustes',

  // Setup - steps
  step_platform: 'Plataforma',
  step_tools: 'Herramientas',
  step_setup: 'Instalación',

  // Setup - platform select
  setup_choose_platform: 'Elige tu plataforma',
  setup_more_platforms: 'Más plataformas disponibles en Ajustes.',

  // Setup - tool select
  setup_optional_tools: 'Herramientas Opcionales',
  setup_tools_desc: 'Selecciona herramientas para instalar junto con {platform}. Puedes añadir más después en Ajustes.',
  setup_start: 'Comenzar Instalación',

  // Setup - installing
  setup_setting_up: 'Configurando...',
  setup_preparing: 'Preparando instalación...',

  // Setup - done
  setup_done_title: '¡Todo listo!',
  setup_done_desc: 'La terminal instalará ahora los componentes de ejecución y las herramientas seleccionadas. Esto tardará de 3 a 10 minutos.',
  setup_open_terminal: 'Abrir Terminal',

  // Setup - tips
  tip_1: 'Puedes instalar múltiples plataformas de IA y cambiar entre ellas en cualquier momento.',
  tip_2: 'La configuración es un proceso de una sola vez. Los futuros arranques son instantáneos.',
  tip_3: 'Una vez completada la configuración, tu asistente de IA funcionará a plena velocidad, como en un ordenador.',
  tip_4: 'Todo el procesamiento ocurre localmente en tu dispositivo. Tus datos nunca salen de tu teléfono.',

  // Setup - tool descriptions
  tool_tmux: 'Multiplexor de terminal para sesiones en segundo plano',
  tool_ttyd: 'Terminal web — acceso desde un navegador',
  tool_dufs: 'Servidor de archivos (WebDAV)',
  tool_code_server: 'VS Code en el navegador',
  tool_claude_code: 'CLI de IA de Anthropic',
  tool_gemini_cli: 'CLI de IA de Google',
  tool_codex_cli: 'CLI de IA de OpenAI',

  // Dashboard
  dash_setup_required: 'Configuración Requerida',
  dash_setup_desc: 'El entorno de ejecución aún no se ha configurado.',
  dash_commands: 'Comandos',
  dash_runtime: 'Entorno',
  dash_management: 'Gestión',

  // Dashboard - commands
  cmd_gateway: 'Iniciar el gateway',
  cmd_status: 'Mostrar estado del gateway',
  cmd_onboard: 'Asistente de configuración inicial',
  cmd_logs: 'Seguir logs en vivo',
  cmd_update: 'Actualizar OpenClaw y todos los componentes',
  cmd_install_tools: 'Añadir o quitar herramientas opcionales',

  // Settings
  settings_title: 'Ajustes',
  settings_platforms: 'Plataformas',
  settings_platforms_desc: 'Gestionar plataformas instaladas',
  settings_updates: 'Actualizaciones',
  settings_updates_desc: 'Buscar actualizaciones',
  settings_keep_alive: 'Mantener Activo',
  settings_keep_alive_desc: 'Evitar que el sistema cierre la app',
  settings_storage: 'Almacenamiento',
  settings_storage_desc: 'Gestionar uso de disco',
  settings_about: 'Acerca de',
  settings_about_desc: 'Información de la app y licencias',

  // Settings - Keep Alive
  ka_title: 'Mantener Activo',
  ka_desc: 'Android puede cerrar procesos en segundo plano después de un tiempo. Sigue estos pasos para evitarlo.',
  ka_battery: '1. Optimización de Batería',
  ka_status: 'Estado',
  ka_excluded: '✓ Excluido',
  ka_request: 'Solicitar Exclusión',
  ka_developer: '2. Opciones de Desarrollador',
  ka_developer_desc: '• Activa las Opciones de Desarrollador\n• Activa "Pantalla siempre activa"',
  ka_open_dev: 'Abrir Opciones de Desarrollador',
  ka_phantom: '3. Phantom Process Killer (Android 12+)',
  ka_phantom_desc: 'Conecta el USB y activa la depuración ADB, luego ejecuta este comando en tu PC:',
  ka_copy: 'Copiar',
  ka_copied: '¡Copiado!',
  ka_charge: '4. Límite de Carga (Opcional)',
  ka_charge_desc: 'Establece el límite de carga de la batería al 80% para un uso continuo. Esto se puede configurar en los ajustes de batería de tu teléfono.',

  // Settings - Storage
  storage_title: 'Almacenamiento',
  storage_total: 'Total usado: ',
  storage_bootstrap: 'Bootstrap (usr/)',
  storage_www: 'Web UI (www/)',
  storage_free: 'Espacio Libre',
  storage_clear: 'Limpiar Caché',
  storage_clearing: 'Limpiando...',
  storage_loading: 'Cargando info de almacenamiento...',

  // Settings - About
  about_title: 'Acerca de',
  about_version: 'Versión',
  about_apk: 'APK',
  about_update_available: 'Actualización disponible',
  about_package: 'Paquete',
  about_script: 'Script',
  about_runtime: 'Entorno',
  about_license: 'Licencia',
  about_app_info: 'Información de la App',
  about_made_for: 'Hecho para Android',

  // Settings - Updates
  updates_title: 'Actualizaciones',
  updates_checking: 'Buscando actualizaciones...',
  updates_up_to_date: 'Todo está actualizado.',
  updates_updating: 'Actualizando {name}...',
  updates_update: 'Actualizar',

  // Settings - Platforms
  platforms_title: 'Plataformas',
  platforms_installing: 'Instalando {name}...',
  platforms_active: 'Activo',
  platforms_install: 'Instalar y Cambiar',
}
