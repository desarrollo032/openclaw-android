package com.openclaw.android

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "InstallSheet"

sealed class InstallResult {
    object Success : InstallResult()
    data class Skipped(val reason: String) : InstallResult()
    data class Error(val message: String) : InstallResult()
}

class InstallationBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_MANDATORY = "mandatory"
        fun newInstance(mandatory: Boolean) = InstallationBottomSheet().apply {
            arguments = Bundle().apply { putBoolean(ARG_MANDATORY, mandatory) }
        }
    }

    private var mandatory = true
    private var isInstalling = false
    private lateinit var content: LinearLayout

    // File picker for missing migration
    private var importedMigrationUri: Uri? = null
    private var migrationStatusView: LinearLayout? = null
    private val migrationPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        validateAndAcceptMigration(uri)
    }

    // Callbacks
    private var onInstallComplete: (() -> Unit)? = null
    private var onGatewayRequested: (() -> Unit)? = null
    fun setOnInstallComplete(cb: () -> Unit) { onInstallComplete = cb }
    fun setOnGatewayRequested(cb: () -> Unit) { onGatewayRequested = cb }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        mandatory = arguments?.getBoolean(ARG_MANDATORY, true) ?: true
    }

    override fun onCreateDialog(s: Bundle?): Dialog {
        val d = super.onCreateDialog(s) as BottomSheetDialog
        d.setOnShowListener {
            val bs = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bs?.let {
                val bh = BottomSheetBehavior.from(it)
                bh.state = BottomSheetBehavior.STATE_EXPANDED
                bh.skipCollapsed = true
                // peekHeight = 70% of screen
                val screenH = resources.displayMetrics.heightPixels
                bh.peekHeight = (screenH * 0.7).toInt()
                it.background = GradientDrawable().apply {
                    cornerRadii = floatArrayOf(24f.dp(), 24f.dp(), 24f.dp(), 24f.dp(), 0f, 0f, 0f, 0f)
                    setColor(Color.parseColor("#12121e"))
                }
                updateCancelability()
            }
        }
        return d
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_installation_sheet, c, false)
        content = v.findViewById(R.id.sheetContent)
        return v
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        showAnalyzingState()
    }

    private fun updateCancelability() {
        val can = !mandatory || !isInstalling
        dialog?.setCancelable(can)
        dialog?.setCanceledOnTouchOutside(can)
        (dialog as? BottomSheetDialog)?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.let { BottomSheetBehavior.from(it).isDraggable = can }
    }

    // ══════════════════════════════════════════════════════════════
    // ESTADO 1: ANALIZANDO
    // ══════════════════════════════════════════════════════════════

    private fun showAnalyzingState() {
        content.removeAllViews()
        content.addView(title("🔍 Analizando entorno..."))
        val spinner = ProgressBar(requireContext()).apply {
            indeterminateTintList = csl("#6366f1")
            layoutParams = lp().apply { bottomMargin = 16.dp() }
        }
        content.addView(spinner)
        content.addView(label("Verificando assets del APK", "#a0a0c0"))
        content.addView(label("Verificando espacio disponible", "#a0a0c0"))
        content.addView(label("Leyendo contenido del payload", "#a0a0c0"))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AssetDetector.detect(requireContext()) }
            if (result.payloadAvailable && result.migrationAvailable) {
                showReadyBothState(result)
            } else if (result.payloadAvailable) {
                showMigrationMissingState(result)
            } else {
                showPayloadMissingState()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ESTADO 2A: AMBOS ASSETS PRESENTES
    // ══════════════════════════════════════════════════════════════

    private fun showReadyBothState(r: AssetDetectionResult) {
        content.removeAllViews()
        content.addView(title("📦 Instalación del entorno"))
        content.addView(label("Archivos listos para instalar:", "#a0a0c0"))

        // Payload card
        content.addView(assetCard(
            "✓", "#4ade80", PAYLOAD_ASSET_NAME,
            "Node.js v22.22.0 + glibc\n+ OpenClaw",
            "Comprimido: ${formatBytes(r.payloadSizeBytes)}\nExtraído: ~280MB",
            r.payloadContents
        ))

        // Migration card
        content.addView(assetCard(
            "✓", "#4ade80", MIGRATION_ASSET_NAME,
            "Configuración personal",
            "Comprimido: ${formatBytes(r.migrationSizeBytes)}",
            r.migrationContents
        ))

        // Space info
        content.addView(spacer(12))
        content.addView(label("Espacio requerido:  ~400MB", "#a0a0c0"))
        val spaceColor = if (r.hasEnoughSpace) "#4ade80" else "#f87171"
        val spaceIcon = if (r.hasEnoughSpace) "✓" else "✗"
        content.addView(label("Espacio disponible: ${formatBytes(r.freeSpaceBytes)} $spaceIcon", spaceColor))

        content.addView(spacer(20))
        content.addView(actionBtn("INICIAR INSTALACIÓN", "#6366f1") { runInstallation(r) })
    }

    // ══════════════════════════════════════════════════════════════
    // ESTADO 2B: MIGRACIÓN AUSENTE
    // ══════════════════════════════════════════════════════════════

    private fun showMigrationMissingState(r: AssetDetectionResult) {
        content.removeAllViews()
        content.addView(title("📦 Instalación del entorno"))

        // Payload OK
        content.addView(assetCard(
            "✓", "#4ade80", PAYLOAD_ASSET_NAME,
            "Node.js + glibc + OpenClaw",
            "Comprimido: ${formatBytes(r.payloadSizeBytes)}",
            r.payloadContents
        ))

        // Migration MISSING card
        val migCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            background = cardBg("#1e1e35")
            layoutParams = lp().apply { bottomMargin = 12.dp() }
        }
        val headerRow = hRow()
        headerRow.addView(label("⚠", "#fbbf24", 18f))
        headerRow.addView(spacerH(8))
        headerRow.addView(boldLabel(MIGRATION_ASSET_NAME, "#ffffff", 13f))
        migCard.addView(headerRow)
        migCard.addView(label("No encontrado en el APK", "#fbbf24", 12f))
        migCard.addView(spacer(8))
        migCard.addView(label(
            "Este archivo contiene tu configuración personal\n" +
            "(claves API, preferencias, historial de onboarding).\n\n" +
            "Sin él, OpenClaw iniciará sin configuración previa.", "#8888aa", 12f
        ))
        migCard.addView(spacer(8))
        migCard.addView(label("Opciones:", "#a0a0c0", 12f))
        migCard.addView(spacer(4))
        migCard.addView(actionBtn("📁 Cargar desde dispositivo", "#334155") {
            migrationPicker.launch("*/*")
        })
        migCard.addView(spacer(4))
        migCard.addView(actionBtn("⏭ Continuar sin migración", "#1e293b") {
            importedMigrationUri = null
            // Just proceed — install button is always available
        })

        // Status area for selected file
        migrationStatusView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = lp().apply { topMargin = 6.dp() }
        }
        migCard.addView(migrationStatusView!!)
        content.addView(migCard)

        // Space info
        content.addView(spacer(12))
        content.addView(label("Espacio requerido:  ~400MB", "#a0a0c0"))
        val spaceColor = if (r.hasEnoughSpace) "#4ade80" else "#f87171"
        content.addView(label("Espacio disponible: ${formatBytes(r.freeSpaceBytes)} $spaceColor", spaceColor))

        content.addView(spacer(20))
        content.addView(label("(disponible con o sin migración)", "#666688", 11f))
        content.addView(spacer(4))
        content.addView(actionBtn("INICIAR INSTALACIÓN", "#6366f1") { runInstallation(r) })
    }

    // ── File picker validation ───────────────────────────────────

    private fun validateAndAcceptMigration(uri: Uri) {
        val ctx = requireContext()
        // Check filename
        val name = uri.lastPathSegment ?: ""
        if (!name.endsWith(".tar.gz")) {
            Snackbar.make(content, "Archivo no válido. Selecciona openclaw-apk-migration.tar.gz", Snackbar.LENGTH_LONG).show()
            return
        }
        // Check size > 100KB
        val size = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }
        if (size < 100 * 1024) {
            Snackbar.make(content, "Archivo demasiado pequeño (< 100KB). Selecciona el correcto.", Snackbar.LENGTH_LONG).show()
            return
        }
        // Copy to cacheDir
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                try {
                    val dest = File(ctx.cacheDir, "migration_import.tar.gz")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cache migration: ${e.message}")
                    null
                }
            }
            if (cached != null) {
                importedMigrationUri = Uri.fromFile(cached)
                migrationStatusView?.let { sv ->
                    sv.removeAllViews()
                    sv.visibility = View.VISIBLE
                    sv.addView(label("✓ ${cached.name} (${formatBytes(size)})", "#4ade80", 12f))
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // PAYLOAD MISSING (fatal)
    // ══════════════════════════════════════════════════════════════

    private fun showPayloadMissingState() {
        content.removeAllViews()
        content.addView(title("❌ Payload no encontrado"))
        content.addView(label(
            "El archivo $PAYLOAD_ASSET_NAME no está incluido en este APK.\n\n" +
            "Reinstala la aplicación con un build que incluya el payload.", "#f87171"
        ))
    }

    // ══════════════════════════════════════════════════════════════
    // ESTADO 3: INSTALANDO
    // ══════════════════════════════════════════════════════════════

    @Suppress("UNUSED_PARAMETER")
    private fun runInstallation(detection: AssetDetectionResult) {
        isInstalling = true
        updateCancelability()
        content.removeAllViews()
        content.addView(title("⚙ Instalando entorno..."))

        val stepLabel = boldLabel("Paso 1 de 2: $PAYLOAD_ASSET_NAME", "#ffffff", 14f)
        content.addView(stepLabel)

        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; isIndeterminate = false
            progressTintList = csl("#6366f1")
            progressBackgroundTintList = csl("#22223a")
            layoutParams = lp().apply { height = 8.dp(); topMargin = 12.dp(); bottomMargin = 4.dp() }
        }
        content.addView(progressBar)

        val pctLabel = boldLabel("0%", "#6366f1", 14f)
        val sizeLabel = label("", "#8888aa", 12f)
        val pctRow = hRow()
        pctRow.addView(pctLabel)
        val spaceFill = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        pctRow.addView(spaceFill)
        pctRow.addView(sizeLabel)
        content.addView(pctRow)

        content.addView(spacer(8))
        content.addView(label("Archivo actual:", "#a0a0c0", 12f))
        val currentFile = label("→ Preparando…", "#666688", 12f).apply {
            typeface = Typeface.MONOSPACE; maxLines = 1
        }
        content.addView(currentFile)

        content.addView(spacer(16))
        content.addView(divider())
        content.addView(spacer(8))
        val warningLabel = boldLabel("⚠ No cierres la aplicación", "#fbbf24", 13f).apply {
            gravity = Gravity.CENTER
        }
        content.addView(warningLabel)
        content.addView(label("La pantalla permanecerá activa", "#666688", 12f).apply {
            gravity = Gravity.CENTER
        })

        lifecycleScope.launch {
            val ctx = requireContext()

            // Step 0: SHA-256
            ui { currentFile.text = "→ Verificando integridad SHA-256…" }
            val integrityOk = withContext(Dispatchers.IO) { OpenClawInstaller.verifyPayloadIntegrity(ctx) }
            if (!integrityOk) { showErrorState("Payload corrupto — reinstala la app.", PAYLOAD_ASSET_NAME, ""); return@launch }

            // Step 1: Payload
            val payloadOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.installPayload(ctx) { msg, pct ->
                    ui {
                        currentFile.text = "→ $msg"
                        if (pct in 0..100) {
                            progressBar.progress = pct
                            pctLabel.text = "$pct%"
                        }
                        sizeLabel.text = msg.substringAfter("... ", "")
                    }
                }
            }
            if (!payloadOk) { showErrorState("Falló la extracción del payload.", PAYLOAD_ASSET_NAME, ""); return@launch }

            // Step 2: Migration
            ui {
                stepLabel.text = "Paso 2 de 2: configuración personal"
                progressBar.progress = 0
                pctLabel.text = "0%"
            }

            val migResult = withContext(Dispatchers.IO) {
                installMigration(ctx) { msg, pct ->
                    ui {
                        currentFile.text = "→ $msg"
                        if (pct in 0..100) { progressBar.progress = pct; pctLabel.text = "$pct%" }
                    }
                }
            }

            // Step 3: BusyBox symlinks
            ui { currentFile.text = "→ Configurando BusyBox…" }
            withContext(Dispatchers.IO) {
                val count = OpenClawTerminalManager(ctx).createBusyboxSymlinks()
                Log.i(TAG, "BusyBox symlinks: $count")
            }

            ui { progressBar.progress = 100; pctLabel.text = "100%" }
            showSuccessState(migResult)
        }
    }

    private suspend fun installMigration(
        context: android.content.Context,
        onProgress: (String, Int) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        // 1. Try imported file first
        importedMigrationUri?.let { uri ->
            onProgress("Restaurando configuración importada…", 50)
            val ok = OpenClawInstaller.restoreConfigFromUri(context, uri) { msg, pct -> onProgress(msg, pct) }
            return@withContext if (ok) InstallResult.Success else InstallResult.Error("Error al restaurar desde archivo importado")
        }
        // 2. Try bundled asset
        val exists = try { context.assets.open(MIGRATION_ASSET_NAME).close(); true } catch (_: Exception) { false }
        if (!exists) return@withContext InstallResult.Skipped("No incluido en APK")
        onProgress("Restaurando configuración…", 10)
        val ok = OpenClawInstaller.restoreConfig(context) { msg, pct -> onProgress(msg, pct) }
        if (ok) InstallResult.Success else InstallResult.Error("Error extrayendo configuración")
    }

    // ══════════════════════════════════════════════════════════════
    // ESTADO 4A: ÉXITO
    // ══════════════════════════════════════════════════════════════

    private fun showSuccessState(migResult: InstallResult) {
        isInstalling = false
        updateCancelability()
        ui {
            content.removeAllViews()
            content.addView(title("✅ Instalación completada"))
            content.addView(checkLine("Node.js v22.22.0 instalado"))
            content.addView(checkLine("OpenClaw instalado"))
            content.addView(checkLine("glibc ARM64 configurado"))
            content.addView(checkLine("Certificados SSL configurados"))
            when (migResult) {
                is InstallResult.Success -> content.addView(checkLine("Configuración personal restaurada"))
                is InstallResult.Skipped -> content.addView(label("Sin configuración previa", "#fbbf24", 13f))
                is InstallResult.Error -> {
                    content.addView(label("⚠ La configuración personal no pudo restaurarse.", "#fbbf24", 13f))
                    content.addView(label("Puedes importarla después desde Ajustes.", "#888888", 12f))
                }
            }
            content.addView(spacer(24))
            content.addView(actionBtn("INICIAR GATEWAY", "#4ade80") {
                onGatewayRequested?.invoke()
                dismiss()
            })
            onInstallComplete?.invoke()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ESTADO 4B: ERROR
    // ══════════════════════════════════════════════════════════════

    private fun showErrorState(message: String, failedFile: String, stackTrace: String) {
        isInstalling = false
        updateCancelability()
        ui {
            content.removeAllViews()
            content.addView(title("❌ Error en la instalación"))
            content.addView(label("Falló: $failedFile", "#f87171", 14f))
            content.addView(label(message, "#a0a0c0", 13f))
            content.addView(spacer(8))

            // Expandable error log
            val logExpander = label("▶ Ver log completo del error", "#6366f1", 13f)
            val logContent = label(stackTrace.ifEmpty { message }, "#666688", 11f).apply {
                typeface = Typeface.MONOSPACE; visibility = View.GONE
            }
            logExpander.setOnClickListener {
                if (logContent.visibility == View.GONE) {
                    logContent.visibility = View.VISIBLE; logExpander.text = "▼ Ocultar log"
                } else {
                    logContent.visibility = View.GONE; logExpander.text = "▶ Ver log completo del error"
                }
            }
            content.addView(logExpander)
            content.addView(logContent)

            content.addView(spacer(12))
            content.addView(label("Posibles soluciones:", "#a0a0c0", 13f))
            content.addView(label("• Libera al menos 400MB de espacio", "#888888", 12f))
            content.addView(label("• Desinstala apps no usadas", "#888888", 12f))
            content.addView(spacer(16))
            content.addView(actionBtn("🗑 Limpiar instalación parcial", "#334155") {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { OpenClawInstaller.uninstall(requireContext()) }
                    showAnalyzingState()
                }
            })
            content.addView(spacer(8))
            content.addView(actionBtn("↺ Reintentar instalación", "#6366f1") { showAnalyzingState() })
        }
    }

    // ── UI helpers ────────────────────────────────────────────────

    private fun Float.dp(): Float = this * (resources.displayMetrics.density)
    private fun Int.dp(): Int = (this * (resources.displayMetrics.density)).toInt()
    private fun lp() = LinearLayout.LayoutParams(-1, -2)
    private fun csl(c: String) = ColorStateList.valueOf(Color.parseColor(c))
    private fun ui(block: () -> Unit) { activity?.runOnUiThread(block) }

    private fun title(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); layoutParams = lp().apply { bottomMargin = 16.dp() }
    }

    private fun label(t: String, color: String, size: Float = 13f) = TextView(requireContext()).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color))
        layoutParams = lp().apply { bottomMargin = 4.dp() }
    }

    private fun boldLabel(t: String, color: String, size: Float) = TextView(requireContext()).apply {
        text = t; textSize = size; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor(color)); layoutParams = lp().apply { bottomMargin = 4.dp() }
    }

    private fun checkLine(t: String) = label("  ✓  $t", "#4ade80", 14f)

    private fun spacer(h: Int) = View(requireContext()).apply { layoutParams = lp().apply { height = h.dp() } }
    private fun spacerH(w: Int) = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(w.dp(), 1)
    }

    private fun divider() = View(requireContext()).apply {
        setBackgroundColor(Color.parseColor("#22223a")); layoutParams = lp().apply { height = 1 }
    }

    private fun hRow() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp()
    }

    private fun cardBg(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = 14f.dp()
    }

    private fun actionBtn(text: String, color: String, onClick: () -> Unit) = Button(requireContext()).apply {
        this.text = text; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); isAllCaps = false
        background = GradientDrawable().apply { cornerRadius = 14f.dp(); setColor(Color.parseColor(color)) }
        layoutParams = lp().apply { height = 52.dp(); bottomMargin = 4.dp() }
        setOnClickListener { onClick() }
    }

    private fun assetCard(
        icon: String, iconColor: String, filename: String,
        desc: String, sizeInfo: String, contents: List<String>
    ): LinearLayout {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            background = cardBg("#1a1a30")
            layoutParams = lp().apply { bottomMargin = 12.dp() }
        }
        val header = hRow()
        header.addView(label(icon, iconColor, 16f))
        header.addView(spacerH(8))
        header.addView(boldLabel(filename, "#ffffff", 13f))
        card.addView(header)
        card.addView(label(desc, "#a0a0c0", 12f))
        card.addView(label(sizeInfo, "#8888aa", 11f))

        if (contents.isNotEmpty()) {
            val expandLabel = label("▶ Ver contenido detallado", "#6366f1", 12f)
            val expandContent = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; visibility = View.GONE
                layoutParams = lp().apply { topMargin = 4.dp() }
            }
            val displayItems = contents.take(15)
            displayItems.forEach { entry ->
                expandContent.addView(TextView(requireContext()).apply {
                    text = "• $entry"; textSize = 11f; typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#666688")); maxLines = 1
                })
            }
            if (contents.size > 15) {
                expandContent.addView(label("... (${contents.size} archivos total)", "#555577", 11f))
            }
            expandLabel.setOnClickListener {
                if (expandContent.visibility == View.GONE) {
                    expandContent.visibility = View.VISIBLE; expandLabel.text = "▼ Ocultar contenido"
                } else {
                    expandContent.visibility = View.GONE; expandLabel.text = "▶ Ver contenido detallado"
                }
            }
            card.addView(spacer(4))
            card.addView(expandLabel)
            card.addView(expandContent)
        }
        return card
    }
}
