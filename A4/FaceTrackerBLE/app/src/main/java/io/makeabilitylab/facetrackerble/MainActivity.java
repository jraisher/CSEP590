package io.makeabilitylab.facetrackerble;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.vision.CameraSource;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import com.google.android.gms.vision.face.LargestFaceFocusingProcessor;

import java.io.IOException;

import io.makeabilitylab.facetrackerble.ble.BLEDevice;
import io.makeabilitylab.facetrackerble.ble.BLEListener;
import io.makeabilitylab.facetrackerble.ble.BLEUtil;
import io.makeabilitylab.facetrackerble.camera.CameraSourcePreview;
import io.makeabilitylab.facetrackerble.camera.GraphicOverlay;

/**
 * Demonstrates how to use the Google Android Vision API--specifically the FaceDetector--along
 * with the RedBear Duo. The app detects faces in real-time and transmits left eye and right eye
 * state information (open probability) along with basic emotion inference (sad/happiness probability).
 * <p>
 * It is based on:
 * - The Google Code Lab tutorial: https://codelabs.developers.google.com/codelabs/face-detection/index.html#1
 * - The Google Mobile Vision Face Tracker tutorial: https://developers.google.com/vision/android/face-tracker-tutorial
 * - The FaceTracker demo: https://github.com/googlesamples/android-vision/tree/master/visionSamples/FaceTracker
 * - The Googly Eyes demo: https://github.com/googlesamples/android-vision/tree/master/visionSamples/googly-eyes
 * - The CSE590 BLE demo: https://github.com/jonfroehlich/CSE590Sp2018/tree/master/A03-BLEAdvanced
 * <p>
 * Jon TODO:
 * 1. (Low priority) We shouldn't disconnect from BLE just because our orientation changed (e.g., from Portrait to Landscape). How to deal?
 */
public class MainActivity extends AppCompatActivity implements BLEListener {

  private static final String TAG = "FaceTrackerBLE";
  private static final int RC_HANDLE_GMS = 9001;
  private static final int CAMERA_PREVIEW_WIDTH = 640;
  private static final int CAMERA_PREVIEW_HEIGHT = 480;

  // permission request codes need to be < 256
  private static final int RC_HANDLE_CAMERA_PERM = 2;

  private CameraSource mCameraSource = null;

  private CameraSourcePreview mPreview;
  private GraphicOverlay mGraphicOverlay;

  // Bluetooth stuff
  private BLEDevice mBLEDevice;

  private final String TARGET_BLE_DEVICE_NAME = "JMR_A4";

  //==============================================================================================
  // Activity Methods
  //==============================================================================================

  /**
   * Initializes the UI and initiates the creation of a face detector.
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    mPreview = (CameraSourcePreview) findViewById(R.id.cameraSourcePreview);
    mGraphicOverlay = (GraphicOverlay) findViewById(R.id.faceOverlay);

    // Check for the camera permission before accessing the camera.  If the
    // permission is not granted yet, request permission.
    int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
    if (rc == PackageManager.PERMISSION_GRANTED) {
      createCameraSource();
    } else {
      requestCameraPermission();
    }

    // Make sure that Bluetooth is supported.
    if (!BLEUtil.isSupported(this)) {
      Toast.makeText(this, "BLE not supported", Toast.LENGTH_SHORT)
          .show();
      finish();
      return;
    }

    // Make sure that we have required permissions.
    if (!BLEUtil.hasPermission(this)) {
      BLEUtil.requestPermission(this);
    }

    // Make sure that Bluetooth is enabled.
    if (!BLEUtil.isBluetoothEnabled(this)) {
      BLEUtil.requestEnableBluetooth(this);
    }

    mBLEDevice = new BLEDevice(this, TARGET_BLE_DEVICE_NAME);
    mBLEDevice.addListener(this);
    attemptBleConnection();
  }

  /**
   * Handles the requesting of the camera permission.  This includes
   * showing a "Snackbar" message of why the permission is needed then
   * sending the request.
   */
  private void requestCameraPermission() {
    Log.w(TAG, "Camera permission is not granted. Requesting permission");

    final String[] permissions = new String[]{Manifest.permission.CAMERA};

    if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
        Manifest.permission.CAMERA)) {
      ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
      return;
    }

    final Activity thisActivity = this;

    View.OnClickListener listener = new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        ActivityCompat.requestPermissions(thisActivity, permissions,
            RC_HANDLE_CAMERA_PERM);
      }
    };

    // Snackbars are like Toasts
    // See: https://stackoverflow.com/q/34432339
    Snackbar.make(mGraphicOverlay, R.string.permission_camera_rationale,
        Snackbar.LENGTH_INDEFINITE)
        .setAction(R.string.ok, listener)
        .show();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    // User chose not to enable Bluetooth.
    if (requestCode == BLEUtil.REQUEST_ENABLE_BLUETOOTH
        && !BLEUtil.isBluetoothEnabled(this)) {
      finish();
      return;
    }

    super.onActivityResult(requestCode, resultCode, data);
  }

  /**
   * Creates and starts the camera.  Note that this uses a higher resolution in comparison
   * to other detection examples to enable the barcode detector to detect small barcodes
   * at long distances.
   */
  private void createCameraSource() {

    int cameraFacing = CameraSource.CAMERA_FACING_FRONT;

    Context context = getApplicationContext();

    // Setup the Google Vision FaceDetector:
    //   - https://developers.google.com/android/reference/com/google/android/gms/vision/face/FaceDetector
    // We use the detector in a pipeline structure in conjunction with a source (Camera)
    // and a processor (in this case, MultiProcessor.Builder<>(new FaceTrackerFactory()))
    FaceDetector detector = new FaceDetector.Builder(context)
        .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
        .build();

    // This processor distributes the items of detection results among individual trackers
    // so that you can detect multiple faces.
    // See: https://developers.google.com/android/reference/com/google/android/gms/vision/MultiProcessor
    // MultiProcessor faceProcessor = new MultiProcessor.Builder<>(new FaceTrackerFactory()).build();

    // This processor only finds the largest face in the frame.
    LargestFaceFocusingProcessor faceProcessor = new LargestFaceFocusingProcessor(detector, new FaceTracker(mGraphicOverlay));

    // set the detector's processor
    detector.setProcessor(faceProcessor);

    if (!detector.isOperational()) {
      // Note: The first time that an app using face API is installed on a device, GMS will
      // download a native library to the device in order to do detection.  Usually this
      // completes before the app is run for the first time.  But if that download has not yet
      // completed, then the above call will not detect any faces.
      //
      // isOperational() can be used to check if the required native library is currently
      // available.  The detector will automatically become operational once the library
      // download completes on device.
      Log.w(TAG, "Face detector dependencies are not yet available.");
    }

    // The face detector can run with a fairly low resolution image (e.g., 320x240)
    // Running in lower images is significantly faster than higher resolution
    // We've currently set this to 640x480
    mCameraSource = new CameraSource.Builder(context, detector)
        .setRequestedPreviewSize(CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT)
        .setFacing(cameraFacing)
        .setRequestedFps(30.0f)
        .build();
  }

  /**
   * Restarts the camera.
   */
  @Override
  protected void onResume() {
    super.onResume();

    startCameraSource();

    if (!BLEUtil.isBluetoothEnabled(this)) {
      BLEUtil.requestEnableBluetooth(this);
    }
  }

  /**
   * Stops the camera.
   */
  @Override
  protected void onPause() {
    super.onPause();
    mPreview.stop();
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (mBLEDevice != null) {
      mBLEDevice.disconnect();
    }
  }

  /**
   * Releases the resources associated with the camera source, the associated detector, and the
   * rest of the processing pipeline.
   */
  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (mCameraSource != null) {
      mCameraSource.release();
    }
  }

  /**
   * Callback for the result from requesting permissions. This method
   * is invoked for every call on {@link #requestPermissions(String[], int)}.
   * <p>
   * <strong>Note:</strong> It is possible that the permissions request interaction
   * with the user is interrupted. In this case you will receive empty permissions
   * and results arrays which should be treated as a cancellation.
   * </p>
   *
   * @param requestCode  The request code passed in {@link #requestPermissions(String[], int)}.
   * @param permissions  The requested permissions. Never null.
   * @param grantResults The grant results for the corresponding permissions
   *                     which is either {@link PackageManager#PERMISSION_GRANTED}
   *                     or {@link PackageManager#PERMISSION_DENIED}. Never null.
   * @see #requestPermissions(String[], int)
   */
  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    // Check user response to requesting bluetooth permissions
    if (requestCode == BLEUtil.REQUEST_BLUETOOTH_PERMISSIONS) {
      if (BLEUtil.hasPermission(this)) {
        attemptBleConnection();
      } else {
        finish();
        return;
      }
    }

    // Check user response to requesting camera permissions
    if (requestCode == RC_HANDLE_CAMERA_PERM) {
      if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        Log.d(TAG, "Camera permission granted - initialize the camera source");
        // we have permission, so create the camerasource
        createCameraSource();
        return;
      }

      Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
          " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));

      DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
          finish();
        }
      };

      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setTitle("Face Tracker BLE Demo")
          .setMessage(R.string.no_camera_permission)
          .setPositiveButton(R.string.ok, listener)
          .show();
    }

    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
  }

  //==============================================================================================
  // UI
  //==============================================================================================

  /**
   * Saves the camera facing mode, so that it can be restored after the device is rotated.
   */
  @Override
  public void onSaveInstanceState(Bundle savedInstanceState) {
    super.onSaveInstanceState(savedInstanceState);
  }



  //==============================================================================================
  // Camera Source Preview
  //==============================================================================================

  /**
   * Starts or restarts the camera source, if it exists.  If the camera source doesn't exist yet
   * (e.g., because onResume was called before the camera source was created), this will be called
   * again when the camera source is created.
   */
  private void startCameraSource() {

    // check that the device has play services available.
    int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
        getApplicationContext());
    if (code != ConnectionResult.SUCCESS) {
      Dialog dlg =
          GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
      dlg.show();
    }

    if (mCameraSource != null) {
      try {
        mPreview.start(mCameraSource, mGraphicOverlay);
      } catch (IOException e) {
        Log.e(TAG, "Unable to start camera source.", e);
        mCameraSource.release();
        mCameraSource = null;
      }
    }
  }

  //==============================================================================================
  // Graphic Face Tracker
  //==============================================================================================

  // Constants for face tracking and communication
  private static final byte FACE_LOCATION_CODE = 0x00;

  /**
   * Factory for creating a face tracker to be associated with a new face.  The multiprocessor
   * uses this factory to create face trackers as needed -- one for each individual.
   */
  private class FaceTrackerFactory implements MultiProcessor.Factory<Face> {
    @Override
    public Tracker<Face> create(Face face) {
      return new FaceTracker(mGraphicOverlay);
    }
  }

  /**
   * Face tracker for each detected individual. This maintains a face graphic within the app's
   * associated face overlay.
   */
  private class FaceTracker extends Tracker<Face> {
    private GraphicOverlay mOverlay;
    private FaceGraphic mFaceGraphic;

    FaceTracker(GraphicOverlay overlay) {
      mOverlay = overlay;
      mFaceGraphic = new FaceGraphic(overlay);
    }

    /**
     * Start tracking the detected face instance within the face overlay.
     */
    @Override
    public void onNewItem(int faceId, Face item) {
      mFaceGraphic.setId(faceId);
      // Start recording a video of the person.
    }

    /**
     * Update the position/characteristics of the face within the overlay.
     */
    @Override
    public void onUpdate(FaceDetector.Detections<Face> detectionResults, Face face) {

      mOverlay.add(mFaceGraphic);
      mFaceGraphic.updateFace(face);


      // CSE590 Student TODO:
      // Once a face is detected, you get lots of information about the face for free, including
      // a smiling probability score, eye shut probability, and the location of the face in the
      // camera image (note that we are using a small camera size to speedup processing:
      //   The dimensions are: CAMERA_PREVIEW_WIDTH x CAMERA_PREVIEW_HEIGHT
      //
      // You need to translate the location of the face into data that will be useful for your servo.
      // For example, this code would translate the X location of the face into a range from 0-255:
      //    (centerOfFace.x / CAMERA_PREVIEW_WIDTH) * 255
      // Since the servo motor can move in only one dimension, you only need to track one dimension of movement
      //
      // To properly calculate the location of the face, you may need to handle front facing vs. rear facing camera
      // and portrait vs. landscape phone modes properly.
      //
      // You can also turn on Landmark detection to get more information about the face like cheek, ear, mouth, etc.
      //   See: https://developers.google.com/android/reference/com/google/android/gms/vision/face/Landmark
      boolean isPortrait = (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
      String debugFaceInfo = String.format("Portrait: %b Front-Facing Camera: %b FaceId: %d Loc (x,y): (%.1f, %.1f) Size (w, h): (%.1f, %.1f) Left Eye: %.1f Right Eye: %.1f  Smile: %.1f",
          isPortrait,
          face.getId(),
          face.getPosition().x, face.getPosition().y,
          face.getHeight(), face.getWidth(),
          face.getIsLeftEyeOpenProbability(), face.getIsRightEyeOpenProbability(),
          face.getIsSmilingProbability());


      byte faceX = (byte) (
          (face.getPosition().x + face.getWidth() / 2) / CAMERA_PREVIEW_WIDTH * 255);

      byte[] buf = new byte[]{FACE_LOCATION_CODE, faceX};
      Log.i("JMRJMRJMR","Would be sending bytes: " + buf);

      // Send the data!
      mBLEDevice.sendData(buf);
    }

    /**
     * Hide the graphic when the corresponding face was not detected.  This can happen for
     * intermediate frames temporarily (e.g., if the face was momentarily blocked from
     * view).
     */
    @Override
    public void onMissing(FaceDetector.Detections<Face> detectionResults) {
      mOverlay.remove(mFaceGraphic);
    }

    /**
     * Called when the face is assumed to be gone for good. Remove the graphic annotation from
     * the overlay.
     */
    @Override
    public void onDone() {
      mOverlay.remove(mFaceGraphic);
    }
  }

  //==============================================================================================
  // Bluetooth stuff
  //==============================================================================================

  private void attemptBleConnection() {
    if (BLEUtil.hasPermission(this) &&
        BLEUtil.isBluetoothEnabled(this) &&
        mBLEDevice.getState() == BLEDevice.State.DISCONNECTED) {
      String msg = "Attempting to connect to '" + TARGET_BLE_DEVICE_NAME + "'";
      Toast toast = Toast.makeText(
          MainActivity.this,
          msg,
          Toast.LENGTH_LONG);
      toast.show();
      TextView textViewBleStatus = (TextView) findViewById(R.id.textViewBleStatus);
      textViewBleStatus.setText(msg);
      mBLEDevice.connect();
    }
  }

  @Override
  public void onBleConnected() {
    Toast.makeText(getApplicationContext(), "Connected", Toast.LENGTH_SHORT).show();

    TextView textViewBleStatus = (TextView) findViewById(R.id.textViewBleStatus);
    textViewBleStatus.setText("Connected to '" + TARGET_BLE_DEVICE_NAME + "'");
  }

  @Override
  public void onBleConnectFailed() {
    Toast toast = Toast
        .makeText(
            MainActivity.this,
            "Couldn't find the BLE device with name '" + TARGET_BLE_DEVICE_NAME + "'!",
            Toast.LENGTH_SHORT);
    toast.setGravity(0, 0, Gravity.CENTER);
    toast.show();

    TextView textViewBleStatus = (TextView) findViewById(R.id.textViewBleStatus);
    textViewBleStatus.setText("BLE connection to '" + TARGET_BLE_DEVICE_NAME + "' failed");

    // Jon TODO: We should really pause here before trying to reconnect...
    // Have some sort of backoff
    attemptBleConnection();
  }

  @Override
  public void onBleDisconnected() {
    Toast.makeText(getApplicationContext(), "Disconnected", Toast.LENGTH_SHORT).show();

    TextView textViewBleStatus = (TextView) findViewById(R.id.textViewBleStatus);
    textViewBleStatus.setText("Disconnected from '" + TARGET_BLE_DEVICE_NAME + "'");

    // Jon TODO: We should really pause here before trying to reconnect...
    // Have some sort of backoff
    attemptBleConnection();
  }

  @Override
  public void onBleDataReceived(byte[] data) {
    // The general form of the received data is going to be:
    //   - bytes 1 and 2 are an encoded 2-byte integer representing the number of centimeters
    //         away an object is.

    // The distance here is incredibly inaccurate.
    // Using this as a signal for doing something is probably a bad user experience.
    // Only use it for debugging.
    int distance = ((int)data[0]) << 8 | data[1];
    ((TextView) findViewById(R.id.distanceDebug)).setText(
            "Distance: " + (float)(distance / 100.0f) + " m");
  }

  @Override
  public void onBleRssiChanged(int rssi) {
    // Not needed for this app
  }
}
