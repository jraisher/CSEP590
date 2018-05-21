#include "ble_config.h"

/*
 * Provides skeleton code to interact with the Android FaceTrackerBLE app 
 * 
 * Created by Jon Froehlich, May 7, 2018
 * 
 * Based on previous code by Liang He, Bjoern Hartmann, 
 * Chris Dziemborowicz and the RedBear Team. See: 
 * https://github.com/jonfroehlich/CSE590Sp2018/tree/master/A03-BLEAdvanced
 */

#if defined(ARDUINO) 
SYSTEM_MODE(SEMI_AUTOMATIC); 
#endif

#define RECEIVE_MAX_LEN  2 
#define SEND_MAX_LEN    3

// Must be an integer between 1 and 9 and and must also be set to len(BLE_SHORT_NAME) + 1
#define BLE_SHORT_NAME_LEN 7 

#define BLE_SHORT_NAME 'J','M','R','_','A','4'  

/* Define the pins on the Duo board
 * TODO: change and add/subtract the pins here for your applications (as necessary)
 */
#define TRIGGER_PIN D0
#define ECHO_PIN D1
#define SERVO_ANALOG_PIN D2
#define LIGHT_PIN D8
#define BUZZER_PIN D9

#define MAX_SERVO_ANGLE  180
#define MIN_SERVO_ANGLE  0
int SERVO_ANGLE = (MAX_SERVO_ANGLE - MIN_SERVO_ANGLE) / 2;

#define MAX_DISTANCE 400  // cm
#define ALERT_DISTANCE 30  // cm

#define BLE_DEVICE_CONNECTED_DIGITAL_OUT_PIN D7

// happiness meter (servo)
Servo _servo;

// Device connected and disconnected callbacks
void deviceConnectedCallback(BLEStatus_t status, uint16_t handle);
void deviceDisconnectedCallback(uint16_t handle);

// UUID is used to find the device by other BLE-abled devices
static uint8_t service1_uuid[16]    = { 0x71,0x3d,0x00,0x00,0x50,0x3e,0x4c,0x75,0xba,0x94,0x31,0x48,0xf1,0x8d,0x94,0x1e };
static uint8_t service1_tx_uuid[16] = { 0x71,0x3d,0x00,0x03,0x50,0x3e,0x4c,0x75,0xba,0x94,0x31,0x48,0xf1,0x8d,0x94,0x1e };
static uint8_t service1_rx_uuid[16] = { 0x71,0x3d,0x00,0x02,0x50,0x3e,0x4c,0x75,0xba,0x94,0x31,0x48,0xf1,0x8d,0x94,0x1e };

// Define the receive and send handlers
static uint16_t receive_handle = 0x0000; // recieve
static uint16_t send_handle = 0x0000; // send

static uint8_t receive_data[RECEIVE_MAX_LEN] = { 0x01 };
int bleReceiveDataCallback(uint16_t value_handle, uint8_t *buffer, uint16_t size); // function declaration for receiving data callback
static uint8_t send_data[SEND_MAX_LEN] = { 0x00 };

// Define the configuration data
static uint8_t adv_data[] = {
  0x02,
  BLE_GAP_AD_TYPE_FLAGS,
  BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE, 
  
  BLE_SHORT_NAME_LEN,
  BLE_GAP_AD_TYPE_SHORT_LOCAL_NAME,
  BLE_SHORT_NAME, 
  
  0x11,
  BLE_GAP_AD_TYPE_128BIT_SERVICE_UUID_COMPLETE,
  0x1e,0x94,0x8d,0xf1,0x48,0x31,0x94,0xba,0x75,0x4c,0x3e,0x50,0x00,0x00,0x3d,0x71 
};

static btstack_timer_source_t send_characteristic;
static void bleSendDataTimerCallback(btstack_timer_source_t *ts); // function declaration for sending data callback
int _sendDataFrequency = 200; // 200ms (how often to read the pins and transmit the data to Android)

void setup() {
  Serial.begin(9600);
  Serial.println("Starting Setup");

  delay(5000);

  // Initialize ble_stack.
  ble.init();
  
  // Register BLE callback functions
  ble.onConnectedCallback(bleConnectedCallback);
  ble.onDisconnectedCallback(bleDisconnectedCallback);

  //lots of standard initialization hidden in here - see ble_config.cpp
  configureBLE(); 
  
  // Set BLE advertising data
  ble.setAdvertisementData(sizeof(adv_data), adv_data);
  
  // Register BLE callback functions
  ble.onDataWriteCallback(bleReceiveDataCallback);

  // Add user defined service and characteristics
  ble.addService(service1_uuid);
  receive_handle = ble.addCharacteristicDynamic(service1_tx_uuid, ATT_PROPERTY_NOTIFY|ATT_PROPERTY_WRITE|ATT_PROPERTY_WRITE_WITHOUT_RESPONSE, receive_data, RECEIVE_MAX_LEN);
  send_handle = ble.addCharacteristicDynamic(service1_rx_uuid, ATT_PROPERTY_NOTIFY, send_data, SEND_MAX_LEN);

  // BLE peripheral starts advertising now.
  ble.startAdvertising();
  Serial.println("BLE start advertising.");

  // Setup pins
  pinMode(TRIGGER_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  pinMode(LIGHT_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);

  pinMode(BLE_DEVICE_CONNECTED_DIGITAL_OUT_PIN, OUTPUT);
  _servo.attach(SERVO_ANALOG_PIN);
  _servo.write(SERVO_ANGLE);

  // Start a task to check status of the pins on your RedBear Duo
  // Works by polling every X milliseconds where X is _sendDataFrequency
  send_characteristic.process = &bleSendDataTimerCallback;
  ble.setTimer(&send_characteristic, _sendDataFrequency); 
  ble.addTimer(&send_characteristic);

  Serial.println("Finishing Setup");
}

const int DISTANCE_BUFFER_SIZE = 4;
float DISTANCE_BUFFER [DISTANCE_BUFFER_SIZE] = {};
int DISTANCE_BUFFER_INDEX = 0;

bool WARN = false;
float DISTANCE = 0.0;
void loop() 
{
  unsigned long t1;
  unsigned long t2;
  unsigned long pulse_width;

  digitalWrite(TRIGGER_PIN, LOW);
  delay(2);
  
  digitalWrite(TRIGGER_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIGGER_PIN, LOW);

  DISTANCE_BUFFER[DISTANCE_BUFFER_INDEX] = (pulseIn(ECHO_PIN, HIGH) / 2.0) * 0.0344;
  DISTANCE_BUFFER_INDEX = (DISTANCE_BUFFER_INDEX + 1) % DISTANCE_BUFFER_SIZE;

  float tmp = 0.0;
  int count = 0;
  for (int i = 0; i < DISTANCE_BUFFER_SIZE; ++i) {
    if (DISTANCE_BUFFER[i] > 0) {
      tmp += DISTANCE_BUFFER[i];
      count += 1;
    }
  }
  if (count > 0) {
    DISTANCE = tmp / count;
  }
  
  if (DISTANCE < ALERT_DISTANCE && DISTANCE > 1.0) {
    if (WARN) {
      signalAlert();      
    } else {
      WARN = true;
      delay(250);
    }
  } else {
    WARN = false;
    digitalWrite(BUZZER_PIN, LOW);
    delay(500);
  }
}

/**
 * Signal the alert.
 */
int LIGHT_STATE = HIGH;
void signalAlert() {
  if (LIGHT_STATE == HIGH) {
    LIGHT_STATE = LOW;
  } else {
    LIGHT_STATE = HIGH;
  }
  digitalWrite(LIGHT_PIN, LIGHT_STATE);
  tone(BUZZER_PIN, 440, 225);
  delay(225);
  tone(BUZZER_PIN, 880, 225);
  delay(225);
  digitalWrite(LIGHT_PIN, LOW);
}

/**
 * @brief Connect handle.
 *
 * @param[in]  status   BLE_STATUS_CONNECTION_ERROR or BLE_STATUS_OK.
 * @param[in]  handle   Connect handle.
 *
 * @retval None
 */
void bleConnectedCallback(BLEStatus_t status, uint16_t handle) {
  switch (status) {
    case BLE_STATUS_OK:
      Serial.println("BLE device connected!");
      digitalWrite(BLE_DEVICE_CONNECTED_DIGITAL_OUT_PIN, HIGH);
      break;
    default: break;
  }
}

/**
 * @brief Disconnect handle.
 *
 * @param[in]  handle   Connect handle.
 *
 * @retval None
 */
void bleDisconnectedCallback(uint16_t handle) {
  Serial.println("BLE device disconnected.");
  digitalWrite(BLE_DEVICE_CONNECTED_DIGITAL_OUT_PIN, LOW);
}

/**
 * @brief Callback for receiving data from Android (or whatever device you're connected to).
 *
 * @param[in]  value_handle  
 * @param[in]  *buffer       The buffer pointer of writting data.
 * @param[in]  size          The length of writting data.   
 *
 * @retval 
 */
int happiness = 0;

int bleReceiveDataCallback(uint16_t value_handle, uint8_t *buffer, uint16_t size) {

  if (receive_handle == value_handle) {
    memcpy(receive_data, buffer, RECEIVE_MAX_LEN);
    Serial.print("Received data: ");
    for (uint8_t index = 0; index < RECEIVE_MAX_LEN; index++) {
      Serial.print(receive_data[index]);
      Serial.print(" ");
    }
    Serial.println(" ");

    int delta = 0;
    switch (receive_data[0]) {
      case 0x00:
        delta = (receive_data[1] - 128) / 10;
        if (delta > 0) {
          SERVO_ANGLE = constrain(
            SERVO_ANGLE - 1, MIN_SERVO_ANGLE, MAX_SERVO_ANGLE);;
        } else if (delta < 0) {
          SERVO_ANGLE = constrain(
            SERVO_ANGLE + 1, MIN_SERVO_ANGLE, MAX_SERVO_ANGLE);;
        }
        _servo.write(SERVO_ANGLE);
        break;
      default:
        Serial.println("Received an unknown control code.");
        Serial.println(receive_data[0]);
    }
  }
  return 0;
}

/**
 * @brief Timer task for sending status change to client.
 * @param[in]  *ts   
 * @retval None
 * 
 * Send the data from either analog read or digital read back to 
 * the connected BLE device (e.g., Android)
 */
static void bleSendDataTimerCallback(btstack_timer_source_t *ts) {
  int distance = DISTANCE;
  Serial.println(distance);
  send_data[0] = 0xff & distance >> 8;
  send_data[1] = 0xff & distance;
  send_data[2] = 0x00;

  if (ble.attServerCanSendPacket()) {
    ble.sendNotify(send_handle, send_data, SEND_MAX_LEN);
  }
  ble.setTimer(ts, _sendDataFrequency);
  ble.addTimer(ts);
}
