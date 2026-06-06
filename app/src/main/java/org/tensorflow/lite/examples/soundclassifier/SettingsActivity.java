package org.tensorflow.lite.examples.soundclassifier;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.woheller69.preferences.EditTextSwitchPreference;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SettingsActivity extends BaseActivity {
Context mContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
        BottomAppBar bottomAppBar = findViewById(R.id.bottomAppBar);
        bottomAppBar.setOnApplyWindowInsetsListener(null);
        BottomNavigationView navigationView = findViewById(R.id.bottomNavigationView);
        navigationView.setOnApplyWindowInsetsListener(null);
        navigationView.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                if (item.getItemId()==R.id.action_bird_info){
                    Intent intent = new Intent(mContext, BirdInfoActivity.class);
                    startActivity(intent);
                } else if (item.getItemId()==R.id.action_mic){
                    Intent intent = new Intent(mContext, MainActivity.class);
                    startActivity(intent);
                } else if (item.getItemId()==R.id.action_view){
                    Intent intent = new Intent(mContext, ViewActivity.class);
                    startActivity(intent);
                }
                return true;
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();
    }



    public static class SettingsFragment extends PreferenceFragmentCompat {

        // QR pairing: the dashboard's "pair a phone" code encodes
        // birdroid://setup?url=<sync endpoint>[&station=<id>]. The embedded
        // scanner handles the camera permission prompt itself.
        private final ActivityResultLauncher<ScanOptions> qrScanLauncher =
                registerForActivityResult(new ScanContract(), result -> {
                    if (result.getContents() != null) handlePairScan(result.getContents());
                });

        private void handlePairScan(String contents) {
            Uri uri = Uri.parse(contents);
            String url = "birdroid".equals(uri.getScheme()) && "setup".equals(uri.getAuthority())
                    ? uri.getQueryParameter("url") : null;
            if (url == null || okhttp3.HttpUrl.parse(url) == null) {
                Toast.makeText(requireContext(), R.string.sync_pair_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            String station = uri.getQueryParameter("station");
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.sync_pair_title)
                    .setMessage(getString(R.string.sync_pair_message, url))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                        SharedPreferences.Editor edit = prefs.edit()
                                .putString("sync_pi_url", url)
                                .putBoolean("sync_enabled", true);
                        if (station != null && !station.trim().isEmpty())
                            edit.putString("sync_station_id", station.trim());
                        edit.apply();
                        onCreatePreferences(null, null);  // rebuild so the new values show
                        Toast.makeText(requireContext(), R.string.sync_pair_done, Toast.LENGTH_SHORT).show();
                        // Prove the pairing end-to-end right away.
                        runSyncTest(requireContext().getApplicationContext(), url,
                                prefs.getString("sync_station_id", "phone-1"));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            PreferenceScreen preferenceScreen = getPreferenceScreen();
            Preference writeWav = getPreferenceManager().findPreference("write_wav");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) preferenceScreen.removePreference(writeWav);

            Preference reset = getPreferenceManager().findPreference("reset");

            if (reset != null) reset.setOnPreferenceClickListener(preference -> {
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

                sharedPreferences.edit().remove("audio_source").apply();
                sharedPreferences.edit().remove("high_pass").apply();
                sharedPreferences.edit().remove("model_threshold").apply();
                sharedPreferences.edit().remove("play_sound").apply();
                sharedPreferences.edit().remove("write_wav").apply();
                sharedPreferences.edit().remove("theme").apply();
                sharedPreferences.edit().remove("bluetooth").apply();

                onCreatePreferences(savedInstanceState,rootKey);
                return false;
            });

            Preference theme = findPreference("theme");
            theme.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
                    requireActivity().recreate();
                    return true;
                }
            });
            Preference language = getPreferenceManager().findPreference("language");
            if (language != null) language.setOnPreferenceClickListener(preference -> {
                // Create an intent to open the app's settings
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.setData(Uri.parse("package:" + getActivity().getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                startActivity(intent);
                return true; // Return true to indicate that the click event has been handled

            });
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) preferenceScreen.removePreference(language);

            SwitchPreferenceCompat showSpectrogramPref = findPreference("show_spectrogram");
            SwitchPreferenceCompat showImagesPref = findPreference("show_images");
            showSpectrogramPref.setOnPreferenceChangeListener((preference, newValue) -> {
                if ((Boolean) newValue) {
                    // If show_spectrogram is turned on, turn off show_images
                    showImagesPref.setChecked(false);
                }
                return true; // Allow the change
            });
            showImagesPref.setOnPreferenceChangeListener((preference, newValue) -> {
                if ((Boolean) newValue) {
                    // If show_images is turned on, turn off show_spectrogram
                    showSpectrogramPref.setChecked(false);
                }
                return true; // Allow the change
            });

            Preference syncPair = getPreferenceManager().findPreference("sync_pair");
            if (syncPair != null) syncPair.setOnPreferenceClickListener(preference -> {
                qrScanLauncher.launch(new ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(getString(R.string.sync_pair_prompt))
                        .setBeepEnabled(false)
                        .setCaptureActivity(PortraitCaptureActivity.class)
                        // false = don't re-lock to the sensor; the manifest pins portrait
                        .setOrientationLocked(false));
                return true;
            });

            Preference syncTest = getPreferenceManager().findPreference("sync_test");
            if (syncTest != null) syncTest.setOnPreferenceClickListener(preference -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                String url = prefs.getString("sync_pi_url", "").trim();
                if (url.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.sync_test_no_url, Toast.LENGTH_SHORT).show();
                    return true;
                }
                runSyncTest(requireContext().getApplicationContext(), url,
                        prefs.getString("sync_station_id", "phone-1"));
                return true;
            });

            EditTextSwitchPreference manualLocationValue = findPreference("manual_location_value");
            manualLocationValue.setOnPreferenceChangeListener((preference, newValue) -> {
                String newVal = newValue.toString();
                if (isValidGPSFormat(newVal) && isValidGPSRange(newVal)) {
                    return true;
                } else {
                    Toast.makeText(requireContext(),
                            requireContext().getString(R.string.error_invalid_GPS), Toast.LENGTH_SHORT).show();
                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
                    sharedPreferences.edit().remove("manual_location_value").apply();
                    manualLocationValue.setText("0.000/0.000");
                    return false;
                }
            });

        }

        /** Fires an empty batch at the receiver and toasts the outcome. Used by "Test
         *  connection" and after QR pairing. The empty batch proves reachability and that
         *  the server answers the sync protocol (response carries "inserted") without
         *  writing any rows. */
        private static void runSyncTest(Context appContext, String url, String stationId) {
            Toast.makeText(appContext, R.string.sync_test_running, Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String message;
                try {
                    OkHttpClient http = new OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(5, TimeUnit.SECONDS)
                            .build();
                    String payload = new JSONObject()
                            .put("station_id", stationId)
                            .put("detections", new JSONArray())
                            .toString();
                    Request req = new Request.Builder()
                            .url(url)
                            .post(RequestBody.create(payload, MediaType.get("application/json; charset=utf-8")))
                            .build();
                    try (Response resp = http.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        if (resp.isSuccessful() && body.contains("\"inserted\"")) {
                            message = appContext.getString(R.string.sync_test_ok);
                        } else if (resp.isSuccessful()) {
                            message = appContext.getString(R.string.sync_test_wrong_server, resp.code());
                        } else {
                            message = appContext.getString(R.string.sync_test_http_error, resp.code());
                        }
                    }
                } catch (Exception e) {
                    message = appContext.getString(R.string.sync_test_failed, e.getMessage());
                }
                String finalMessage = message;
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(appContext, finalMessage, Toast.LENGTH_LONG).show());
            }).start();
        }

        private boolean isValidGPSFormat(String value) {
            if (value == null || value.isEmpty()) return false;
            return value.matches("^-?\\d+(\\.\\d+)?/-?\\d+(\\.\\d+)?$");
        }

        private boolean isValidGPSRange(String value) {
            try {
                String[] parts = value.split("/");
                double lat = Double.parseDouble(parts[0]);
                double lon = Double.parseDouble(parts[1]);
                return lat >= -90 && lat <= 90 &&
                        lon >= -180 && lon <= 180;
            } catch (Exception e) {
                return false;
            }
        }
    }
}