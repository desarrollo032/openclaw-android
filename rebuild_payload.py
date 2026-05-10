"""
rebuild_payload.py - Regenera payload-v2.tar.xz SIN los binarios duplicados.

Archivos eliminados (ya disponibles via nativeLibraryDir):
  - ./bin/node.real    (115MB) -> libnode.so en jniLibs
  - ./glibc/lib/ld-linux-aarch64.so.1 (0.23MB) -> libldlinux.so en jniLibs

Las demas libs glibc (libc.so.6, libssl.so.3, etc.) se MANTIENEN.
lib/node_modules/ se MANTIENE intacto.
etc/tls/cert.pem se MANTIENE.

Uso:
  python rebuild_payload.py

Genera: payload-v2.tar.xz (optimizado, reemplaza el original)
Backup: payload-v2.tar.xz.bak (copia del original)
"""
import tarfile
import os
import sys
import shutil
import time

SRC = os.path.join('android', 'app', 'src', 'main', 'assets', 'payload-v2.tar.xz')
DST = os.path.join('android', 'app', 'src', 'main', 'assets', 'payload-v2-optimized.tar.xz')
BAK = SRC + '.bak'

# Archivos a EXCLUIR del nuevo tar
# Normalizados sin ./ inicial
EXCLUDE = {
    'bin/node.real',                    # 115MB - ya en libnode.so (nativeLibraryDir)
    'glibc/lib/ld-linux-aarch64.so.1',  # 0.23MB - ya en libldlinux.so (nativeLibraryDir)
}

def norm(name):
    return name.lstrip('.').lstrip('/')

def main():
    if not os.path.exists(SRC):
        print(f"ERROR: {SRC} no encontrado")
        sys.exit(1)

    src_size = os.path.getsize(SRC)
    print(f"Archivo original: {SRC}")
    print(f"Tamano original:  {src_size / (1024*1024):.2f} MB")
    print(f"Archivos a excluir: {len(EXCLUDE)}")
    for e in sorted(EXCLUDE):
        print(f"  - {e}")
    print()

    included = 0
    excluded = 0
    excluded_bytes = 0

    start = time.time()
    print("Leyendo y regenerando tar.xz (esto puede tardar varios minutos)...")

    with tarfile.open(SRC, 'r:xz') as src_tar:
        with tarfile.open(DST, 'w:xz', preset=6) as dst_tar:
            members = src_tar.getmembers()
            total = len(members)
            for i, member in enumerate(members):
                normalized = norm(member.name)
                if normalized in EXCLUDE:
                    print(f"  EXCLUIDO: {member.name} ({member.size / (1024*1024):.2f} MB)")
                    excluded += 1
                    excluded_bytes += member.size
                else:
                    if member.isfile():
                        f = src_tar.extractfile(member)
                        dst_tar.addfile(member, f)
                    else:
                        dst_tar.addfile(member)
                    included += 1

                # Progress every 5000 entries
                if (i + 1) % 5000 == 0:
                    elapsed = time.time() - start
                    print(f"  Procesados {i+1}/{total} ({elapsed:.0f}s)...")

    elapsed = time.time() - start
    dst_size = os.path.getsize(DST)

    print(f"\n{'='*60}")
    print(f"RESULTADO")
    print(f"{'='*60}")
    print(f"Incluidos: {included} archivos/dirs")
    print(f"Excluidos: {excluded} archivos ({excluded_bytes / (1024*1024):.2f} MB sin comprimir)")
    print(f"Tiempo:    {elapsed:.1f} segundos")
    print()
    print(f"Tamano original:   {src_size / (1024*1024):.2f} MB")
    print(f"Tamano nuevo:      {dst_size / (1024*1024):.2f} MB")
    savings = src_size - dst_size
    pct = (savings / src_size) * 100 if src_size > 0 else 0
    print(f"Ahorro:            {savings / (1024*1024):.2f} MB ({pct:.1f}%)")

    if savings > 0:
        print(f"\nPara aplicar los cambios:")
        print(f"  1. Verificar el contenido:")
        print(f"       python analyze_payload.py  (cambiar PAYLOAD a payload-v2-optimized)")
        print(f"  2. Hacer backup y reemplazar:")
        print(f"       copy {SRC} {BAK}")
        print(f"       move {DST} {SRC}")
        print(f"  3. Rebuild APK:")
        print(f"       gradlew assembleDebug")
    else:
        print(f"\nNo hubo ahorro. Eliminando archivo temporal.")
        os.remove(DST)

if __name__ == '__main__':
    main()
