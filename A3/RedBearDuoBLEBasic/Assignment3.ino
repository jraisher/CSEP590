#include "ble_config.h"

/*
 * Simple Bluetooth Demo
 * This code shows that the user can send simple digital write data from the
 * Android app to the Duo board.
 * Created by Liang He, April 27th, 2018
 * 
 * The Library is created based on Bjorn's code for RedBear BLE communication: 
 * https://github.com/bjo3rn/idd-examples/tree/master/redbearduo/examples/ble_led
 * 
 * Our code is created based on the provided example code (Simple Controls) by the RedBear Team:
 * https://github.com/RedBearLab/Android
 */

#if defined(ARDUINO) 
SYSTEM_MODE(SEMI_AUTOMATIC); 
#endif

#define RECEIVE_MAX_LEN    2
#define BLE_SHORT_NAME_LEN 0x07 // must be in the range of [0x01, 0x09]
#define BLE_SHORT_NAME 'J','M','R','_','A','3'  // define each char but the number of char should be BLE_SHORT_NAME_LEN-1

#define RED D0
#define GREEN D1
#define BLUE D2

#define LIGHT_SENSOR A0
#define PRESSURE_SENSOR A1

//////// State variables
// Control the color through physical means on the board.
int PHYSICAL_MODE = 0;
// Control the color through a bluetooth signal.
int BLUETOOTH_MODE = 1;
// The mode that the board is in.
int MODE = PHYSICAL_MODE;

float RED_STATE = 0;
float GREEN_STATE = 0;
float BLUE_STATE = 0;
bool IS_ON = true;

// UUID is used to find the device by other BLE-abled devices
static uint8_t service1_uuid[16]    = { 0x71,0x3d,0x00,0x00,0x50,0x3e,0x4c,0x75,0xba,0x94,0x31,0x48,0xf1,0x8d,0x94,0x1e };
static uint8_t service1_tx_uuid[16] = { 0x71,0x3d,0x00,0x03,0x50,0x3e,0x4c,0x75,0xba,0x94,0x31,0x48,0xf1,0x8d,0x94,0x1e };

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

// Define the receive and send handlers
static uint16_t receive_handle = 0x0000; // recieve

static uint8_t receive_data[RECEIVE_MAX_LEN] = { 0x01 };

/**
 * @brief Callback for writing event.
 *
 * @param[in]  value_handle  
 * @param[in]  *buffer       The buffer pointer of writting data.
 * @param[in]  size          The length of writting data.   
 *
 * @retval 
 */
int bleWriteCallback(uint16_t value_handle, uint8_t *buffer, uint16_t size) {
  Serial.print("Write value handler: ");
  Serial.println(value_handle, HEX);

  if (receive_handle == value_handle) {
    memcpy(receive_data, buffer, RECEIVE_MAX_LEN);
    Serial.print("Write value: ");
    for (uint8_t index = 0; index < RECEIVE_MAX_LEN; index++) {
      Serial.print(receive_data[index], HEX);
      Serial.print(" ");
    }
    Serial.println(" ");
    
    /* Process the data
     * TODO: Receive the data sent from other BLE-abled devices (e.g., Android app)
     * and process the data for different purposes (digital write, digital read, analog read, PWM write)
     */
    byte code = receive_data[0] >> 4;
    byte red = receive_data[0] & 0x0f;
    byte green = receive_data[1] >> 4;
    byte blue = receive_data[1] & 0x0f;
    switch(code) {
      case 0x00:
        handleTurnOff();
        break;
      case 0x01:
        handleTurnOn(red, green, blue);
        break;
      case 0x02:
        handleSetColor(red, green, blue);
        break;
      case 0x03:
        handleSetPhysical();
        break;
      case 0x04:
        handleSetBluetooth(red, green, blue);
        break;
    }
  }
  return 0;
}

void handleTurnOff() {
  Serial.println("Recieved power off signal");
  if (MODE == PHYSICAL_MODE) return;
  IS_ON = false;
}

void handleTurnOn(int red, int green, int blue) {
  Serial.println("Recieved power on signal");
  if (MODE == PHYSICAL_MODE) return;
  IS_ON = true;
  handleSetColor(red, green, blue);
}

// Since the red, green, and blue values are carried with 4 bits each, they can take
// values between 0 and 15, so we divide by 16. 
void handleSetColor(int red, int green, int blue) {
  Serial.print("Recieved set color signal: red: ");
  Serial.print(red);
  Serial.print(" green: ");
  Serial.print(green);
  Serial.print(" blue: ");
  Serial.print(blue);
  Serial.print("\n");
    
  if (MODE == PHYSICAL_MODE) return;
  RED_STATE = constrain((float)red / 16.0, 0.0, 1.0);
  GREEN_STATE = constrain((float)green / 16.0, 0.0, 1.0);
  BLUE_STATE = constrain((float)blue / 16.0, 0.0, 1.0);
}

void handleSetPhysical() {
  Serial.println("Recieved physical mode signal");
  MODE = PHYSICAL_MODE;
  IS_ON = true;
}

void handleSetBluetooth(int red, int green, int blue) {
  Serial.println("Recieved bluetooth mode signal");
  MODE = BLUETOOTH_MODE;
  IS_ON = false;
  handleSetColor(red, green, blue);
}


void setup() {
  Serial.begin(115200);
  delay(5000);
  Serial.println("Simple Digital Out Demo.");

  // Initialize ble_stack.
  ble.init();
  configureBLE(); //lots of standard initialization hidden in here - see ble_config.cpp
  // Set BLE advertising data
  ble.setAdvertisementData(sizeof(adv_data), adv_data);

  // Register BLE callback functions
  ble.onDataWriteCallback(bleWriteCallback);

  // Add user defined service and characteristics
  ble.addService(service1_uuid);
  receive_handle = ble.addCharacteristicDynamic(service1_tx_uuid, ATT_PROPERTY_NOTIFY|ATT_PROPERTY_WRITE|ATT_PROPERTY_WRITE_WITHOUT_RESPONSE, receive_data, RECEIVE_MAX_LEN);
  
  // BLE peripheral starts advertising now.
  ble.startAdvertising();
  Serial.println("BLE start advertising.");
  
  pinMode(RED, OUTPUT);
  pinMode(GREEN, OUTPUT);
  pinMode(BLUE, OUTPUT);

  pinMode(LIGHT_SENSOR, INPUT);
  pinMode(PRESSURE_SENSOR, INPUT);
}

void loop() {
  // The darker the room, the brighter the light to conserve energy.
  // The light is also always controlled physically.
  float light;
  if (IS_ON) {
    light =  1.0 - float(analogRead(LIGHT_SENSOR)) / float(4096);
    light = constrain((light - 0.25) / 0.5, 0.0, 1.0);
  } else {
    light = 0.0;
  }

  if (MODE == PHYSICAL_MODE) {
    senseColorState();
  }

  setColor(RED_STATE * light, GREEN_STATE * light, BLUE_STATE * light);
  delay(50);
}

void senseColorState() {
  // Here, we represent the hue of the light as a float from 0.0 (red) to 1.0 (blue)
  float hue = float(analogRead(PRESSURE_SENSOR)) / float(4096);

  RED_STATE = constrain(2.0 - 3 * hue, 0.0, 1.0); 
  GREEN_STATE = constrain(1.5 - 3 * abs(hue - 0.5), 0.0, 1.0);
  BLUE_STATE = constrain(3 * hue - 2, 0.0, 1.0);
}

void setColor(float red, float green, float blue) {
  int rvalue = 255 * (1.0 - red);
  int gvalue = 255 * (1.0 - green);
  int bvalue = 255 * (1.0 - blue);

  analogWrite(RED, rvalue);
  analogWrite(GREEN, gvalue);
  analogWrite(BLUE, bvalue);
}

