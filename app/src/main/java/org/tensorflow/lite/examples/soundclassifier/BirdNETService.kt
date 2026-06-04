package org.tensorflow.lite.examples.soundclassifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * ForegroundService that owns the SoundClassifier so detection keeps running with the screen off.
 *
 * MainActivity binds to this service to attach its ActivityMainBinding for live UI updates.
 * When the activity unbinds (e.g. backgrounded), the classifier keeps inferring + writing to DB
 * headless. The service is started with startForegroundService() so it survives the activity.
 */
class BirdNETService : Service() {

  inner class LocalBinder : Binder() {
    fun getService(): BirdNETService = this@BirdNETService
  }

  private val binder = LocalBinder()
  private var wakeLock: PowerManager.WakeLock? = null

  /** The classifier instance — outlives the activity. */
  lateinit var soundClassifier: SoundClassifier
    private set

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "BirdNETService onCreate")

    soundClassifier = SoundClassifier(applicationContext, SoundClassifier.Options())

    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "birdnet:listening").apply {
      setReferenceCounted(false)
      acquire()
    }

    startInForeground()
    soundClassifier.start()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // If killed by the system, restart so detection resumes.
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onDestroy() {
    Log.i(TAG, "BirdNETService onDestroy")
    if (::soundClassifier.isInitialized && soundClassifier.isRecording) {
      soundClassifier.stop()
    }
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    super.onDestroy()
  }

  private fun startInForeground() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel_listening),
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = getString(R.string.notification_channel_listening_desc)
        setShowBadge(false)
      }
      nm.createNotificationChannel(channel)
    }

    val openAppIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    val contentPi = PendingIntent.getActivity(this, 0, openAppIntent, piFlags)

    val notification: Notification = Notification.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(R.string.notification_listening_title))
      .setContentText(getString(R.string.notification_listening_text))
      .setSmallIcon(R.drawable.ic_record_24dp)
      .setContentIntent(contentPi)
      .setOngoing(true)
      .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  companion object {
    private const val TAG = "BirdNETService"
    const val CHANNEL_ID = "birdnet_listening"
    const val NOTIFICATION_ID = 1001
  }
}
