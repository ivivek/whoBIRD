package org.tensorflow.lite.examples.soundclassifier

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File

/**
 * Size cap for the WAV clip folder (Music/birdroid). With sync on, the receiver is the archive
 * and the phone only needs to hold clips until they upload — without a cap an unattended
 * station fills its storage, after which every clip write fails silently.
 *
 * Eviction is oldest first, in two passes: clips that are already uploaded (or whose rows are
 * gone) go first; clips still waiting to upload are only touched if that isn't enough, i.e. the
 * receiver has been unreachable long enough for the backlog alone to exceed the cap. Deleting a
 * pending clip is safe for the sync queue: SyncWorker finds the file missing and marks the row
 * done.
 */
object ClipCache {
  private const val TAG = "ClipCache"
  const val PREF_CAP_MB = "clip_cache_mb"
  private const val DEFAULT_CAP_MB = 500L

  class Usage(val files: Int, val bytes: Long, val pendingFiles: Int, val pendingBytes: Long)

  fun dir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "birdroid")

  private fun capBytes(context: Context): Long {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val mb = prefs.getString(PREF_CAP_MB, null)?.toLongOrNull() ?: DEFAULT_CAP_MB
    return mb * 1024 * 1024
  }

  /** Clips on disk, oldest first (names are <ts_millis>.wav). */
  private fun clips(): List<File> =
    dir().listFiles { f -> f.isFile && f.name.endsWith(".wav") }
      ?.sortedBy { millis(it) } ?: emptyList()

  private fun millis(f: File): Long = f.nameWithoutExtension.toLongOrNull() ?: 0L

  private fun pendingMillis(context: Context): Set<Long> =
    BirdDBHelper.getInstance(context).clipPendingMillis

  /** True while a trim or clear is deleting; deletes go through MediaProvider at ~10 ms each,
   *  so a first trim over a large backlog runs for minutes. */
  @Volatile
  var busy: Boolean = false
    private set

  // Not synchronized: a read-only count must not wait minutes behind a running trim.
  // A file deleted mid-count just reads as 0 bytes.
  fun usage(context: Context): Usage {
    val pending = pendingMillis(context)
    var bytes = 0L; var pendingFiles = 0; var pendingBytes = 0L
    val files = clips()
    for (f in files) {
      val len = f.length()
      bytes += len
      if (millis(f) in pending) { pendingFiles++; pendingBytes += len }
    }
    return Usage(files.size, bytes, pendingFiles, pendingBytes)
  }

  /**
   * Deletes oldest clips until the folder fits the cap — the saved setting, or [capMb] when the
   * caller has a value that isn't persisted yet. Returns clips deleted.
   */
  @Synchronized
  @JvmOverloads
  fun trim(context: Context, capMb: Long? = null): Int {
    val cap = capMb?.let { it * 1024 * 1024 } ?: capBytes(context)
    val files = clips()
    var total = files.sumOf { it.length() }
    if (total <= cap) return 0

    val pending = pendingMillis(context)
    var deleted = 0
    var deletedPending = 0
    busy = true
    try {
      for (pendingPass in listOf(false, true)) {
        for (f in files) {
          if (total <= cap) break
          val isPending = millis(f) in pending
          if (isPending != pendingPass) continue
          val len = f.length()
          if (f.delete()) {
            total -= len
            deleted++
            if (isPending) deletedPending++
          }
        }
      }
    } finally {
      busy = false
    }
    Log.i(TAG, "Trimmed $deleted clips to fit ${cap / (1024 * 1024)} MB")
    if (deletedPending > 0) Log.w(TAG, "$deletedPending of them were never uploaded")
    return deleted
  }

  /** "Clear" button: uploaded clips, plus the not-yet-uploaded ones if [includePending]. */
  @Synchronized
  fun clear(context: Context, includePending: Boolean): Int {
    val pending = if (includePending) emptySet() else pendingMillis(context)
    var deleted = 0
    busy = true
    try {
      for (f in clips()) {
        if (millis(f) in pending) continue
        if (f.delete()) deleted++
      }
    } finally {
      busy = false
    }
    Log.i(TAG, "Cleared $deleted clips (includePending=$includePending)")
    return deleted
  }
}
