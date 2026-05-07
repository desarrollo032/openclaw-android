package com.openclaw.android

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.util.zip.GZIPInputStream

fun extractTarXzFromStream(inputStream: InputStream, targetDir: File): Boolean {
    return try {
        if (!targetDir.exists()) targetDir.mkdirs()
        XZCompressorInputStream(inputStream).use { xzIn ->
            TarArchiveInputStream(xzIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            tarIn.copyTo(fos)
                        }
                        if (entry.mode != 0) {
                            outFile.chmod(entry.mode)
                        }
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun extractTarGzFromStream(inputStream: InputStream, targetDir: File): Boolean {
    return try {
        if (!targetDir.exists()) targetDir.mkdirs()
        GZIPInputStream(inputStream).use { gzipIn ->
            TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            tarIn.copyTo(fos)
                        }
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun Context.extractTarXz(assetPath: String, targetDir: File): Boolean {
    return try {
        assets.open(assetPath).use { extractTarXzFromStream(it, targetDir) }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun Context.extractTarGz(assetPath: String, targetDir: File): Boolean {
    return try {
        assets.open(assetPath).use { extractTarGzFromStream(it, targetDir) }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun File.copyFromAssets(context: Context, assetPath: String) {
    context.assets.open(assetPath).use { inputStream ->
        this.parentFile?.mkdirs()
        FileOutputStream(this).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }
}

fun File.chmod755() {
    this.setExecutable(true, false)
    this.setReadable(true, false)
    this.setWritable(true, true)
}

fun File.chmod(mode: Int) {
    if (mode and 0x40 != 0) this.setExecutable(true, false)
    if (mode and 0x100 != 0) this.setReadable(true, false)
    if (mode and 0x80 != 0) this.setWritable(true, true)
}

fun File.deleteRecursivelySafe() {
    if (this.isDirectory) {
        this.listFiles()?.forEach { it.deleteRecursivelySafe() }
    }
    this.delete()
}
