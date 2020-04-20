#include <M5Stack.h>
#include <Adafruit_BMP280.h>

#include <BLEDevice.h>
#include <BLE2902.h>

// BLE上のこのデバイスの名前。適当な名前でOK。
#define BLE_LOCAL_NAME "M5GO Env.Sensor Advertiser"
// BLEのサービスUUID。適当なUUID(ランダムで生成したものがよい)を設定して下さい。
#define BLE_SERVICE_UUID "133fe8d4-5197-4675-9d76-d9bbf2450bb4"
// BLEのCharacteristic UUID。適当なUUID(ランダムで生成したものがよい)を設定して下さい。
#define BLE_CHARACTERISTIC_UUID "0fc10cb8-0518-40dd-b5c3-c4637815de40"

// BMP280のインスタンスを作成
static Adafruit_BMP280 bmp280;

static BLEServer* pBleServer = NULL;
static BLECharacteristic* pBleNotifyCharacteristic = NULL;

// 温度と気圧のデータを指定したバッファーに書き込みます
// バッファーには、1バイト目と2バイト目にはビッグエンディアンで温度を格納し、
// 3バイト目と4バイト目にはビッグエンディアンで気圧を格納します。
// 
// - buffer uint8_tの4つ分以上のサイズのバッファを指定すること
static void pack(uint8_t* buffer, float temperature, float pressure) {
  buffer[0] = (int16_t)(temperature * 100) & 0xff;
  buffer[1] = ((int16_t)(temperature * 100) >> 8);
  buffer[2] = (int16_t)(pressure * 100) & 0xff;
  buffer[3] = ((int16_t)(pressure * 100) >> 8);
}

// 起動時に最初の1回だけ呼ばれる処理は setup 関数内に書きます
void setup() {
  // M5Stackの初期化
  M5.begin();

  // BMP280の初期化。
  // 初期化の結果が bmp280InitResult の変数に格納されます。
  // 引数に指定している BMP280_ADDRESS_ALT はBMP280センサーのI2Cアドレスです。
  bool bmp280InitResult = bmp280.begin(BMP280_ADDRESS_ALT);

  // M5StackのLCDを黒で塗りつぶします
  M5.Lcd.fillScreen(BLACK);
  // M5StackのLCDで表示する文字色を白、背景色を黒に設定します。
  M5.Lcd.setTextColor(WHITE ,BLACK);
  // M5StackのLCDで表示する文字の大きさを設定します。
  M5.Lcd.setTextSize(2);

  // "BMP280" という文字列をLCDの座標 (10, 20) の位置に表示します。
  // LCDの座標系は左上が(0, 0)、右下が(320, 240)です。
  M5.Lcd.setCursor(10, 20);
  M5.Lcd.print("BMP280");
  // "temperature:" という文字列をLCDの座標 (30, 50) の位置に表示します。
  M5.Lcd.setCursor(30, 50);
  M5.Lcd.print("temperature:");
  // "temperature:" という文字列をLCDの座標 (30, 80) の位置に表示します。
  M5.Lcd.setCursor(30, 80);
  M5.Lcd.print("pressure:");

  // BMP280の初期化が失敗した場合(bmp280InitResultがfalseの場合)は…
  if (!bmp280InitResult) {
    // M5StackのLCDで表示する文字色を黄色にします。
    M5.Lcd.setTextColor(YELLOW ,BLACK);
    // "Failed BMP280 init." という文字列をLCDの座標 (10, 200) の位置に表示します。
    M5.Lcd.setCursor(10, 200);
    M5.Lcd.print("Failed BMP280 init.");
  }

  // BLE環境の初期化
  BLEDevice::init(BLE_LOCAL_NAME);
  // BLEサーバの生成
  pBleServer = BLEDevice::createServer();
  // BLEのサービスの生成。引数でサービスUUIDを設定する。
  BLEService* pBleService = pBleServer->createService(BLE_SERVICE_UUID);
  // BLE Characteristicの生成
  pBleNotifyCharacteristic = pBleService->createCharacteristic(
                                // Characteristic UUIDを指定
                                BLE_CHARACTERISTIC_UUID,
                                // このCharacteristicが通知にのみ対応していることを指定
                                BLECharacteristic::PROPERTY_NOTIFY
                             );
  // BLE Characteristicにディスクリプタを設定
  pBleNotifyCharacteristic->addDescriptor(new BLE2902());
  // BLEサービスの開始
  pBleService->start();
  // BLEのアドバタイジングを開始
  pBleServer->getAdvertising()->start();
}

// 電源が入っている間は、この loop 関数で書いた処理が繰り返されます
void loop() {
  // M5StackのボタンA/B/Cの読み取り状態を更新しています。
  // ボタンを使わない場合でも、loop関数の冒頭で M5.update() を呼んでおくといいでしょう。
  M5.update();

  // BMP280から温度を取得します
  float temperature = bmp280.readTemperature();
  // BMP280から気圧を取得します。取得した気圧を100でわることで、hPaの単位になります。
  float pressure = bmp280.readPressure() / 100;

  // M5StackのLCDで表示する文字色を白、背景色を黒に設定します。
  M5.Lcd.setTextColor(WHITE ,BLACK);

  // LCDの座標 (180, 50) の位置に温度を小数二桁で表示します。
  M5.Lcd.setCursor(180, 50);
  M5.Lcd.printf("%.2fC", temperature);
  
  // LCDの座標 (180, 50) の位置に気圧を小数二桁で表示します。
  M5.Lcd.setCursor(180, 80);
  M5.Lcd.printf("%.2fhPa", pressure);

  // BLEでのデータ通知用バッファを定義
  uint8_t dataBuffer[4];
  // 温度と気圧のデータをdataBufferに格納します
  pack(dataBuffer, temperature, pressure);
  // データをBLEに設定し、送信します
  pBleNotifyCharacteristic->setValue(dataBuffer, 4);
  pBleNotifyCharacteristic->notify();

  // 33ミリ秒停止します
  delay(33);
}
