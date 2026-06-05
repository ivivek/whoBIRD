/*
 * Copyright 2020 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modifications by woheller69

package org.tensorflow.lite.examples.soundclassifier

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.material.slider.LabelFormatter.LABEL_GONE
import org.tensorflow.lite.examples.soundclassifier.databinding.ActivityMainBinding
import org.woheller69.freeDroidWarn.FreeDroidWarn

class MainActivity : BaseActivity() {

  private var soundClassifier: SoundClassifier? = null
  private var birdNetService: BirdNETService? = null
  private var serviceBound: Boolean = false
  private lateinit var binding: ActivityMainBinding

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val localBinder = service as BirdNETService.LocalBinder
      birdNetService = localBinder.getService()
      soundClassifier = birdNetService?.soundClassifier
      soundClassifier?.attachBinding(binding)
      // Reflect current running state in the FAB / progress bar.
      val running = !(soundClassifier?.isPaused ?: false)
      binding.progressHorizontal.setIndeterminate(running)
      binding.fab.setImageDrawable(
        ContextCompat.getDrawable(
          this@MainActivity,
          if (running) R.drawable.ic_pause_24dp else R.drawable.ic_record_24dp
        )
      )
      // Kick GPS now that we have the classifier reference.
      LocationHelper.requestLocation(this@MainActivity, soundClassifier!!)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      soundClassifier?.detachBinding()
      soundClassifier = null
      birdNetService = null
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    //On Android 15, both BottomAppBar and BottomNavigationView automatically register WindowInsetsListeners that:
    //Add extra padding for gesture navigation areas
    //Increase the minimum height to accommodate system bars
    //Modify layout behavior to "avoid" system UI (but overdo it)
    //We do not need that, therefore set them "null"
    binding.bottomAppBar.setOnApplyWindowInsetsListener(null)
    binding.bottomNavigationView.setOnApplyWindowInsetsListener(null)

    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)

    //Set aspect ratio for webview and icon
    val width = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val windowMetrics = windowManager.currentWindowMetrics
      windowMetrics.bounds.width()
    } else {
      val displayMetrics = DisplayMetrics()
      windowManager.defaultDisplay.getMetrics(displayMetrics)
      displayMetrics.widthPixels
    }
    val paramsWebview: ViewGroup.LayoutParams = binding.webview.getLayoutParams() as ViewGroup.LayoutParams
    paramsWebview.height = (width / 1.8f).toInt()
    val paramsIcon: ViewGroup.LayoutParams = binding.icon.getLayoutParams() as ViewGroup.LayoutParams
    paramsIcon.height = (width / 1.8f).toInt()

    binding.rangeSlider.labelBehavior = LABEL_GONE

    binding.gps.setText(getString(R.string.latitude)+": --.-- / " + getString(R.string.longitude) + ": --.--" )
    binding.webview.setWebViewClient(object : MlWebViewClient(this) {})
    binding.webview.settings.setDomStorageEnabled(true)
    binding.webview.settings.setJavaScriptEnabled(true)

    binding.fab.setOnClickListener {
      if (binding.progressHorizontal.isIndeterminate) {
        binding.progressHorizontal.setIndeterminate(false)
        binding.fab.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_record_24dp))
        soundClassifier?.isPaused = true
        // Drop the persistent notification + foreground state — the service stays alive
        // (started, bound), it just stops claiming a status-bar slot. Mic is already released.
        birdNetService?.setForegroundActive(false)
        if (binding.icon.visibility == View.VISIBLE && sharedPref.getBoolean("show_spectrogram", false)){
          binding.rangeSlider.visibility = View.VISIBLE
          binding.runRecognizerButton.visibility = View.VISIBLE
          binding.resetButton.visibility = View.VISIBLE
          binding.rangeSlider.values = mutableListOf(0.0f, 100.0f)
        }
      }
      else {
        binding.progressHorizontal.setIndeterminate(true)
        binding.fab.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_pause_24dp))
        // Re-enter foreground BEFORE the classifier reopens AudioRecord, so the
        // microphone access is properly attributed to a foreground service.
        birdNetService?.setForegroundActive(true)
        soundClassifier?.isPaused = false
        binding.rangeSlider.visibility = View.GONE
        binding.runRecognizerButton.visibility = View.GONE
        binding.resetButton.visibility = View.GONE
      }
    }
    binding.bottomNavigationView.setOnItemSelectedListener { item ->
      when (item.itemId) {
        R.id.action_view -> {
          intent = Intent(this, ViewActivity::class.java)
          startActivity(intent)
        }
        R.id.action_bird_info -> {
          intent = Intent(this, BirdInfoActivity::class.java)
          startActivity(intent)
        }
        R.id.action_settings -> {
          intent = Intent(this, SettingsActivity::class.java)
          startActivity(intent)
        }
      }
      true
    }



    val metaModelInfluence = sharedPref.getFloat("meta_model_influence", 60.0f)
    binding.metaInfluenceSlider.value = metaModelInfluence
    binding.metaInfluenceSlider.addOnChangeListener { slider, value, fromUser ->
      val editor=sharedPref.edit()
      editor.putFloat("meta_model_influence", value)
      editor.apply()
      soundClassifier?.setMetaInfluence(value)
    }
    FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE)
    if (GithubStar.shouldShowStarDialog(this)) GithubStar.starDialog(this, "https://github.com/ivivek/whoBIRD")

    requestPermissions()

  }

  override fun onStart() {
    super.onStart()
    if (checkMicrophonePermission()) {
      val svcIntent = Intent(this, BirdNETService::class.java)
      // Use plain startService — activity is in foreground here so this is allowed.
      // The service decides its own foreground state via setForegroundActive() so a
      // "paused" service doesn't get force-promoted back to foreground (and the notification
      // shown again) just because the user opened the app.
      startService(svcIntent)
      bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
      serviceBound = true
    }
  }

  override fun onStop() {
    super.onStop()
    if (serviceBound) {
      soundClassifier?.detachBinding()
      unbindService(serviceConnection)
      serviceBound = false
    }
  }

  override fun onResume() {
    super.onResume()
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
    if (sharedPref.getBoolean("bluetooth", false)){
      audioManager.startBluetoothSco()
      audioManager.isBluetoothScoOn = true
    } else {
      audioManager.stopBluetoothSco()
      audioManager.isBluetoothScoOn = false
    }

    soundClassifier?.let { LocationHelper.requestLocation(this, it) }
    if (!checkLocationPermission()){
      Toast.makeText(this, this.resources.getString(R.string.error_location_permission), Toast.LENGTH_SHORT).show()
    }
    if (!checkMicrophonePermission()){
      Toast.makeText(this, this.resources.getString(R.string.error_audio_permission), Toast.LENGTH_SHORT).show()
    }
    keepScreenOn(true)
  }

  override fun onPause() {
    super.onPause()
    LocationHelper.stopLocation(this)
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.stopBluetoothSco()
    audioManager.isBluetoothScoOn = false
  }

  private fun checkMicrophonePermission(): Boolean {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO ) == PackageManager.PERMISSION_GRANTED) {
      return true
    } else {
      return false
    }
  }

  private fun checkLocationPermission(): Boolean {
    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
    if (sharedPref.getBoolean("manual_location", false) || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      return true
    } else {
      return false
    }
  }

  private fun requestPermissions() {
    val perms = mutableListOf<String>()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      perms.add(Manifest.permission.RECORD_AUDIO)
    }

    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)

    if (!sharedPref.getBoolean("manual_location", false)
      && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    if (sharedPref.getBoolean("bluetooth", false)
      && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
      && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
      && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        perms.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (!perms.isEmpty()) requestPermissions(perms.toTypedArray(), REQUEST_PERMISSIONS)
  }

  private fun keepScreenOn(enable: Boolean) =
    if (enable) {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

  companion object {
    const val REQUEST_PERMISSIONS = 1337
  }

  fun reload(view: View) {
    binding.webview.settings.setCacheMode(WebSettings.LOAD_DEFAULT)
    binding.webview.loadUrl(binding.webviewUrl.text.toString())
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    val inflater = menuInflater
    inflater.inflate(R.menu.main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.action_share_app -> {
        val intent = Intent(Intent.ACTION_SEND)
        val shareBody = "https://github.com/ivivek/whoBIRD"
        intent.setType("text/plain")
        intent.putExtra(Intent.EXTRA_TEXT, shareBody)
        startActivity(Intent.createChooser(intent, ""))
        return true
      }
      R.id.action_info -> {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ivivek/whoBIRD")))
        return true
      }
      else -> return super.onOptionsItemSelected(item)
    }
  }

  fun runRecognizer(view: View) {
    val classifier = soundClassifier ?: return
    if (view == binding.resetButton) binding.rangeSlider.values = mutableListOf(0.0f, 100.0f)

    binding.text1.setText("")
    binding.text1.setBackgroundResource(0)
    binding.text2.setText("")
    binding.text2.setBackgroundResource(0)

    val buffer = classifier.getInputBufferSnapshot()
    val N: Int = buffer.capacity()
    // Ensure minPercentage <= maxPercentage
    val currentValues = binding.rangeSlider.values
    val minPercentage = currentValues[0]
    val maxPercentage = currentValues[1]

    // Calculate index bounds
    val lowerIndex = Math.floor(minPercentage / 100.0 * N).toInt()
    var upperIndex = Math.floor(maxPercentage / 100.0 * N).toInt()
    upperIndex = Math.min(upperIndex, N) // Clamp to buffer size

    // Zero out values outside the range
    for (i in 0 until N) {
      if (i < lowerIndex || i >= upperIndex) {
        buffer.put(i, 0.0f)
      }
    }
    classifier.recognizeAndDisplay(buffer)
  }

}
