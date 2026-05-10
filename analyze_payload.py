"""
analyze_payload.py — Análisis completo del payload-v2.tar.xz

Fase 1A: Lista todos los archivos con tamaños
Fase 1B: Identifica duplicados con jniLibs
Fase 1C: Calcula ahorro potencial
"""
import tarfile
import os
import sys

PAYLOAD = os.path.join('android', 'app', 'src', 'main', 'assets', 'payload-v2.tar.xz')

# Archivos potencialmente duplicados con jniLibs
TARGETS_IN_TAR = [
    'bin/node.real',
    'bin/node',
    'glibc/lib/ld-linux-aarch64.so.1',
    'bin/busybox',
    'bin/busybox.real',
]

# Normalize: strip leading ./ for comparison
def norm(name):
    return name.lstrip('.').lstrip('/')

def main():
    if not os.path.exists(PAYLOAD):
        print(f"ERROR: No se encontró {PAYLOAD}")
        sys.exit(1)

    tar_size_bytes = os.path.getsize(PAYLOAD)
    tar_size_mb = tar_size_bytes / (1024 * 1024)

    print("=" * 80)
    print(f"ANÁLISIS DE PAYLOAD: {PAYLOAD}")
    print(f"Tamaño del tar.xz en disco: {tar_size_mb:.2f} MB ({tar_size_bytes:,} bytes)")
    print("=" * 80)

    # ── Phase 1A: List all files ──────────────────────────────────────
    print(f"\n{'RUTA':<70} {'TAMAÑO':>10}")
    print("-" * 82)

    total_size = 0
    file_count = 0
    dir_count = 0
    symlink_count = 0
    all_files = []
    found_targets = {}

    with tarfile.open(PAYLOAD, 'r:xz') as t:
        members = t.getmembers()
        # Sort by size descending for the listing
        sorted_members = sorted(
            [m for m in members if m.isfile()],
            key=lambda m: m.size,
            reverse=True
        )

        for m in sorted_members:
            size_mb = m.size / (1024 * 1024)
            total_size += m.size
            file_count += 1
            all_files.append((m.name, m.size))

            # Only print files > 100KB to keep output manageable
            if m.size > 100 * 1024:
                print(f"{m.name:<70} {size_mb:>8.2f}MB")

            # Check if this is a target
            normalized = norm(m.name)
            for target in TARGETS_IN_TAR:
                if normalized == target or normalized.endswith('/' + target):
                    found_targets[target] = m.size

        # Count dirs and symlinks
        for m in members:
            if m.isdir():
                dir_count += 1
            elif m.issym():
                symlink_count += 1

    print("-" * 82)
    print(f"{'TOTAL':<70} {total_size / (1024 * 1024):>8.2f}MB")
    print(f"\nArchivos: {file_count} | Directorios: {dir_count} | Symlinks: {symlink_count}")

    # ── Phase 1B: Identify duplicates ─────────────────────────────────
    compression_ratio = tar_size_bytes / total_size if total_size > 0 else 1.0

    print("\n" + "=" * 80)
    print("FASE 1B — DUPLICADOS CON jniLibs")
    print("=" * 80)
    print(f"\nRatio de compresión del tar: {compression_ratio:.4f} "
          f"({tar_size_mb:.1f}MB / {total_size / (1024 * 1024):.1f}MB)")

    jnilibs_map = {
        'bin/node.real': ('libnode.so', '~120MB'),
        'bin/node': ('libnode.so (wrapper?)', '~120MB'),
        'glibc/lib/ld-linux-aarch64.so.1': ('libldlinux.so', '~241KB'),
        'bin/busybox': ('libbusybox.so', '~1.5MB'),
        'bin/busybox.real': ('libbusybox.so', '~1.5MB'),
    }

    print(f"\n{'Archivo en tar':<45} {'jniLib equiv':<20} {'En tar?':>8} {'Tamaño':>12} {'Comprim.est':>12}")
    print("-" * 100)

    total_savings_raw = 0
    total_savings_compressed = 0
    removable = []

    for target in TARGETS_IN_TAR:
        jni_name, jni_size = jnilibs_map.get(target, ('?', '?'))
        present = target in found_targets
        if present:
            raw_size = found_targets[target]
            est_compressed = raw_size * compression_ratio
            total_savings_raw += raw_size
            total_savings_compressed += est_compressed
            removable.append((target, raw_size, est_compressed))
            print(f"{target:<45} {jni_name:<20} {'SÍ':>8} {raw_size / (1024 * 1024):>10.2f}MB {est_compressed / (1024 * 1024):>10.2f}MB")
        else:
            print(f"{target:<45} {jni_name:<20} {'NO':>8} {'—':>12} {'—':>12}")

    # ── Phase 1C: Calculate savings ───────────────────────────────────
    print("\n" + "=" * 80)
    print("FASE 1C — AHORRO POTENCIAL")
    print("=" * 80)

    print(f"\nArchivos eliminables encontrados: {len(removable)}")
    for name, raw, comp in removable:
        print(f"  • {name}: {raw / (1024 * 1024):.2f}MB raw → ~{comp / (1024 * 1024):.2f}MB en tar")

    print(f"\nAhorro total sin comprimir:  {total_savings_raw / (1024 * 1024):.2f}MB")
    print(f"Ahorro estimado en tar.xz:  {total_savings_compressed / (1024 * 1024):.2f}MB")
    print(f"Tamaño actual del tar:      {tar_size_mb:.2f}MB")
    new_tar_est = tar_size_mb - (total_savings_compressed / (1024 * 1024))
    print(f"Tamaño estimado nuevo tar:  {new_tar_est:.2f}MB")
    pct = (total_savings_compressed / tar_size_bytes) * 100 if tar_size_bytes > 0 else 0
    print(f"Reducción estimada:         {pct:.1f}%")

    if total_savings_compressed / (1024 * 1024) < 5:
        print("\n⚠ AHORRO < 5MB — Posiblemente no vale la pena regenerar el tar.")
    else:
        print(f"\n✓ AHORRO SIGNIFICATIVO ({total_savings_compressed / (1024 * 1024):.1f}MB) — Vale la pena regenerar.")

    # ── Top 20 largest files ──────────────────────────────────────────
    print("\n" + "=" * 80)
    print("TOP 20 ARCHIVOS MÁS GRANDES")
    print("=" * 80)
    for name, size in all_files[:20]:
        print(f"  {size / (1024 * 1024):>10.2f}MB  {name}")

if __name__ == '__main__':
    main()
