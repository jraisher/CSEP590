/* BLE demo: Use a single button to send data to the Duo board from the Android app to control the
 * LED on and off on the board through BLE.
 *
 * The app is built based on the example code provided by the RedBear Team:
 * https://github.com/RedBearLab/Android
 */
package com.example.lianghe.android_ble_basic;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.christophesmet.android.views.colorpicker.ColorPickerView;
import com.example.lianghe.android_ble_basic.BLE.RBLGattAttributes;
import com.example.lianghe.android_ble_basic.BLE.RBLService;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
  // Define the device name and the length of the name
  // Note the device name and the length should be consistent with the ones defined in the Duo sketch
  private static String TARGET_DEVICE_NAME = "JMR_A3";
  private static int mNameLen = 0x07;

  private final static String TAG = MainActivity.class.getSimpleName();

  // Declare all variables associated with the UI components
  private Button mConnectBtn = null;
  private TextView mDeviceName = null;
  private TextView mRssiValue = null;
  private TextView mUUID = null;
  private ToggleButton controlButton;
  private ToggleButton powerButton;
  private String mBluetoothDeviceName = "";
  private String mBluetoothDeviceUUID = "";
  private Spinner colorModeSpinner = null;
  private ColorPickerView colorPicker = null;
  private SensorManager sensorManager = null;
  private Sensor gravitySensor = null;
  private SensorEventListener sensorListener = null;
  private Handler handler;
  private BatteryManager batteryManager;

  // Declare all Bluetooth stuff
  private BluetoothGattCharacteristic mCharacteristicTx = null;
  private RBLService mBluetoothLeService;
  private BluetoothAdapter mBluetoothAdapter;
  private BluetoothDevice mDevice = null;
  private String mDeviceAddress;

  private boolean flag = true;
  private boolean mConnState = false;
  private boolean mScanFlag = false;

  private byte[] mData = new byte[3];
  private static final int REQUEST_ENABLE_BT = 1;
  private static final long SCAN_PERIOD = 1000;   // millis

  final private static char[] hexArray = {'0', '1', '2', '3', '4', '5', '6',
      '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

  // Process service connection. Created by the RedBear Team
  private final ServiceConnection mServiceConnection = new ServiceConnection() {

    @Override
    public void onServiceConnected(ComponentName componentName,
                                   IBinder service) {
      mBluetoothLeService = ((RBLService.LocalBinder) service)
          .getService();
      if (!mBluetoothLeService.initialize()) {
        Log.e(TAG, "Unable to initialize Bluetooth");
        finish();
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      mBluetoothLeService = null;
    }
  };

  private BoardController boardController;

  private void setButtonDisable() {
    flag = false;
    mConnState = false;
    controlButton.setEnabled(flag);
    mConnectBtn.setText("Connect");
    mRssiValue.setText("");
    mDeviceName.setText("");
    mUUID.setText("");
  }

  private void setButtonEnable() {
    flag = true;
    mConnState = true;
    controlButton.setEnabled(flag);
    mConnectBtn.setText("Disconnect");
  }

  // Process the Gatt and get data if there is data coming from Duo board. Created by the RedBear Team
  private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      final String action = intent.getAction();

      if (RBLService.ACTION_GATT_DISCONNECTED.equals(action)) {
        Toast.makeText(getApplicationContext(), "Disconnected",
            Toast.LENGTH_SHORT).show();
        setButtonDisable();
      } else if (RBLService.ACTION_GATT_SERVICES_DISCOVERED
          .equals(action)) {
        Toast.makeText(getApplicationContext(), "Connected",
            Toast.LENGTH_SHORT).show();

        getGattService(mBluetoothLeService.getSupportedGattService());
      } else if (RBLService.ACTION_GATT_RSSI.equals(action)) {
        displayData(intent.getStringExtra(RBLService.EXTRA_DATA));
      }
    }
  };

  // Display the received RSSI on the interface
  private void displayData(String data) {
    if (data != null) {
      mRssiValue.setText(data);
      mDeviceName.setText(mBluetoothDeviceName);
      mUUID.setText(mBluetoothDeviceUUID);
    }
  }


  // Get Gatt service information for setting up the communication
  private void getGattService(BluetoothGattService gattService) {
    if (gattService == null)
      return;

    setButtonEnable();
    startReadRssi();

    mCharacteristicTx = gattService
        .getCharacteristic(RBLService.UUID_BLE_SHIELD_TX);
  }

  // Start a thread to read RSSI from the board
  private void startReadRssi() {
    new Thread() {
      public void run() {

        while (flag) {
          mBluetoothLeService.readRssi();
          try {
            sleep(500);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }

      ;
    }.start();
  }

  // Scan all available BLE-enabled devices
  private void scanLeDevice() {
    new Thread() {

      @Override
      public void run() {
        mBluetoothAdapter.startLeScan(mLeScanCallback);

        try {
          Thread.sleep(SCAN_PERIOD);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        mBluetoothAdapter.stopLeScan(mLeScanCallback);
      }
    }.start();
  }

  // Callback function to search for the target Duo board which has matched UUID
  // If the Duo board cannot be found, debug if the received UUID matches the predefined UUID on the board
  private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {

    @Override
    public void onLeScan(final BluetoothDevice device, final int rssi,
                         final byte[] scanRecord) {

      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          byte[] serviceUuidBytes = new byte[16];
          String serviceUuid = "";
          for (int i = (21 + mNameLen), j = 0; i >= (6 + mNameLen); i--, j++) {
            serviceUuidBytes[j] = scanRecord[i];
          }
          /*
           * This is where you can test if the received UUID matches the defined UUID in the Arduino
           * Sketch and uploaded to the Duo board: 0x713d0000503e4c75ba943148f18d941e.
           */
          serviceUuid = bytesToHex(serviceUuidBytes);
          if (stringToUuidString(serviceUuid).equals(
              RBLGattAttributes.BLE_SHIELD_SERVICE
                  .toUpperCase(Locale.ENGLISH)) && device.getName().equals(TARGET_DEVICE_NAME)) {
            mDevice = device;
            mBluetoothDeviceName = mDevice.getName();
            mBluetoothDeviceUUID = serviceUuid;
          }
        }
      });
    }
  };

  // Convert an array of bytes into Hex format string
  private String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    int v;
    for (int j = 0; j < bytes.length; j++) {
      v = bytes[j] & 0xFF;
      hexChars[j * 2] = hexArray[v >>> 4];
      hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
  }

  // Convert a string to a UUID format
  private String stringToUuidString(String uuid) {
    StringBuffer newString = new StringBuffer();
    newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(0, 8));
    newString.append("-");
    newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(8, 12));
    newString.append("-");
    newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(12, 16));
    newString.append("-");
    newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(16, 20));
    newString.append("-");
    newString.append(uuid.toUpperCase(Locale.ENGLISH).substring(20, 32));

    return newString.toString();
  }


  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // Associate all UI components with variables
    mConnectBtn = (Button) findViewById(R.id.connectBtn);
    mDeviceName = (TextView) findViewById(R.id.deviceName);
    mRssiValue = (TextView) findViewById(R.id.rssiValue);
    controlButton = (ToggleButton) findViewById(R.id.control_button);
    powerButton = (ToggleButton) findViewById(R.id.power_button);
    colorModeSpinner = (Spinner) findViewById(R.id.color_mode_spinner);
    colorPicker = (ColorPickerView) findViewById(R.id.color_picker_view);
    handler = new Handler();
    batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
    mUUID = (TextView) findViewById(R.id.uuidValue);

    sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
    sensorListener = new SensorEventListener(){
      @Override
      public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GRAVITY
            && colorInputMode == GRAVITY_COLOR_SELECTION) {
          double total = event.values[0] + event.values[1] + event.values[2];
          setColor(
              event.values[0] / total,
              event.values[1] / total,
              event.values[2] / total);
        }
      }

      @Override
      public void onAccuracyChanged(Sensor sensor, int accuracy) {

      }
    };
    sensorManager.registerListener(
        sensorListener, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL);

    disableColorInput();
    disablePowerInput();

    // Connection button click event
    mConnectBtn.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {
        if (mScanFlag == false) {
          // Scan all available devices through BLE
          scanLeDevice();

          Timer mTimer = new Timer();
          mTimer.schedule(new TimerTask() {

            @Override
            public void run() {
              if (mDevice != null) {
                mDeviceAddress = mDevice.getAddress();
                mBluetoothLeService.connect(mDeviceAddress);
                mScanFlag = true;
              } else {
                runOnUiThread(new Runnable() {
                  public void run() {
                    Toast toast = Toast
                        .makeText(
                            MainActivity.this,
                            "Couldn't search Ble Shield device!",
                            Toast.LENGTH_SHORT);
                    toast.setGravity(0, 0, Gravity.CENTER);
                    toast.show();
                  }
                });
              }
            }
          }, SCAN_PERIOD);
        }

        System.out.println(mConnState);
        if (mConnState == false) {
          mBluetoothLeService.connect(mDeviceAddress);
        } else {
          mBluetoothLeService.disconnect();
          mBluetoothLeService.close();
          setButtonDisable();
          disableColorInput();
          disablePowerInput();
        }
      }
    });

    // Send data to Duo board
    // It has three bytes: maker, data value, reserved
    controlButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView,
                                   boolean isChecked) {
        handleControlButtonPress(isChecked);
      }
    });

    powerButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        handlePowerButtonPress(isChecked);
      }
    });

    colorModeSpinner.setAdapter(new ArrayAdapter<CharSequence>(
        this, R.layout.support_simple_spinner_dropdown_item,
        new String[]{
            MANUAL_COLOR_SELECTION, GRAVITY_COLOR_SELECTION, EXPERIMENTAL_COLOR_SELECTION}));
    colorModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        selectColorInput((String) parent.getItemAtPosition(position));
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {

      }
    });


    colorPicker.setColorListener(new ColorPickerView.ColorListener() {
      @Override
      public void onColorSelected(int newColor) {
        handleColorPick(newColor);
      }
    });

    // Bluetooth setup. Created by the RedBear team.
    if (!getPackageManager().hasSystemFeature(
        PackageManager.FEATURE_BLUETOOTH_LE)) {
      Toast.makeText(this, "Ble not supported", Toast.LENGTH_SHORT)
          .show();
      finish();
    }

    final BluetoothManager mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
    mBluetoothAdapter = mBluetoothManager.getAdapter();
    if (mBluetoothAdapter == null) {
      Toast.makeText(this, "Ble not supported", Toast.LENGTH_SHORT)
          .show();
      finish();
      return;
    }

    Intent gattServiceIntent = new Intent(MainActivity.this,
        RBLService.class);
    bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
  }

  @Override
  protected void onResume() {
    super.onResume();

    // Check if BLE is enabled on the device. Created by the RedBear team.
    if (!mBluetoothAdapter.isEnabled()) {
      Intent enableBtIntent = new Intent(
          BluetoothAdapter.ACTION_REQUEST_ENABLE);
      startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
    }

    registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

  }

  @Override
  protected void onStop() {
    super.onStop();

    flag = false;

    unregisterReceiver(mGattUpdateReceiver);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    if (mServiceConnection != null)
      unbindService(mServiceConnection);
  }

  // Create a list of intent filters for Gatt updates. Created by the RedBear team.
  private static IntentFilter makeGattUpdateIntentFilter() {
    final IntentFilter intentFilter = new IntentFilter();

    intentFilter.addAction(RBLService.ACTION_GATT_CONNECTED);
    intentFilter.addAction(RBLService.ACTION_GATT_DISCONNECTED);
    intentFilter.addAction(RBLService.ACTION_GATT_SERVICES_DISCOVERED);
    intentFilter.addAction(RBLService.ACTION_DATA_AVAILABLE);
    intentFilter.addAction(RBLService.ACTION_GATT_RSSI);

    return intentFilter;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    // User chose not to enable Bluetooth.
    if (requestCode == REQUEST_ENABLE_BT
        && resultCode == Activity.RESULT_CANCELED) {
      finish();
      return;
    }

    super.onActivityResult(requestCode, resultCode, data);
  }

  ///////////////////////   Code for handling button presses.   //////////////////////////////
  private static final int RED = 0;
  private static final int GREEN = 1;
  private static final int BLUE = 2;

  private static final String MANUAL_COLOR_SELECTION = "Manual";
  private static final String GRAVITY_COLOR_SELECTION = "Gravity";
  private static final String EXPERIMENTAL_COLOR_SELECTION = "Battery";

  private String colorInputMode = MANUAL_COLOR_SELECTION;

  private void handleControlButtonPress(boolean on) {
    if (on) {
      activateBluetoothControl(1.0, 1.0, 1.0);
      enablePowerInput();
    } else {
      activatePhysicalControl();
      disablePowerInput();
    }
  }

  private void handlePowerButtonPress(boolean on) {
    if (on) {
      turnOffLight();
      disableColorInput();
    } else {
      enableColorInput();
    }
  }

  private void handleColorPick(int color) {
    setColor(intToColors(color));
  }

  private void enablePowerInput() {
    powerButton.setEnabled(true);
    if (!powerButton.isChecked()) {
      enableColorInput();
    }
  }

  private void disablePowerInput() {
    powerButton.setEnabled(false);
    disableColorInput();
  }

  private void enableColorInput() {
    colorModeSpinner.setEnabled(true);
    colorPicker.setVisibility(View.VISIBLE);
    double[] colors = intToColors(colorPicker.getColor());
    turnOnLight(colors[RED], colors[GREEN], colors[BLUE]);
  }

  private void disableColorInput() {
    colorModeSpinner.setEnabled(false);
    colorPicker.setVisibility(View.INVISIBLE);
  }

  private void selectColorInput(String input) {
    // Deactivate all other visualizations
    colorPicker.setVisibility(View.INVISIBLE);

    colorInputMode = input;
    if (colorInputMode == MANUAL_COLOR_SELECTION) {
      colorPicker.setVisibility(View.VISIBLE);
      double[] colors = intToColors(colorPicker.getColor());
      turnOnLight(colors[RED], colors[GREEN], colors[BLUE]);
    } if (colorInputMode == EXPERIMENTAL_COLOR_SELECTION) {
      Runnable weatherChecker = new Runnable() {
        @Override
        public void run() {
          if (colorInputMode == EXPERIMENTAL_COLOR_SELECTION) {
            double charge =
                (double) batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    / 100f;

            double red = Math.min(1f, Math.max(0f, (2.0f - 3f * charge)));
            double green = Math.min(1f, Math.max(0f, (1.5 - 3f * Math.abs(charge - 0.5))));
            double blue = Math.min(1f, Math.max(0f, (3f * charge - 2)));

            setColor(red, green, blue);
            // Every 5 minutes;
            handler.postDelayed(this, 300000);
          }
        }
      };
      handler.post(weatherChecker);
    }
  }

  /**
   * Converts an integer into an array of doubles which reflect the R, G, B values from 0.0 to 1.0
   */
  private double[] intToColors(int color) {
    double red = (double) (0x00ff & color >> 16) / 255.0;
    double green = (double) (0x0000ff & color >> 8) / 255.0;
    double blue = (double) (0x000000ff & color) / 255.0;

    return new double[]{red, green, blue};
  }

  ///////////////////   Code for interfacing with the board.    /////////////////////////////
  ///////////// For documentation on the methods and protocol see the write-up //////////////

  private static final byte TURN_OFF = 0x00;
  private static final byte TURN_ON = 0x01;
  private static final byte SET_COLOR = 0x02;
  private static final byte ACTIVATE_PHYSICAL = 0x03;
  private static final byte ACTIVATE_BLUETOOTH = 0x04;

  /*
   * Turn on the light in the most recent mode using the given color if not otherwise determined by
   * the mode.
   */
  public void turnOnLight(double red, double green, double blue) {
    send(getColorPayloadBuffer(TURN_ON, red, green, blue));
  }

  /* Turns off the board light. */
  public void turnOffLight() {
    send(new byte[]{(byte) (TURN_OFF << 4), 0x00});
  }

  public void setColor(double[] colors) {
    setColor(colors[RED], colors[GREEN], colors[BLUE]);
  }

  /* Set the light to the given color. */
  public void setColor(double red, double green, double blue) {
    send(getColorPayloadBuffer(SET_COLOR, red, green, blue));
  }

  /*
   * Activates physical control mode on the board.  This uses the pressure sensor to control color.
   */
  public void activatePhysicalControl() {
    send(new byte[]{(byte) (ACTIVATE_PHYSICAL << 4), 0x00});
  }

  /* Activates bluetooth control of the board, setting the board to use the provided color. */
  public void activateBluetoothControl(double red, double green, double blue) {
    send(getColorPayloadBuffer(ACTIVATE_BLUETOOTH, red, green, blue));
  }

  public byte[] getColorPayloadBuffer(byte command, double red, double green, double blue) {
    byte red_bytes = (byte) ((int)(red * 15.0) & 0x0f);
    byte green_bytes = (byte) ((int) (green * 15.0) & 0x0f);
    byte blue_bytes = (byte) ((int) (blue * 15.0) & 0x0f);

    return new byte[]{
        (byte) ((command << 4) | red_bytes),
        (byte) ((green_bytes << 4) | blue_bytes)};
  }

  private void send(byte[] payload) {
    if (mCharacteristicTx == null || mBluetoothLeService == null) return;
    mCharacteristicTx.setValue(payload);
    mBluetoothLeService.writeCharacteristic(mCharacteristicTx);
  }
}