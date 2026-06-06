package org.tensorflow.lite.examples.soundclassifier;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * zxing-android-embedded's stock CaptureActivity is landscape-locked in the library
 * manifest. This empty subclass exists only so our manifest can pin the QR pairing
 * scanner to portrait like every other screen in the app.
 */
public class PortraitCaptureActivity extends CaptureActivity {
}
