# ── Apache Commons Compress ──────────────────────────────────────────────────
# Usado por OpenClawProot.downloadAndExtractAlpine() para extraer el rootfs
# Alpine Linux (tar.gz) sin depender de /system/bin/tar del dispositivo.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# ── XZ for Java ──────────────────────────────────────────────────────────────
# Dependencia transitiva de commons-compress (org.tukaani:xz)
-keep class org.tukaani.** { *; }
-dontwarn org.tukaani.**
