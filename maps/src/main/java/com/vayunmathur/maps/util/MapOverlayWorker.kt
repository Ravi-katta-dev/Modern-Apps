package com.vayunmathur.maps.util

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Checks for and fetches incremental map updates (Phase 2: download + store).
 *
 * One run: fetch `planet3.manifest.json` (kilobytes, any network), compare
 * its overlay sequence against the pinned manifest, and — when newer —
 * download the missing segments sequentially through the shared
 * [downloadModels] path (`.part` + Range resume, per-segment SHA-256, atomic
 * rename). Segments land under `overlays/<seq>/`; the base
 * `basemap.mamaps` is never touched, so peak transient disk is one segment.
 * On success the manifest text is pinned; the atomic reader swap to
 * `(base, seq)` is Phase 3 work — until then new segments sit inert on disk
 * and the map keeps reading the base.
 *
 * Two entry points: [checkAuto] (UNMETERED periodic check, gated on the
 * auto-update toggle) and [checkManual] (user-tapped, any connectivity).
 * Both KEEP an in-flight run rather than doubling it.
 */
class MapOverlayWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manifestUrl = inputData.getString(KEY_MANIFEST_URL) ?: MapOverlay.MANIFEST_URL
        val ds = DataStoreUtils.getInstance(applicationContext)
        val root = applicationContext.getExternalFilesDir(null) ?: return Result.failure()
        // No base, no overlay: the startup gate owns the full download.
        if (!File(root, MapTileCache.BASEMAP_ARCHIVE_FILE).exists()) {
            Log.status(TAG, "no base archive; skipping overlay check")
            return Result.success()
        }
        val text = fetchText(manifestUrl) ?: return Result.retry()
        val manifest = MapOverlay.parse(text)
        if (manifest == null) {
            Log.status(TAG, "manifest unparseable or too new; skipping")
            return Result.success()
        }
        ds.setString(KEY_LAST_MANIFEST, text.take(MANIFEST_TEXT_CAP))
        ds.setLong(KEY_LAST_CHECK, System.currentTimeMillis())
        val pinned = MapOverlay.pinned(root)
        if (pinned != null && pinned.overlaySeq >= manifest.overlaySeq) {
            Log.status(TAG, "overlay seq ${manifest.overlaySeq} already applied")
            return Result.success()
        }
        // Base mismatch refuses: an overlay for another build must never apply.
        if (!baseMatches(root, manifest)) {
            Log.status(TAG, "overlay seq ${manifest.overlaySeq} is for another base; skipping")
            return Result.success()
        }
        val missing = MapOverlay.missing(root, manifest)
        if (missing.isEmpty()) {
            MapOverlay.pin(root, text)
            ds.setLong(KEY_APPLIED_SEQ, manifest.overlaySeq)
            return Result.success()
        }
        ds.setLong(KEY_PENDING_BYTES, missing.sumOf { it.len })
        val items = missing.map { seg ->
            val file = MapOverlay.segmentFile(root, manifest.overlaySeq, seg)
            val rel = root.toURI().relativize(file.toURI()).path
            ModelDownloadItem(
                url = seg.url,
                fileName = rel,
                description = "Map update ${manifest.overlaySeq} part ${seg.seq + 1}/${manifest.segments.size}",
                sha256 = seg.sha256,
            )
        }
        // Sequential, one segment at a time: peak transient is one segment.
        var ok = true
        for (item in items) {
            if (!downloadModels(applicationContext, ds, listOf(item))) {
                ok = false
                break
            }
        }
        if (!ok) return Result.retry()
        if (!MapOverlay.isApplied(root, manifest)) {
            Log.status(TAG, "segments landed but failed verification")
            return Result.retry()
        }
        MapOverlay.pin(root, text)
        ds.setLong(KEY_APPLIED_SEQ, manifest.overlaySeq)
        ds.setLong(KEY_PENDING_BYTES, 0L)
        Log.status(TAG, "overlay seq ${manifest.overlaySeq} stored (${items.size} segments)")
        return Result.success()
    }

    /**
     * Whether the local base is the build [manifest] was made for. The base
     * header's `build_id` lives at bytes 16..24 (little-endian u64); the
     * manifest pins it as hex. A missing/unreadable header refuses.
     */
    private fun baseMatches(root: File, manifest: MapOverlay.Manifest): Boolean {
        if (manifest.baseLen > 0) {
            val len = File(root, MapTileCache.BASEMAP_ARCHIVE_FILE).length()
            if (len != manifest.baseLen) {
                Log.status(TAG, "base len $len != manifest ${manifest.baseLen}")
                return false
            }
        }
        val id = readBaseBuildId(File(root, MapTileCache.BASEMAP_ARCHIVE_FILE)) ?: return false
        if (id != manifest.baseBuildId) {
            Log.status(TAG, "base build ${id.toString(16)} != manifest ${manifest.baseBuildId.toString(16)}")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "MapOverlay"
        private const val KEY_MANIFEST_URL = "manifest_url"
        /** DataStore keys the Settings row reads (last check text is capped). */
        const val KEY_LAST_CHECK = "overlay_last_check"
        const val KEY_APPLIED_SEQ = "overlay_applied_seq"
        const val KEY_PENDING_BYTES = "overlay_pending_bytes"
        const val KEY_LAST_MANIFEST = "overlay_last_manifest"
        private const val MANIFEST_TEXT_CAP = 8 * 1024

        private const val MAX_RUN_ATTEMPTS = 6
        private const val BACKOFF_SECONDS = 30L

        /** Periodic auto check: UNMETERED only (multi-hundred-MB segments). */
        fun checkAuto(context: Context) =
            enqueue(context, MapOverlay.MANIFEST_URL, requireUnmetered = true)

        /** Manual check from Settings: any connectivity. */
        fun checkManual(context: Context) =
            enqueue(context, MapOverlay.MANIFEST_URL, requireUnmetered = false)

        private fun enqueue(context: Context, manifestUrl: String, requireUnmetered: Boolean) {
            val request = OneTimeWorkRequestBuilder<MapOverlayWorker>()
                .setInputData(workDataOf(KEY_MANIFEST_URL to manifestUrl))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        )
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (requireUnmetered) UNIQUE_AUTO else UNIQUE_MANUAL,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private const val UNIQUE_AUTO = "map_overlay_auto"
        private const val UNIQUE_MANUAL = "map_overlay_manual"

        /** Attempt count guard lives in doWork's caller: WorkManager retries. */
        fun maxAttempts(): Int = MAX_RUN_ATTEMPTS
    }
}

/** GET [url] as text, or null on any failure (caller retries). */
private suspend fun fetchText(url: String): String? = runCatching {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        (URL(url).openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 30_000
            readTimeout = 30_000
            requestMethod = "GET"
            try {
                connect()
                if (responseCode != java.net.HttpURLConnection.HTTP_OK) return@run null
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }
}.getOrNull()

/** The base archive's header `build_id` (bytes 16..24 LE), or null. */
private fun readBaseBuildId(file: File): Long? = runCatching {
    if (!file.isFile || file.length() < 24) return null
    java.io.RandomAccessFile(file, "r").use { raf ->
        raf.seek(16)
        val buf = ByteArray(8)
        raf.readFully(buf)
        java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
    }
}.getOrNull()
