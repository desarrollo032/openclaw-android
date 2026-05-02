#!/data/data/com.termux/files/usr/bin/bash

# Actualizar repositorios e instalar nodejs
pkg update -y
pkg install nodejs-lts -y

# Ejecutar instalador oficial de OpenClaw
curl -sL myopenclawhub.com/install | bash

# Arreglar PATH (lo mismo que hicimos manualmente)
export PATH="$PREFIX/glibc/bin:$PATH"

# Verificar instalación
node --version
openclaw --version
