package org.tensorflow.lite.examples.soundclassifier;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

public class LocationHelper {
    private static final String TAG = "LocationHelper";
    private static Location oldLocation;
    private static long oldLocationTime = 0;
    private static Location preciseLocation;
    private static LocationListener locationListenerGPS;
    static {
        preciseLocation = new Location("GPS");
        preciseLocation.setLatitude(0.0f);
        preciseLocation.setLongitude(0.0f);
    }

    static void stopLocation(Context context){
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationListenerGPS!=null) locationManager.removeUpdates(locationListenerGPS);
        locationListenerGPS=null;
    }

    static void requestLocation(Context context, SoundClassifier soundClassifier) {

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (sharedPreferences.getBoolean("manual_location", false)){
            String manualLocation = sharedPreferences.getString("manual_location_value", "0.000/0.000");
            String lat = manualLocation.split("/")[0];
            String lon = manualLocation.split("/")[1];
            // 0/0 is the never-configured placeholder — treat "manual location on but unset"
            // as misconfiguration and fall through to GPS instead of silently doing nothing.
            if (Double.parseDouble(lat) == 0.0 && Double.parseDouble(lon) == 0.0) {
                Log.w(TAG, "manual_location enabled but value is unset (0/0) — falling back to GPS");
            } else {
                preciseLocation = new Location("GPS");
                preciseLocation.setLatitude(Double.parseDouble(lat));
                preciseLocation.setLongitude(Double.parseDouble(lon));
                oldLocation = preciseLocation;
                soundClassifier.runMetaInterpreter(oldLocation);
                oldLocationTime = 0;
                return;
            }
        }

        if (System.currentTimeMillis() - oldLocationTime > 3 * 60 * 1000) {oldLocation = null; oldLocationTime = 0;}  //location older than 3 min -> reset
        else soundClassifier.runMetaInterpreter(oldLocation);

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "location permission not granted — cannot request fixes");
            return;
        }
        if (checkLocationProvider(context)) {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            Log.i(TAG, "providers: gps=" + locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    + " network=" + locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));

            // Seed from the freshest cached fix so the location filter is active immediately
            // instead of waiting minutes for a cold GPS fix (or forever, indoors).
            if (oldLocation == null) {
                Location seed = null;
                for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    try {
                        Location l = locationManager.getLastKnownLocation(provider);
                        Log.i(TAG, "getLastKnownLocation(" + provider + ") = " + (l == null ? "null" : (l.getTime() + " age " + (System.currentTimeMillis() - l.getTime()) / 1000 + "s")));
                        if (l != null && (seed == null || l.getTime() > seed.getTime())) seed = l;
                    } catch (Exception e) {
                        Log.w(TAG, "getLastKnownLocation(" + provider + ") threw: " + e);
                    }
                }
                if (seed != null) {
                    Log.i(TAG, "seeding location filter from cached " + seed.getProvider() + " fix");
                    preciseLocation = seed;
                    Location roundLoc = new Location(seed);
                    roundLoc.setLatitude(Math.round(seed.getLatitude() * 100.0) / 100.0);
                    roundLoc.setLongitude(Math.round(seed.getLongitude() * 100.0) / 100.0);
                    oldLocation = roundLoc;
                    oldLocationTime = System.currentTimeMillis();
                    soundClassifier.runMetaInterpreter(roundLoc);
                }
            }

            if (locationListenerGPS==null) locationListenerGPS = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    preciseLocation = location;
                    Location roundLoc = new Location(location);
                    roundLoc.setLatitude(Math.round(location.getLatitude() * 100.0) / 100.0);
                    roundLoc.setLongitude(Math.round(location.getLongitude() * 100.0) / 100.0);
                    if (oldLocation == null ||
                            (roundLoc.getLatitude() != oldLocation.getLatitude()) ||
                            (roundLoc.getLongitude() != oldLocation.getLongitude())){

                        oldLocation = roundLoc;
                        oldLocationTime = System.currentTimeMillis();
                        soundClassifier.runMetaInterpreter(roundLoc);
                    }
                }

                @Deprecated
                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0, locationListenerGPS);
            // Network provider delivers a (coarser) fix much faster and works indoors —
            // plenty for the meta model, which rounds to ~1 km anyway. A later GPS fix
            // simply re-runs the meta model with better coordinates.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 0, locationListenerGPS);
        }
    }

    public static boolean checkLocationProvider(Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
            Toast.makeText(context, "Error no GPS", Toast.LENGTH_SHORT).show();
            return false;
        } else {
            return true;
        }
    }

    public static Location getPreciseLocation(){
        return preciseLocation;
    }
}