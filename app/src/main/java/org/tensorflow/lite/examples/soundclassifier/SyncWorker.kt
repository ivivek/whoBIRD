package org.tensorflow.lite.examples.soundclassifier

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.scheduleAtFixedRate

/**
 * Pushes unsynced bird detections from the local SQLite DB to a Raspberry Pi over HTTP.
 *
 * Runs inside BirdNETService. One worker, one timer, settings re-read each tick so the user can
 * toggle sync / change the URL without restarting the service.
 *
 * Wire protocol — POST {pi_url}:
 *   { "station_id": "phone-1", "detections": [
 *       { "client_id": 42, "ts_millis": 1717..., "species": "Eurasian Blackbird",
 *         "species_id": 123, "confidence": 0.84, "lat": 12.97, "lon": 77.59 }, ... ] }
 *
 * The Pi is expected to be idempotent on (station_id, client_id) — we may retry the same batch
 * if a response is lost mid-flight.
 */
class SyncWorker(private val context: Context) {

  private val db: BirdDBHelper = BirdDBHelper.getInstance(context)
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

  private var timer: Timer? = null
  private var consecutiveFailures: Int = 0

  fun start() {
    if (timer != null) return
    timer = Timer("BirdNETSync").apply {
      // Initial 5s delay so the service is fully up; then re-evaluate interval each tick.
      scheduleAtFixedRate(5_000L, 5_000L) { tick() }
    }
    Log.i(TAG, "SyncWorker started")
  }

  fun stop() {
    timer?.cancel()
    timer = null
    Log.i(TAG, "SyncWorker stopped")
  }

  private fun tick() {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    if (!prefs.getBoolean("sync_enabled", false)) return

    val url = prefs.getString("sync_pi_url", "")?.trim().orEmpty()
    if (url.isEmpty()) return

    val intervalMs = ((prefs.getString("sync_interval_sec", "30") ?: "30").toIntOrNull() ?: 30) * 1_000L
    val now = System.currentTimeMillis()

    // Wait until the configured interval has elapsed, or longer if we're backing off after failures.
    val backoffMs = if (consecutiveFailures > 0) {
      (1L shl consecutiveFailures.coerceAtMost(6)) * 5_000L  // 5s..320s
    } else 0L
    val minWait = maxOf(intervalMs, backoffMs)
    if (now - lastRunMs < minWait) return
    lastRunMs = now

    val stationId = prefs.getString("sync_station_id", "phone-1") ?: "phone-1"
    val batch = db.getUnsyncedBatch(BATCH_SIZE)
    if (batch.isEmpty()) {
      consecutiveFailures = 0
      uploadPendingClips(url, stationId)
      return
    }

    val payload = JSONObject().apply {
      put("station_id", stationId)
      val arr = JSONArray()
      for (o in batch) {
        arr.put(JSONObject().apply {
          put("client_id", o.id)
          put("ts_millis", o.millis)
          put("species", o.name)
          put("species_id", o.speciesId)
          put("confidence", o.probability)
          put("lat", o.latitude)
          put("lon", o.longitude)
        })
      }
      put("detections", arr)
    }.toString()

    val body = payload.toRequestBody(JSON_MEDIA)
    val req = Request.Builder().url(url).post(body).build()
    try {
      http.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) {
          val ids = batch.map { it.id }
          db.markSynced(ids)
          consecutiveFailures = 0
          Log.i(TAG, "Synced ${ids.size} rows to $url")
          uploadPendingClips(url, stationId)
        } else {
          consecutiveFailures++
          Log.w(TAG, "Sync HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
        }
      }
    } catch (e: Exception) {
      consecutiveFailures++
      Log.w(TAG, "Sync failed: ${e.message}")
    }
  }

  /**
   * Upload WAV clips for rows whose metadata is already on the receiver. Each row is handled
   * exactly once: if its clip exists in Music/whoBIRD/<ts_millis>.wav it is PUT to the
   * receiver; if no file was written (write_wav off) the row is marked done and skipped
   * forever. Bounded per tick so a backlog can't stall the sync timer; stops on the first
   * network failure and retries next tick.
   */
  private fun uploadPendingClips(syncUrl: String, stationId: String) {
    // Derive the clip endpoint from the configured detections URL.
    val base = syncUrl.removeSuffix("/api/detections")
    if (base == syncUrl) return  // custom path we don't understand — clips not supported

    val pending = db.getClipPendingBatch(CLIP_BATCH_SIZE)
    if (pending.isEmpty()) return

    val wavDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "whoBIRD")
    val done = mutableListOf<Int>()
    var uploaded = 0
    for (o in pending) {
      val wav = File(wavDir, "${o.millis}.wav")
      if (!wav.exists()) {
        done.add(o.id)  // no clip was written for this detection — never look again
        continue
      }
      val req = Request.Builder()
        .url("$base/api/clip/$stationId/${o.millis}")
        .post(wav.asRequestBody(WAV_MEDIA))
        .build()
      var abort = false
      try {
        http.newCall(req).execute().use { resp ->
          if (resp.isSuccessful) {
            done.add(o.id)
            uploaded++
          } else {
            Log.w(TAG, "Clip upload HTTP ${resp.code} for ${o.millis}")
            abort = true  // server-side problem: stop this tick, retry remaining next tick
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Clip upload failed: ${e.message}")
        abort = true
      }
      if (abort) break
    }
    db.markClipSynced(done)
    if (uploaded > 0) Log.i(TAG, "Uploaded $uploaded clips")
  }

  private var lastRunMs: Long = 0L

  companion object {
    private const val TAG = "SyncWorker"
    private const val BATCH_SIZE = 200
    private const val CLIP_BATCH_SIZE = 5  // ~300 KB each; bounded work per 30s tick
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val WAV_MEDIA = "audio/wav".toMediaType()
  }
}
