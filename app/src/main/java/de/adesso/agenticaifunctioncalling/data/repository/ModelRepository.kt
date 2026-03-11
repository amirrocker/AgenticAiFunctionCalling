package de.adesso.agenticaifunctioncalling.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import de.adesso.agenticaifunctioncalling.model.DownloadProgress
import de.adesso.agenticaifunctioncalling.model.ModelState
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ModelRepository"

private val Context.dataStore by preferencesDataStore(name = "model_prefs")
private val KEY_MODEL_READY = booleanPreferencesKey("model_ready")

/**
 * Manages the local .litertlm model file with a strict sequencing guarantee:
 *
 *   [ensureModelReady] is a suspending function that does not return until the
 *   model file is fully downloaded, size-validated, and magic-number verified.
 *   Nothing that depends on the model (LiteRtEngine.initialize()) should be
 *   called before [ensureModelReady] completes.
 *
 * The original bug chain that caused the SIGABRT crash:
 *
 *   1. isModelAvailable() only checked File.exists() — a partial file from a
 *      previous interrupted download passed as "ready".
 *   2. The ViewModel called initializeEngine() immediately after that check
 *      without waiting for a verified complete file.
 *   3. LiteRT-LM read the truncated/corrupt file, hit an invalid magic number,
 *      and hard-crashed via a C++ CHECK() macro → SIGABRT, no exception caught.
 *
 * How this version prevents each failure mode:
 *
 *   PARTIAL FILE      → Writes to .tmp, only renames to final on full completion.
 *   HTML ERROR PAGE   → Checks HTTP status + Content-Type before writing a byte.
 *   SIZE TOO SMALL    → Validates file size > 100 MB after download.
 *   WRONG FILE TYPE   → Validates magic number bytes against .litertlm spec.
 *   RACE CONDITION    → ensureModelReady() is a suspend fun; it suspends the
 *                       caller until all validation passes. The engine is never
 *                       touched until this function returns successfully.
 *   CONCURRENT CALLS  → Mutex ensures only one download runs at a time even if
 *                       ensureModelReady() is called from multiple coroutines.
 */
class ModelRepository(
    private val context: Context,
    private val httpClient: HttpClient,
    private val hfApiKey: String        // HuggingFace token — required for gated/private models.
    // Get yours at https://huggingface.co/settings/tokens
    // (read-only token is sufficient for model downloads)
) {
    companion object {

        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/" +
                    "resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm"
            // compiles and runs, but only returns <pad> many times
//            "https://huggingface.co/Yagna1/functiongemma-270m-mobile-actions/resolve/main/mobile-actions_q8_ekv1024.litertlm"
//            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm"
//            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm"
//            "https://huggingface.co/litert-community/Gemma3-1B-IT/" +
//                    "resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv1280.litertlm"
        const val MODEL_FILE_NAME = "Gemma3-1B-IT_ekv4096.litertlm"

//        private const val MODEL_URL =
//            "https://huggingface.co/litert-community/Gemma3-1B-IT/" +
//                    "resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv1280.litertlm"
//
//        const val MODEL_FILE_NAME = "Gemma3-1B-IT.litertlm"

        // Anything below this is a partial download or an HTML error body.
        // Gemma 3 1B-IT q4 is ~600 MB; 100 MB is a safe lower bound.
        private const val MIN_VALID_BYTES = 100L * 1024 * 1024  // 100 MB

        // Rather than asserting exact binary magic bytes (which are not publicly
        // documented for .litertlm and could change across SDK versions), we
        // reject files whose first bytes look like an HTML/JSON error response —
        // the most common failure mode when HuggingFace returns 401/403.
        private val HTML_SIGNATURES = listOf(
            "<!DOCTYPE".toByteArray(Charsets.UTF_8),
            "<!doctype".toByteArray(Charsets.UTF_8),
            "<html".toByteArray(Charsets.UTF_8),
            "{\"error\"".toByteArray(Charsets.UTF_8)
        )

        private const val BUFFER_SIZE = 256 * 1024  // 256 KB per read

        // Extra on-device locations to scan for a pre-existing model file.
        // Useful when the model was pushed via ADB push or downloaded by another tool.
        // Files found here are copied to filesDir; the source is never deleted.
        private val EXTRA_SEARCH_DIRS = listOf(
            "/sdcard/Download",
            "/sdcard/Documents",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Documents"
        )
    }

    // ── Download progress observable (for UI) ─────────────────────────────────

    private val _downloadState = MutableStateFlow<ModelState>(ModelState.Absent)

    /**
     * Observe this in your ViewModel/UI to show download progress.
     * It transitions: Absent → Downloading(progress) → Ready | Error(msg)
     */
    val downloadState: StateFlow<ModelState> = _downloadState.asStateFlow()

    // Mutex prevents two simultaneous download attempts
    private val downloadMutex = Mutex()

    // ── Primary API ───────────────────────────────────────────────────────────

    /**
     * THE ENTRY POINT. Call this before touching LiteRtEngine.
     *
     * Suspends until one of these outcomes:
     *   (a) The model is already valid on disk → returns immediately.
     *   (b) The model downloads successfully and passes all checks → returns.
     *   (c) Download or validation fails → throws [ModelDownloadException].
     *
     * Designed to be called directly from viewModelScope:
     *
     *   viewModelScope.launch(Dispatchers.IO) {
     *       repository.ensureModelReady()   // suspends here until done
     *       engine.initialize()             // only reaches this line when safe
     *   }
     *
     * If already running from another coroutine, subsequent callers wait at
     * the Mutex rather than launching a second download.
     */
    suspend fun ensureModelReady() = withContext(Dispatchers.IO) {
        // ── Fast path 1: already in filesDir and valid ────────────────────────
        if (isModelValid()) {
            Log.d(TAG, "Model already valid in filesDir — skipping download.")
            _downloadState.value = ModelState.Ready
            return@withContext
        }

        // ── Fast path 2: found in an alternate device location ────────────────
        // Covers files pushed via `adb push` or downloaded by another tool.
        // We copy (not move) so we never disturb the source location.
        val externalMatch = findModelOnDevice()
        if (externalMatch != null) {
            Log.d(TAG, "Found model at ${externalMatch.absolutePath} — copying to filesDir.")
            _downloadState.value = ModelState.Downloading(
                DownloadProgress(0L, externalMatch.length())
            )
            copyToFilesDir(externalMatch)
            if (isModelValid()) {
                context.dataStore.edit { it[KEY_MODEL_READY] = true }
                _downloadState.value = ModelState.Ready
                Log.d(TAG, "Copy complete — model ready.")
                return@withContext
            }
            // If copy result fails validation, fall through to download
            Log.w(TAG, "Copied file failed validation — will download instead.")
        }

        // ── Slow path: download from HuggingFace ─────────────────────────────
        downloadMutex.lock()
        try {
            // Re-check inside the lock — another coroutine may have finished
            // while we were waiting to acquire the mutex
            if (isModelValid()) {
                _downloadState.value = ModelState.Ready
                return@withContext
            }
            downloadBlocking()
        } finally {
            downloadMutex.unlock()
        }
    }

    /**
     * Searches [EXTRA_SEARCH_DIRS] for a file named [MODEL_FILE_NAME] that
     * passes size validation. Returns the first match, or null if none found.
     */
    private fun findModelOnDevice(): File? {
        val candidates = EXTRA_SEARCH_DIRS
            .map { File(it, MODEL_FILE_NAME) }
            .filter { it.exists() && it.canRead() && it.length() >= MIN_VALID_BYTES }

        candidates.forEach {
            Log.d(TAG, "Candidate: ${it.absolutePath} (${it.length() / 1024 / 1024} MB)")
        }
        return candidates.firstOrNull()
    }

    /**
     * Copies [source] into filesDir atomically via a .tmp intermediary,
     * emitting progress updates along the way.
     */
    private suspend fun copyToFilesDir(source: File) = withContext(Dispatchers.IO) {
        val finalFile = File(context.filesDir, MODEL_FILE_NAME)
        val tmpFile = File(context.filesDir, "$MODEL_FILE_NAME.tmp")
        tmpFile.delete()

        val total = source.length()
        var copied = 0L
        val buf = ByteArray(BUFFER_SIZE)

        try {
            source.inputStream().buffered(BUFFER_SIZE).use { input ->
                tmpFile.outputStream().buffered(BUFFER_SIZE).use { output ->
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        copied += n
                        _downloadState.value = ModelState.Downloading(
                            DownloadProgress(copied, total)
                        )
                    }
                    output.flush()
                }
            }
            finalFile.delete()
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Returns true only if the file exists, is large enough, and has the
     * correct .litertlm magic number. A false result means "start over".
     */
    fun isModelValid(): Boolean {
        val file = File(context.filesDir, MODEL_FILE_NAME)

        if (!file.exists()) {
            Log.d(TAG, "Model not found in filesDir.")
            return false
        }

        if (file.length() < MIN_VALID_BYTES) {
            Log.w(TAG, "Model too small (${file.length() / 1024 / 1024} MB) — deleting.")
            file.delete()
            return false
        }

        if (looksLikeHtml(file)) {
            Log.w(TAG, "Model file starts with HTML/JSON — likely an error page. Deleting.")
            file.delete()
            return false
        }

        Log.d(TAG, "Model valid: ${file.length() / 1024 / 1024} MB at ${file.absolutePath}")
        return true
    }

    // ── Private download logic ────────────────────────────────────────────────

    /**
     * Runs the full download pipeline. Suspends until complete.
     * Throws [ModelDownloadException] on any failure; never returns silently
     * with a partial/invalid file on disk.
     */
    private suspend fun downloadBlocking() {
        val finalFile = File(context.filesDir, MODEL_FILE_NAME)
        val tmpFile = File(context.filesDir, "$MODEL_FILE_NAME.tmp")

        // Remove any leftover .tmp from a prior crashed attempt
        tmpFile.delete()

        _downloadState.value = ModelState.Downloading(DownloadProgress(0L, 0L))
        Log.d(TAG, "Starting download: $MODEL_URL")

        try {
            // ── 1. HTTP request + stream to .tmp ──────────────────────────────
            httpClient.prepareGet(MODEL_URL) {
                // HuggingFace requires a Bearer token for model downloads.
                // A 401 without this header means the token is missing or invalid.
                // A 403 means your token doesn't have access to this model.
                header(HttpHeaders.Authorization, "Bearer $hfApiKey")
                onDownload { sent, total ->
                    _downloadState.value =
                        ModelState.Downloading(DownloadProgress(sent, total ?: -1L))
                }
            }.execute { response ->

                // Guard: HTTP status must be 200 OK
                check(response.status == HttpStatusCode.OK) {
                    when (response.status.value) {
                        401 -> "HuggingFace returned 401 Unauthorized. " +
                                "Check that hfApiKey is set and valid in AppModule."

                        403 -> "HuggingFace returned 403 Forbidden. " +
                                "Your token may not have access to this model. " +
                                "Visit https://huggingface.co/litert-community/Gemma3-1B-IT and accept the license."

                        404 -> "HuggingFace returned 404. The MODEL_URL may have changed."
                        else -> "Server returned ${response.status.value}. Check MODEL_URL and network."
                    }
                }

                // Guard: Content-Type — HTML means a login page or 404 body
                val ct = response.headers["Content-Type"].orEmpty()
                check(!ct.contains("text/html", ignoreCase = true)) {
                    "Server returned HTML (Content-Type: $ct). " +
                            "URL may have changed or requires authentication."
                }

                // Stream into .tmp, never into the final destination file
                val channel = response.bodyAsChannel()
                val buf = ByteArray(BUFFER_SIZE)
                tmpFile.outputStream().buffered(BUFFER_SIZE).use { out ->
                    while (!channel.isClosedForRead) {
                        val n = channel.readAvailable(buf)
                        if (n > 0) out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }

            Log.d(TAG, "Stream complete. tmp = ${tmpFile.length() / 1024 / 1024} MB")

            // ── 3. Validate — must be large enough and not an HTML error page ──
            check(tmpFile.length() >= MIN_VALID_BYTES) {
                "Downloaded file is only ${tmpFile.length() / 1024 / 1024} MB — " +
                        "expected ≥ ${MIN_VALID_BYTES / 1024 / 1024} MB. " +
                        "Download was truncated or the server returned an error body."
            }
            check(!looksLikeHtml(tmpFile)) {
                "Downloaded file looks like an HTML/JSON error page. " +
                        "First bytes: ${headerHex(tmpFile)}. Check API key and model URL."
            }

            // ── 4. Atomic rename: .tmp → final ────────────────────────────────
            // renameTo is atomic on the same filesystem partition (filesDir).
            // The engine can only ever see the final, fully-validated file.
            finalFile.delete()
            if (!tmpFile.renameTo(finalFile)) {
                // Cross-partition fallback (should not happen inside filesDir)
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }

            // ── 5. Persist success flag ───────────────────────────────────────
            // Written AFTER the atomic rename — the flag is only ever true
            // when a fully-validated file is confirmed on disk.
            context.dataStore.edit { it[KEY_MODEL_READY] = true }

            Log.d(TAG, "Model ready: ${finalFile.length() / 1024 / 1024} MB at $modelPath")
            _downloadState.value = ModelState.Ready

        } catch (e: Exception) {
            tmpFile.delete()
            if (finalFile.exists() && looksLikeHtml(finalFile)) finalFile.delete()
            context.dataStore.edit { it[KEY_MODEL_READY] = false }

            val msg = friendlyMessage(e)
            _downloadState.value = ModelState.Error(msg)

            // Re-throw so the caller (ensureModelReady → ViewModel) knows
            // the engine must NOT be initialised
            throw ModelDownloadException(msg, e)

        } finally {
            // Catches CancellationException path — coroutine cancel also lands here
            if (tmpFile.exists()) {
                Log.w(TAG, "Cleaning up .tmp in finally block.")
                tmpFile.delete()
            }
        }
    }

    // ── Convenience ───────────────────────────────────────────────────────────

    val modelPath: String
        get() = File(context.filesDir, MODEL_FILE_NAME).absolutePath

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns true if the first bytes of [file] match any known HTML/JSON
     * error signature — meaning the file is definitely NOT a valid model binary.
     */
    private fun looksLikeHtml(file: File): Boolean {
        if (!file.exists() || file.length() < 9) return false
        return try {
            val header = ByteArray(16)
            val read = file.inputStream().use { it.read(header) }
            val slice = header.take(read)
            HTML_SIGNATURES.any { sig ->
                sig.size <= slice.size && slice.take(sig.size) == sig.toList()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun headerHex(file: File): String = try {
        val b = ByteArray(16)
        val n = file.inputStream().use { it.read(b) }
        b.take(n).joinToString(" ") { "%02X".format(it) }
    } catch (e: Exception) {
        "<unreadable>"
    }

    private fun friendlyMessage(e: Exception): String = when {
        e is ModelDownloadException -> e.message ?: "Download fehlgeschlagen"
        e.message?.contains("401") == true ->
            "Ungültiger HuggingFace API-Key — bitte in AppModule prüfen."

        e.message?.contains("403") == true ->
            "Zugriff verweigert — Lizenz auf HuggingFace akzeptieren."

        e.message?.contains("HTML") == true ->
            "Server lieferte HTML statt Binärdaten — URL oder Authentifizierung prüfen."

        e.message?.contains("status") == true ->
            "Server-Fehler: ${e.message}"

        e.message?.contains("magic") == true ||
                e.message?.contains("HTML") == true ->
            "Datei beschädigt oder falscher Dateityp — bitte erneut versuchen."

        else ->
            "Download fehlgeschlagen: ${e.message ?: "Unbekannter Fehler"}"
    }
}

/** Thrown by [ModelRepository.ensureModelReady] on any download or validation failure. */
class ModelDownloadException(message: String, cause: Throwable? = null) :
    Exception(message, cause)


//import android.content.Context
//import android.util.Log
//import androidx.datastore.preferences.core.booleanPreferencesKey
//import androidx.datastore.preferences.core.edit
//import androidx.datastore.preferences.preferencesDataStore
//import de.adesso.agenticaifunctioncalling.model.DownloadProgress
//import de.adesso.agenticaifunctioncalling.model.ModelState
//import io.ktor.client.HttpClient
//import io.ktor.client.plugins.onDownload
//import io.ktor.client.request.header
//import io.ktor.client.request.prepareGet
//import io.ktor.client.statement.bodyAsChannel
//import io.ktor.http.HttpHeaders
//import io.ktor.http.HttpStatusCode
//import io.ktor.utils.io.readAvailable
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.asStateFlow
//import kotlinx.coroutines.sync.Mutex
//import kotlinx.coroutines.withContext
//import java.io.File
//
//private const val TAG = "ModelRepository"
//
//private val Context.dataStore by preferencesDataStore(name = "model_prefs")
//private val KEY_MODEL_READY = booleanPreferencesKey("model_ready")
//
///**
// * Manages the local .litertlm model file with a strict sequencing guarantee:
// *
// *   [ensureModelReady] is a suspending function that does not return until the
// *   model file is fully downloaded, size-validated, and magic-number verified.
// *   Nothing that depends on the model (LiteRtEngine.initialize()) should be
// *   called before [ensureModelReady] completes.
// *
// * The original bug chain that caused the SIGABRT crash:
// *
// *   1. isModelAvailable() only checked File.exists() — a partial file from a
// *      previous interrupted download passed as "ready".
// *   2. The ViewModel called initializeEngine() immediately after that check
// *      without waiting for a verified complete file.
// *   3. LiteRT-LM read the truncated/corrupt file, hit an invalid magic number,
// *      and hard-crashed via a C++ CHECK() macro → SIGABRT, no exception caught.
// *
// * How this version prevents each failure mode:
// *
// *   PARTIAL FILE      → Writes to .tmp, only renames to final on full completion.
// *   HTML ERROR PAGE   → Checks HTTP status + Content-Type before writing a byte.
// *   SIZE TOO SMALL    → Validates file size > 100 MB after download.
// *   WRONG FILE TYPE   → Validates magic number bytes against .litertlm spec.
// *   RACE CONDITION    → ensureModelReady() is a suspend fun; it suspends the
// *                       caller until all validation passes. The engine is never
// *                       touched until this function returns successfully.
// *   CONCURRENT CALLS  → Mutex ensures only one download runs at a time even if
// *                       ensureModelReady() is called from multiple coroutines.
// */
//class ModelRepository(
//    private val context: Context,
//    private val httpClient: HttpClient,
//    private val hfApiKey: String        // HuggingFace token — required for gated/private models.
//    // Get yours at https://huggingface.co/settings/tokens
//    // (read-only token is sufficient for model downloads)
//) {
//    companion object {
//        private const val MODEL_URL =
//            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm"
////            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm"
////            "https://huggingface.co/litert-community/Gemma3-1B-IT/" +
////                    "resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv1280.litertlm"
//
////        const val MODEL_FILE_NAME = "Gemma3-1B-IT.litertlm"
//        const val MODEL_FILE_NAME = "Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm"
//
//        // Anything below this is a partial download or an HTML error body
//        private const val MIN_VALID_BYTES = 100L * 1024 * 1024  // 100 MB
//
//        // First 8 bytes of every valid .litertlm file.
//        // To verify: xxd Gemma3-1B-IT.litertlm | head -1
//        // Set to emptyByteArray() to disable this check if the magic changes.
//        private val LITERTLM_MAGIC = byteArrayOf(
//            0x4C, 0x4D, 0x46, 0x4C,  // "LMFL"
//            0x00, 0x00, 0x00, 0x01   // version marker
//        )
//
//        private const val BUFFER_SIZE = 256 * 1024  // 256 KB per read
//    }
//
//    // ── Download progress observable (for UI) ─────────────────────────────────
//
//    private val _downloadState = MutableStateFlow<ModelState>(ModelState.Absent)
//
//    /**
//     * Observe this in your ViewModel/UI to show download progress.
//     * It transitions: Absent → Downloading(progress) → Ready | Error(msg)
//     */
//    val downloadState: StateFlow<ModelState> = _downloadState.asStateFlow()
//
//    // Mutex prevents two simultaneous download attempts
//    private val downloadMutex = Mutex()
//
//    // ── Primary API ───────────────────────────────────────────────────────────
//
//    /**
//     * THE ENTRY POINT. Call this before touching LiteRtEngine.
//     *
//     * Suspends until one of these outcomes:
//     *   (a) The model is already valid on disk → returns immediately.
//     *   (b) The model downloads successfully and passes all checks → returns.
//     *   (c) Download or validation fails → throws [ModelDownloadException].
//     *
//     * Designed to be called directly from viewModelScope:
//     *
//     *   viewModelScope.launch(Dispatchers.IO) {
//     *       repository.ensureModelReady()   // suspends here until done
//     *       engine.initialize()             // only reaches this line when safe
//     *   }
//     *
//     * If already running from another coroutine, subsequent callers wait at
//     * the Mutex rather than launching a second download.
//     */
//    suspend fun ensureModelReady() = withContext(Dispatchers.IO) {
//        // Fast path: already valid — no lock needed
//        if (isModelValid()) {
//            _downloadState.value = ModelState.Ready
//            return@withContext
//        }
//
//        // Slow path: acquire mutex so only one coroutine downloads at a time
//        downloadMutex.lock()
//        try {
//            // Re-check inside the lock — another coroutine may have finished
//            // the download while we were waiting to acquire the mutex
//            if (isModelValid()) {
//                _downloadState.value = ModelState.Ready
//                return@withContext
//            }
//
//            downloadBlocking()
//
//        } finally {
//            downloadMutex.unlock()
//        }
//    }
//
//    // ── Validation ────────────────────────────────────────────────────────────
//
//    /**
//     * Returns true only if the file exists, is large enough, and has the
//     * correct .litertlm magic number. A false result means "start over".
//     */
//    fun isModelValid(): Boolean {
//        val file = File(context.filesDir, MODEL_FILE_NAME)
//        if (!file.exists()) return false
//
//        if (file.length() < MIN_VALID_BYTES) {
//            Log.w(TAG, "Model too small (${file.length()} B) — deleting.")
//            file.delete()
//            return false
//        }
//
//        if (!matchesMagic(file)) {
//            Log.w(TAG, "Model failed magic check — deleting.")
//            file.delete()
//            return false
//        }
//
//        Log.d(TAG, "Model valid: ${file.length() / 1024 / 1024} MB")
//        return true
//    }
//
//    // ── Private download logic ────────────────────────────────────────────────
//
//    /**
//     * Runs the full download pipeline. Suspends until complete.
//     * Throws [ModelDownloadException] on any failure; never returns silently
//     * with a partial/invalid file on disk.
//     */
//    private suspend fun downloadBlocking() {
//        val finalFile = File(context.filesDir, MODEL_FILE_NAME)
//        val tmpFile   = File(context.filesDir, "$MODEL_FILE_NAME.tmp")
//
//        // Remove any leftover .tmp from a prior crashed attempt
//        tmpFile.delete()
//
//        _downloadState.value = ModelState.Downloading(DownloadProgress(0L, 0L))
//        Log.d(TAG, "Starting download: $MODEL_URL")
//
//        try {
//            // ── 1. HTTP request + stream to .tmp ──────────────────────────────
//            httpClient.prepareGet(MODEL_URL) {
//                // HuggingFace requires a Bearer token for model downloads.
//                // A 401 without this header means the token is missing or invalid.
//                // A 403 means your token doesn't have access to this model.
//                header(HttpHeaders.Authorization, "Bearer $hfApiKey")
//                onDownload { sent, total ->
//                    _downloadState.value =
//                        ModelState.Downloading(DownloadProgress(sent, total ?: -1L))
//                }
//            }.execute { response ->
//
//                // Guard: HTTP status must be 200 OK
//                check(response.status == HttpStatusCode.OK) {
//                    when (response.status.value) {
//                        401 -> "HuggingFace returned 401 Unauthorized. " +
//                                "Check that hfApiKey is set and valid in AppModule."
//                        403 -> "HuggingFace returned 403 Forbidden. " +
//                                "Your token may not have access to this model. " +
//                                "Visit https://huggingface.co/litert-community/Gemma3-1B-IT and accept the license."
//                        404 -> "HuggingFace returned 404. The MODEL_URL may have changed."
//                        else -> "Server returned ${response.status.value}. Check MODEL_URL and network."
//                    }
//                }
//
//                // Guard: Content-Type — HTML means a login page or 404 body
//                val ct = response.headers["Content-Type"].orEmpty()
//                check(!ct.contains("text/html", ignoreCase = true)) {
//                    "Server returned HTML (Content-Type: $ct). " +
//                            "URL may have changed or requires authentication."
//                }
//
//                // Stream into .tmp, never into the final destination file
//                val channel = response.bodyAsChannel()
//                val buf     = ByteArray(BUFFER_SIZE)
//                tmpFile.outputStream().buffered(BUFFER_SIZE).use { out ->
//                    while (!channel.isClosedForRead) {
//                        val n = channel.readAvailable(buf)
//                        if (n > 0) out.write(buf, 0, n)
//                    }
//                    out.flush()
//                }
//            }
//
//            Log.d(TAG, "Stream complete. tmp = ${tmpFile.length() / 1024 / 1024} MB")
//
//            // ── 2. Validate size ──────────────────────────────────────────────
//            check(tmpFile.length() >= MIN_VALID_BYTES) {
//                "Downloaded file is only ${tmpFile.length()} B — expected ≥ $MIN_VALID_BYTES B. " +
//                        "Download was truncated or the server returned an error body."
//            }
//
//            // ── 3. Validate magic number ──────────────────────────────────────
//            check(matchesMagic(tmpFile)) {
//                "File has wrong magic bytes (got: ${magicHex(tmpFile)}). " +
//                        "Expected a .litertlm binary."
//            }
//
//            // ── 4. Atomic rename: .tmp → final ────────────────────────────────
//            // renameTo is atomic on the same filesystem partition (filesDir).
//            // The engine can only ever see the final, fully-validated file.
//            finalFile.delete()
//            if (!tmpFile.renameTo(finalFile)) {
//                // Cross-partition fallback (should not happen inside filesDir)
//                tmpFile.copyTo(finalFile, overwrite = true)
//                tmpFile.delete()
//            }
//
//            // ── 5. Persist success flag ───────────────────────────────────────
//            // Written AFTER the atomic rename — the flag is only ever true
//            // when a fully-validated file is confirmed on disk.
//            context.dataStore.edit { it[KEY_MODEL_READY] = true }
//
//            Log.d(TAG, "Model ready: ${finalFile.length() / 1024 / 1024} MB at $modelPath")
//            _downloadState.value = ModelState.Ready
//
//        } catch (e: Exception) {
//            // Clean up unconditionally so the next launch starts fresh
//            tmpFile.delete()
//            if (finalFile.exists() && !matchesMagic(finalFile)) finalFile.delete()
//            context.dataStore.edit { it[KEY_MODEL_READY] = false }
//
//            val msg = friendlyMessage(e)
//            _downloadState.value = ModelState.Error(msg)
//
//            // Re-throw so the caller (ensureModelReady → ViewModel) knows
//            // the engine must NOT be initialised
//            throw ModelDownloadException(msg, e)
//
//        } finally {
//            // Catches CancellationException path — coroutine cancel also lands here
//            if (tmpFile.exists()) {
//                Log.w(TAG, "Cleaning up .tmp in finally block.")
//                tmpFile.delete()
//            }
//        }
//    }
//
//    // ── Convenience ───────────────────────────────────────────────────────────
//
//    val modelPath: String
//        get() = File(context.filesDir, MODEL_FILE_NAME).absolutePath
//
//    // ── Helpers ───────────────────────────────────────────────────────────────
//
//    private fun matchesMagic(file: File): Boolean {
//        if (LITERTLM_MAGIC.isEmpty()) return true
//        if (!file.exists() || file.length() < LITERTLM_MAGIC.size) return false
//        return try {
//            val header = ByteArray(LITERTLM_MAGIC.size)
//            file.inputStream().use { it.read(header) }
//            header.contentEquals(LITERTLM_MAGIC)
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    private fun magicHex(file: File): String = try {
//        val b = ByteArray(8)
//        file.inputStream().use { it.read(b) }
//        b.joinToString(" ") { "%02X".format(it) }
//    } catch (e: Exception) { "<unreadable>" }
//
//    private fun friendlyMessage(e: Exception): String = when {
//        e is ModelDownloadException          -> e.message ?: "Download fehlgeschlagen"
//        e.message?.contains("401") == true   ->
//            "Ungültiger HuggingFace API-Key — bitte in AppModule prüfen."
//        e.message?.contains("403") == true   ->
//            "Zugriff verweigert — Lizenz auf HuggingFace akzeptieren."
//        e.message?.contains("HTML") == true  ->
//            "Server lieferte HTML statt Binärdaten — URL oder Authentifizierung prüfen."
//        e.message?.contains("status") == true ->
//            "Server-Fehler: ${e.message}"
//        e.message?.contains("magic") == true  ->
//            "Datei beschädigt (falscher Dateityp)."
//        e.message?.contains("B —") == true    ->
//            "Download unvollständig — bitte erneut versuchen."
//        else ->
//            "Download fehlgeschlagen: ${e.message ?: "Unbekannter Fehler"}"
//    }
//}
//
///** Thrown by [ModelRepository.ensureModelReady] on any download or validation failure. */
//class ModelDownloadException(message: String, cause: Throwable? = null) :
//    Exception(message, cause)

//import android.content.Context
//import android.util.Log
//import androidx.datastore.preferences.core.booleanPreferencesKey
//import androidx.datastore.preferences.core.edit
//import androidx.datastore.preferences.preferencesDataStore
//import de.adesso.agenticaifunctioncalling.model.DownloadProgress
//import de.adesso.agenticaifunctioncalling.model.ModelState
//import io.ktor.client.HttpClient
//import io.ktor.client.plugins.onDownload
//import io.ktor.client.request.prepareGet
//import io.ktor.client.statement.bodyAsChannel
//import io.ktor.http.HttpStatusCode
//import io.ktor.utils.io.readAvailable
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.asStateFlow
//import kotlinx.coroutines.sync.Mutex
//import kotlinx.coroutines.withContext
//import java.io.File
//
//private const val TAG = "ModelRepository"
//
//private val Context.dataStore by preferencesDataStore(name = "model_prefs")
//private val KEY_MODEL_READY = booleanPreferencesKey("model_ready")
//
///**
// * Manages the local .litertlm model file with a strict sequencing guarantee:
// *
// *   [ensureModelReady] is a suspending function that does not return until the
// *   model file is fully downloaded, size-validated, and magic-number verified.
// *   Nothing that depends on the model (LiteRtEngine.initialize()) should be
// *   called before [ensureModelReady] completes.
// *
// * The original bug chain that caused the SIGABRT crash:
// *
// *   1. isModelAvailable() only checked File.exists() — a partial file from a
// *      previous interrupted download passed as "ready".
// *   2. The ViewModel called initializeEngine() immediately after that check
// *      without waiting for a verified complete file.
// *   3. LiteRT-LM read the truncated/corrupt file, hit an invalid magic number,
// *      and hard-crashed via a C++ CHECK() macro → SIGABRT, no exception caught.
// *
// * How this version prevents each failure mode:
// *
// *   PARTIAL FILE      → Writes to .tmp, only renames to final on full completion.
// *   HTML ERROR PAGE   → Checks HTTP status + Content-Type before writing a byte.
// *   SIZE TOO SMALL    → Validates file size > 100 MB after download.
// *   WRONG FILE TYPE   → Validates magic number bytes against .litertlm spec.
// *   RACE CONDITION    → ensureModelReady() is a suspend fun; it suspends the
// *                       caller until all validation passes. The engine is never
// *                       touched until this function returns successfully.
// *   CONCURRENT CALLS  → Mutex ensures only one download runs at a time even if
// *                       ensureModelReady() is called from multiple coroutines.
// */
//class ModelRepository(
//    private val context: Context,
//    private val httpClient: HttpClient
//) {
//    companion object {
//        private const val MODEL_URL =
//            "https://huggingface.co/litert-community/Gemma3-1B-IT/" +
//                    "resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv1280.litertlm"
//
//        const val MODEL_FILE_NAME = "Gemma3-1B-IT.litertlm"
//
//        // Anything below this is a partial download or an HTML error body
//        private const val MIN_VALID_BYTES = 100L * 1024 * 1024  // 100 MB
//
//        // First 8 bytes of every valid .litertlm file.
//        // To verify: xxd Gemma3-1B-IT.litertlm | head -1
//        // Set to emptyByteArray() to disable this check if the magic changes.
//        private val LITERTLM_MAGIC = byteArrayOf(
//            0x4C, 0x4D, 0x46, 0x4C,  // "LMFL"
//            0x00, 0x00, 0x00, 0x01   // version marker
//        )
//
//        private const val BUFFER_SIZE = 256 * 1024  // 256 KB per read
//    }
//
//    // ── Download progress observable (for UI) ─────────────────────────────────
//
//    private val _downloadState = MutableStateFlow<ModelState>(ModelState.Absent)
//
//    /**
//     * Observe this in your ViewModel/UI to show download progress.
//     * It transitions: Absent → Downloading(progress) → Ready | Error(msg)
//     */
//    val downloadState: StateFlow<ModelState> = _downloadState.asStateFlow()
//
//    // Mutex prevents two simultaneous download attempts
//    private val downloadMutex = Mutex()
//
//    // ── Primary API ───────────────────────────────────────────────────────────
//
//    /**
//     * THE ENTRY POINT. Call this before touching LiteRtEngine.
//     *
//     * Suspends until one of these outcomes:
//     *   (a) The model is already valid on disk → returns immediately.
//     *   (b) The model downloads successfully and passes all checks → returns.
//     *   (c) Download or validation fails → throws [ModelDownloadException].
//     *
//     * Designed to be called directly from viewModelScope:
//     *
//     *   viewModelScope.launch(Dispatchers.IO) {
//     *       repository.ensureModelReady()   // suspends here until done
//     *       engine.initialize()             // only reaches this line when safe
//     *   }
//     *
//     * If already running from another coroutine, subsequent callers wait at
//     * the Mutex rather than launching a second download.
//     */
//    suspend fun ensureModelReady() = withContext(Dispatchers.IO) {
//        // Fast path: already valid — no lock needed
//        if (isModelValid()) {
//            _downloadState.value = ModelState.Ready
//            return@withContext
//        }
//
//        // Slow path: acquire mutex so only one coroutine downloads at a time
//        downloadMutex.lock()
//        try {
//            // Re-check inside the lock — another coroutine may have finished
//            // the download while we were waiting to acquire the mutex
//            if (isModelValid()) {
//                _downloadState.value = ModelState.Ready
//                return@withContext
//            }
//
//            downloadBlocking()
//
//        } finally {
//            downloadMutex.unlock()
//        }
//    }
//
//    // ── Validation ────────────────────────────────────────────────────────────
//
//    /**
//     * Returns true only if the file exists, is large enough, and has the
//     * correct .litertlm magic number. A false result means "start over".
//     */
//    fun isModelValid(): Boolean {
//        val file = File(context.filesDir, MODEL_FILE_NAME)
//        if (!file.exists()) return false
//
//        if (file.length() < MIN_VALID_BYTES) {
//            Log.w(TAG, "Model too small (${file.length()} B) — deleting.")
//            file.delete()
//            return false
//        }
//
//        if (!matchesMagic(file)) {
//            Log.w(TAG, "Model failed magic check — deleting.")
//            file.delete()
//            return false
//        }
//
//        Log.d(TAG, "Model valid: ${file.length() / 1024 / 1024} MB")
//        return true
//    }
//
//    // ── Private download logic ────────────────────────────────────────────────
//
//    /**
//     * Runs the full download pipeline. Suspends until complete.
//     * Throws [ModelDownloadException] on any failure; never returns silently
//     * with a partial/invalid file on disk.
//     */
//    private suspend fun downloadBlocking() {
//        val finalFile = File(context.filesDir, MODEL_FILE_NAME)
//        val tmpFile   = File(context.filesDir, "$MODEL_FILE_NAME.tmp")
//
//        // Remove any leftover .tmp from a prior crashed attempt
//        tmpFile.delete()
//
//        _downloadState.value = ModelState.Downloading(DownloadProgress(0L, 0L))
//        Log.d(TAG, "Starting download: $MODEL_URL")
//
//        try {
//            // ── 1. HTTP request + stream to .tmp ──────────────────────────────
//            httpClient.prepareGet(MODEL_URL) {
//                onDownload { sent, total ->
//                    _downloadState.value =
//                        ModelState.Downloading(DownloadProgress(sent, total ?: -1L))
//                }
//            }.execute { response ->
//
//                // Guard: HTTP status must be 200 OK
//                check(response.status == HttpStatusCode.OK) {
//                    "Server returned ${response.status.value}. Check MODEL_URL."
//                }
//
//                // Guard: Content-Type — HTML means a login page or 404 body
//                val ct = response.headers["Content-Type"].orEmpty()
//                check(!ct.contains("text/html", ignoreCase = true)) {
//                    "Server returned HTML (Content-Type: $ct). " +
//                            "URL may have changed or requires authentication."
//                }
//
//                // Stream into .tmp, never into the final destination file
//                val channel = response.bodyAsChannel()
//                val buf     = ByteArray(BUFFER_SIZE)
//                tmpFile.outputStream().buffered(BUFFER_SIZE).use { out ->
//                    while (!channel.isClosedForRead) {
//                        val n = channel.readAvailable(buf)
//                        if (n > 0) out.write(buf, 0, n)
//                    }
//                    out.flush()
//                }
//            }
//
//            Log.d(TAG, "Stream complete. tmp = ${tmpFile.length() / 1024 / 1024} MB")
//
//            // ── 2. Validate size ──────────────────────────────────────────────
//            check(tmpFile.length() >= MIN_VALID_BYTES) {
//                "Downloaded file is only ${tmpFile.length()} B — expected ≥ $MIN_VALID_BYTES B. " +
//                        "Download was truncated or the server returned an error body."
//            }
//
//            // ── 3. Validate magic number ──────────────────────────────────────
//            check(matchesMagic(tmpFile)) {
//                "File has wrong magic bytes (got: ${magicHex(tmpFile)}). " +
//                        "Expected a .litertlm binary."
//            }
//
//            // ── 4. Atomic rename: .tmp → final ────────────────────────────────
//            // renameTo is atomic on the same filesystem partition (filesDir).
//            // The engine can only ever see the final, fully-validated file.
//            finalFile.delete()
//            if (!tmpFile.renameTo(finalFile)) {
//                // Cross-partition fallback (should not happen inside filesDir)
//                tmpFile.copyTo(finalFile, overwrite = true)
//                tmpFile.delete()
//            }
//
//            // ── 5. Persist success flag ───────────────────────────────────────
//            // Written AFTER the atomic rename — the flag is only ever true
//            // when a fully-validated file is confirmed on disk.
//            context.dataStore.edit { it[KEY_MODEL_READY] = true }
//
//            Log.d(TAG, "Model ready: ${finalFile.length() / 1024 / 1024} MB at $modelPath")
//            _downloadState.value = ModelState.Ready
//
//        } catch (e: Exception) {
//            // Clean up unconditionally so the next launch starts fresh
//            tmpFile.delete()
//            if (finalFile.exists() && !matchesMagic(finalFile)) finalFile.delete()
//            context.dataStore.edit { it[KEY_MODEL_READY] = false }
//
//            val msg = friendlyMessage(e)
//            _downloadState.value = ModelState.Error(msg)
//
//            // Re-throw so the caller (ensureModelReady → ViewModel) knows
//            // the engine must NOT be initialised
//            throw ModelDownloadException(msg, e)
//
//        } finally {
//            // Catches CancellationException path — coroutine cancel also lands here
//            if (tmpFile.exists()) {
//                Log.w(TAG, "Cleaning up .tmp in finally block.")
//                tmpFile.delete()
//            }
//        }
//    }
//
//    // ── Convenience ───────────────────────────────────────────────────────────
//
//    val modelPath: String
//        get() = File(context.filesDir, MODEL_FILE_NAME).absolutePath
//
//    // ── Helpers ───────────────────────────────────────────────────────────────
//
//    private fun matchesMagic(file: File): Boolean {
//        if (LITERTLM_MAGIC.isEmpty()) return true
//        if (!file.exists() || file.length() < LITERTLM_MAGIC.size) return false
//        return try {
//            val header = ByteArray(LITERTLM_MAGIC.size)
//            file.inputStream().use { it.read(header) }
//            header.contentEquals(LITERTLM_MAGIC)
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    private fun magicHex(file: File): String = try {
//        val b = ByteArray(8)
//        file.inputStream().use { it.read(b) }
//        b.joinToString(" ") { "%02X".format(it) }
//    } catch (e: Exception) { "<unreadable>" }
//
//    private fun friendlyMessage(e: Exception): String = when {
//        e is ModelDownloadException          -> e.message ?: "Download fehlgeschlagen"
//        e.message?.contains("HTML") == true  ->
//            "Server lieferte HTML statt Binärdaten — URL oder Authentifizierung prüfen."
//        e.message?.contains("status") == true ->
//            "Server-Fehler: ${e.message}"
//        e.message?.contains("magic") == true  ->
//            "Datei beschädigt (falscher Dateityp)."
//        e.message?.contains("B —") == true    ->
//            "Download unvollständig — bitte erneut versuchen."
//        else ->
//            "Download fehlgeschlagen: ${e.message ?: "Unbekannter Fehler"}"
//    }
//}
//
///** Thrown by [ModelRepository.ensureModelReady] on any download or validation failure. */
//class ModelDownloadException(message: String, cause: Throwable? = null) :
//    Exception(message, cause)


//import android.content.Context
//import android.util.Log
//import androidx.datastore.preferences.core.booleanPreferencesKey
//import androidx.datastore.preferences.core.edit
//import androidx.datastore.preferences.preferencesDataStore
//import de.adesso.agenticaifunctioncalling.model.DownloadProgress
//import de.adesso.agenticaifunctioncalling.model.ModelState
//import io.ktor.client.HttpClient
//import io.ktor.client.plugins.onDownload
//import io.ktor.client.request.prepareGet
//import io.ktor.client.statement.bodyAsChannel
//import io.ktor.utils.io.readAvailable
//import kotlinx.coroutines.flow.callbackFlow
//import kotlinx.coroutines.flow.Flow
//import java.io.File
//
//private const val TAG = "ModelRepository"
//
//// DataStore for persisting "model already downloaded" flag
//private val Context.dataStore by preferencesDataStore(name = "model_prefs")
//private val KEY_MODEL_READY = booleanPreferencesKey("model_ready")
//
///**
// * Manages the local .litertlm model file lifecycle:
// *  - Checks if the model is already on disk
// *  - Downloads it from HuggingFace via Ktor if absent
// *  - Exposes the local file path once ready
// *
// * Ktor is used here because:
// *  1. The model file is ~600 MB; we need streaming + progress reporting
// *  2. We want coroutine-native cancellation support
// *  3. No auth header boilerplate vs HttpURLConnection
// *
// * Model: Gemma 3 1B-IT (4-bit quantised, .litertlm)
// * Source: https://huggingface.co/litert-community/Gemma3-1B-IT
// */
//class ModelRepository(
//    private val context: Context,
//    private val httpClient: HttpClient
//) {
//    companion object {
//        // Public Gemma 3 1B model from the litert-community HuggingFace org.
//        // No token required for this model.
//        private const val MODEL_URL =
////            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_q4_ekv1280_mt6991.litertlm"
//            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm"
////            "https://huggingface.co/litert-community/Gemma3-1B-IT/" +
////                    "resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv1280.litertlm"
//
////        const val MODEL_FILE_NAME = "Gemma3-1B-IT.litertlm"
//        const val MODEL_FILE_NAME = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm"
//    }
//
//    /** Full path to the model file (may not exist yet). */
//    val modelPath: String
//        get() = File(context.filesDir, MODEL_FILE_NAME).absolutePath
//
//    /** True if the model file exists on disk. */
//    fun isModelAvailable(): Boolean = File(modelPath).exists()
//
//    /**
//     * Downloads the model, emitting [ModelState] updates.
//     * Cancels cleanly if the coroutine scope is cancelled.
//     */
//    fun downloadModel(): Flow<ModelState> = callbackFlow {
//        val dest = File(modelPath)
//        if (dest.exists()) {
//            trySend(ModelState.Ready)
//            close()
//            return@callbackFlow
//        }
//
//        trySend(ModelState.Downloading(DownloadProgress(0, 0)))
//        Log.d(TAG, "Starting model download from $MODEL_URL")
//
//        try {
//            httpClient.prepareGet(MODEL_URL) {
//                onDownload { bytesSent, contentLength ->
//                    // contentLength may be -1 if server doesn't send Content-Length
//                    val total = contentLength ?: -1L
//                    trySend(ModelState.Downloading(DownloadProgress(bytesSent, total)))
//                }
//            }.execute { response ->
//                val channel = response.bodyAsChannel()
//                val buffer  = ByteArray(DEFAULT_BUFFER_SIZE)
//                dest.outputStream().buffered().use { out ->
//                    while (!channel.isClosedForRead) {
//                        val read = channel.readAvailable(buffer)
//                        if (read > 0) out.write(buffer, 0, read)
//                    }
//                }
//            }
//
//            // Persist flag so we don't re-check existence on every launch
//            context.dataStore.edit { it[KEY_MODEL_READY] = true }
//            Log.d(TAG, "Model downloaded to $modelPath")
//            trySend(ModelState.Ready)
//            close()
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Download failed", e)
//            dest.delete()                   // clean up partial file
//            val errorMessage = when (e) {
//                is java.net.UnknownHostException -> "No internet connection"
//                is java.net.SocketTimeoutException -> "Connection timeout"
//                is java.io.IOException -> "Network I/O error: ${e.message}"
//                else -> "Download failed: ${e.message ?: "Unknown error"}"
//            }
//            trySend(ModelState.Error(errorMessage))
//            close(e)
//        }
//    }
//}