#include <Arduino.h> //Các chức năng cơ bản của Arduino như pinMode(), digitalWrite(), millis(), Serial
#include <ArduinoJson.h>
#include <math.h>
#include <atomic>
#include <esp_sntp.h>
#include <esp_system.h>
#include <freertos/FreeRTOS.h> // Các chức năng cơ bản của FreeRTOS như xTaskCreate(), vTaskDelay(), xQueueCreate(), xQueueSend(), xQueueReceive()
#include <freertos/queue.h>
#include <freertos/task.h>
#include <Preferences.h>

#include <time.h>
#include <WiFi.h>         // ESP32 kết nối WiFi để giao tiếp với MQTT Broker
#include <PubSubClient.h> // Thư viện MQTT cho ESP32
#include "DHT.h"          // Thư viện cảm biến DHT22

// SENSOR & DEVICE PIN
#define DHTPIN 15     // Chân kết nối DHT22
#define DHTTYPE DHT22 // Loại cảm biến DHT
#define LDR_PIN 34    // Chân đọc cảm biến ánh sáng
#define CO2_PIN 35    // Chân đọc cảm biến CO2
#define FAN_PIN 4                 // FAN - GPIO4
#define MIST_PIN 16        // MIST - GPIO16
#define HEATER_PIN 17            // HEATER - GPIO17
#define AMPLIFIER_PIN 18   // AMPLIFIER - GPIO18

struct ZoneConfig; // khai báo trước để sử dụng trong các hàm
DHT dht(DHTPIN, DHTTYPE); // Khởi tạo đối tượng DHT với chân và loại cảm biến

bool getTimestamp(char *output, size_t outputSize); // Lấy timestamp hiện tại theo chuẩn ISO 8601, trả về false nếu đồng hồ chưa được đồng bộ
bool isClockTrusted(); // Kiểm tra xem đồng hồ đã được đồng bộ với NTP hay chưa
void publishState(); // Publish trạng thái hiện tại lên MQTT
void applyControlLogic(); // Áp dụng logic điều khiển
void setFanState(bool turnOn); // Đặt trạng thái quạt
bool setActuatorState(const String &actuator, bool turnOn); // Đặt trạng thái bộ phận điều khiển
bool getActuatorState(const String &actuator); // Lấy trạng thái bộ phận điều khiển
int getActuatorPin(const String &actuator); // Lấy chân của bộ phận điều khiển

// Initial AUTO thresholds agreed for the SwiftletCare prototype.
// CO2 values are currently derived from the Wokwi analog input; calibrate
// the conversion and thresholds for the real CO2 sensor before deployment.
#define FAN_ON_TEMP 31.0f // nhiet do tren 31 C thi bat quat
#define FAN_OFF_TEMP 30.0f // nhiet do duoi 30 C va CO2 hop le thi tat quat

#define FAN_ON_CO2_PPM 1000
#define FAN_OFF_CO2_PPM 800
#define AUDIO_START_HOUR 6
#define AUDIO_END_HOUR 18
#define VIETNAM_UTC_OFFSET_SECONDS (7 * 60 * 60) // UTC+7

bool fanState = false; // false = quạt tắt, true = quạt bật
bool humidifierState = false; // false = máy phun sương tắt, true = máy phun sương bật
bool heaterState = false; // false = máy sưởi tắt, true = máy sưởi bật
bool audioAmplifierState = false; // false = ampli tắt, true = ampli bật
bool safetyOverride = true; // true = an toàn, false = không an toàn (cảm biến lỗi hoặc dữ liệu cũ)

bool latestDhtValid = false; // true = dữ liệu DHT22 hợp lệ, false = dữ liệu DHT22 lỗi
bool haveValidTemperature = false; // true = đã có dữ liệu nhiệt độ hợp lệ, false = chưa có dữ liệu nhiệt độ hợp lệ

float latestTemperature = NAN; // Nhiệt độ gần nhất
float latestHumidity = NAN; // Độ ẩm gần nhất
int latestCo2 = 0; // Mức CO2 gần nhất
int latestLight = 0; // Mức ánh sáng gần nhất
bool latestCo2Valid = false; // true = dữ liệu CO2 hợp lệ, false = dữ liệu CO2 lỗi
bool latestLightValid = false; // true = dữ liệu ánh sáng hợp lệ, false = dữ liệu ánh sáng lỗi

unsigned long lastValidTemperatureMs = 0; // Thời điểm nhận được dữ liệu nhiệt độ hợp lệ gần nhất
unsigned long lastSensorSampleMs = 0; // Thời điểm lấy mẫu cảm biến gần nhất
unsigned long lastState = 0; // Thời điểm cập nhật trạng thái gần nhất

const unsigned long SENSOR_INTERVAL = 2000; // Khoảng thời gian giữa các lần lấy mẫu cảm biến (2 giây)
const unsigned long SENSOR_STALE_MS = 15000; // Dữ liệu cảm biến được coi là lỗi nếu không có dữ liệu hợp lệ trong 15 giây
const unsigned long STATE_INTERVAL = 5000;

String bootId; // ID khởi động
enum ControlMode
{
  MODE_AUTO,
  MODE_MANUAL
};

ControlMode controlMode = MODE_AUTO;
// Cache command_id đã xử lý, chỉ lưu trong RAM.
const int COMMAND_CACHE_SIZE = 64;

struct CachedCommand // Lưu trữ thông tin về các lệnh đã nhận và xử lý
{
  bool used = false;
  String commandId;
  String normalizedContent;
  String originalStatus;
  String originalReason;
};

CachedCommand commandCache[COMMAND_CACHE_SIZE]; // Mảng lưu trữ các lệnh đã nhận và xử lý
int commandCacheNext = 0; // Vị trí tiếp theo trong cache để lưu lệnh mới

int findCachedCommand(const String &commandId) // Tìm kiếm lệnh đã nhận trong cache, trả về chỉ số nếu tìm thấy, -1 nếu không tìm thấy
{
  for (int i = 0; i < COMMAND_CACHE_SIZE; i++)
  {
    if (commandCache[i].used &&
        commandCache[i].commandId == commandId)
    {
      return i; // Trả về chỉ số của lệnh đã nhận trong cache
    }
  }

  return -1; // Không tìm thấy vì chỉ số mảng bắt đầu từ 0, nên trả về -1 để báo lỗi
}

bool isValidUuid(const char *s)
{
  if (s == nullptr || strlen(s) != 36)
  {
    return false;
  }

  for (int i = 0; i < 36; i++)
  {
    if (i == 8 || i == 13 || i == 18 || i == 23)
    {
      if (s[i] != '-')
      {
        return false;
      }
    }
    else
    {
      char c = s[i];

      bool isDigit = c >= '0' && c <= '9';
      bool isLowerHex = c >= 'a' && c <= 'f';
      bool isUpperHex = c >= 'A' && c <= 'F';

      if (!isDigit && !isLowerHex && !isUpperHex)
      {
        return false;
      }
    }
  }

  return true;
}

void rememberCommand( // Lưu trữ thông tin về lệnh đã nhận và xử lý vào cache
    const String &commandId,
    const String &normalizedContent,
    const char *status,
    const char *reason)
{
  if (commandId.isEmpty())
  {
    return;
  }

  CachedCommand &entry = commandCache[commandCacheNext]; // Lấy tham chiếu đến vị trí tiếp theo trong cache để lưu lệnh mới
  entry.used = true;
  entry.commandId = commandId;
  entry.normalizedContent = normalizedContent;
  entry.originalStatus = status;
  entry.originalReason = reason;

  commandCacheNext = (commandCacheNext + 1) % COMMAND_CACHE_SIZE; // Cập nhật vị trí tiếp theo trong cache, nếu vượt quá kích thước cache thì quay lại đầu mảng
}
// WIFI CONFIGURATION
const char *WIFI_SSID = "Wokwi-GUEST";
const char *WIFI_PASSWORD = "";

// MQTT CONFIGURATION
const char *MQTT_HOST = "host.wokwi.internal"; // Địa chỉ MQTT Broker
const int MQTT_PORT = 1883;                    // Cổng MQTT Broker
// const int MQTT_PORT = 51884;

const char *HOUSE_ID = "1";
const char *ZONE_ID = "1";
const char *DEVICE_ID = "ESP32-HOUSE01-ZONE01";

const char *HEARTBEAT_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/heartbeat";
const char *TELEMETRY_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/telemetry";
const char *COMMAND_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/command";
const char *COMMAND_ACK_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/command_ack";
const char *STATE_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/state";
const char *CFG_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/cfg";
const char *CFG_ACK_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/cfg_ack";
const char *EVENTS_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/events";
const char *TELEMETRY_ACK_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/telemetry_ack";
Preferences preferences;
struct ZoneConfig
{
  uint32_t version = 1;
  float humidityOn = 75.0f;  // MIST ON below 75%
  float humidityOff = 80.0f; // MIST OFF at/above 80%
  float tempMin = 26.0f;     // HEATER ON below 26 C
  float tempMax = 31.0f;     // FAN ON above 31 C; HEATER OFF at/above 28 C
  uint32_t telemetryIntervalMs = 5000;
};
ZoneConfig zoneConfig;
unsigned long manualModeStartedMs = 0; // Thời điểm bắt đầu chế độ MANUAL, dùng để tự động chuyển về AUTO sau 10 phút
const unsigned long MANUAL_TIMEOUT_MS = 10UL * 60UL * 1000UL; // 10 phút
uint8_t dhtGoodStreak = 0; // Số lần đọc DHT liên tiếp hợp lệ, dùng để xác định trạng thái cảm biến DHT
bool dhtFaultActive = false;
uint32_t droppedTelemetryCount = 0;
unsigned long bootStartedMs = 0;

// MQTT CLIENT
WiFiClient espClient;               // Tạo kết nối TCP/IP
PubSubClient mqttClient(espClient); // Tạo MQTT Client

// TIMERS
unsigned long lastTelemetry = 0;
unsigned long lastHeartbeat = 0;

unsigned long TELEMETRY_INTERVAL = 5000;       // Gửi telemetry mỗi 5 giây
const unsigned long HEARTBEAT_INTERVAL = 2000; // Gửi heartbeat mỗi 2 giây
unsigned long messageCounter = 0; // Đếm số message đã tạo

bool ntpConfigured = false;
std::atomic<bool> mqttOnline{false};
std::atomic<bool> networkEnabled{true};
std::atomic<bool> mqttJustConnected{false};
std::atomic<bool> ntpSynced{false};
std::atomic<uint32_t> lastNtpSyncMs{0};
std::atomic<uint32_t> reconnectCount{0}, connectAttempts{0};
std::atomic<uint32_t> txFailures{0}, rxDrops{0};
uint32_t txQueueDrops = 0;
uint32_t bufferPeak = 0;
uint32_t loopMaxMs = 0;
bool simulateDhtFault = false, simulateCo2Fault = false;
bool simulateLightFault = false;
//Hàng đợi MQTT – Queue
struct MqttPacket // Cấu trúc lưu trữ thông tin về một gói MQTT
{
  char topic[160];
  uint8_t payload[768];
  uint16_t length;
  bool retained;
};
QueueHandle_t mqttTxQueue = nullptr, mqttRxQueue = nullptr; // Hàng đợi gửi và nhận MQTT
TaskHandle_t networkTaskHandle = nullptr; // là một con trỏ đến một đối tượng TaskHandle_t, được sử dụng để quản lý và điều khiển một tác vụ (task) trong hệ thống FreeRTOS. Nó được sử dụng để lưu trữ thông tin về tác vụ mạng (network task) trong chương trình, cho phép các phần khác của chương trình có thể tương tác với tác vụ này, ví dụ như dừng hoặc kiểm tra trạng thái của nó.
bool queuePublish(const char *topic, const uint8_t *payload,
                  unsigned int length, bool retained = false); // Hàm này được sử dụng để đưa một gói MQTT vào hàng đợi gửi (mqttTxQueue). Nó nhận các tham số: topic (chủ đề của gói), payload (dữ liệu của gói), length (độ dài của payload) và retained (cờ xác định xem gói có được giữ lại trên broker hay không). Hàm trả về true nếu gói được đưa vào hàng đợi thành công, ngược lại trả về false nếu hàng đợi đầy hoặc gặp lỗi.
bool queuePublish(const char *topic, const char *payload,
                  bool retained = false); // Hàm này được sử dụng để đưa một gói MQTT vào hàng đợi gửi (mqttTxQueue). Nó nhận các tham số: topic (chủ đề của gói), payload (dữ liệu của gói dưới dạng chuỗi ký tự) và retained (cờ xác định xem gói có được giữ lại trên broker hay không). Hàm trả về true nếu gói được đưa vào hàng đợi thành công, ngược lại trả về false nếu hàng đợi đầy hoặc gặp lỗi.
void processMqttMessages();
void networkTask(void *);
void onNetworkMessage(char *, byte *, unsigned int);
void processSerialTests();
void flushSensorEvents();
void printZoneConfig();
bool validateZoneConfig(const ZoneConfig &);
bool saveZoneConfig(const ZoneConfig &);
void onTimeSync(struct timeval *)
{
  lastNtpSyncMs.store(millis());
  ntpSynced.store(true);
  Serial.println("[CLOCK] SNTP sync OK");
}
// WIFI CONNECT
void connectWiFi()
{
  Serial.println("Starting WiFi connection...");

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD, 6);
}
unsigned long lastWiFiAttempt = 0;
const unsigned long WIFI_RETRY_INTERVAL = 5000;

void maintainWiFi() // Kiểm tra trạng thái WiFi và kết nối lại nếu bị mất kết nối
{
  if (WiFi.status() == WL_CONNECTED)
  {
    return;
  }

  if (millis() - lastWiFiAttempt >= WIFI_RETRY_INTERVAL)
  {
    lastWiFiAttempt = millis();

    Serial.println("WiFi disconnected; retrying...");
    WiFi.disconnect();
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD, 6);
  }
}

// OFFLINE TELEMETRY BUFFER
#define BUFFER_SIZE 120 // 10 phút ở chu kỳ 5s; RAM-only, mất khi reset
#define TELEMETRY_RETRY_INTERVAL 5000UL

// Cấu trúc lưu message khi MQTT bị mất kết nối
struct BufferedMessage
{
  String topic;
  String payload;
  unsigned long lastSentMs = 0;
};

BufferedMessage messageBuffer[BUFFER_SIZE];
int bufferCount = 0; // Số message hiện đang được lưu

// Thêm message vào buffer khi MQTT không gửi được
bool addToBuffer(const char *topic, const char *payload)
{
  if (bufferCount >= BUFFER_SIZE)
  {
    droppedTelemetryCount++;
    Serial.printf(
        "[BUFFER] FULL; newest telemetry dropped | total=%lu\n",
        (unsigned long)droppedTelemetryCount);
    return false;
  }

  messageBuffer[bufferCount].topic = topic;
  messageBuffer[bufferCount].payload = payload;
  messageBuffer[bufferCount].lastSentMs = 0;

  bufferCount++;
  if ((uint32_t)bufferCount > bufferPeak)
    bufferPeak = bufferCount;

  Serial.printf("Telemetry buffered. Count = %d\n", bufferCount);
  return true;
}

void removeBufferedTelemetryByEventId(const String &eventId)
{
  for (int i = 0; i < bufferCount; i++) // Duyệt qua tất cả các message trong buffer
  {
    JsonDocument doc; // Khởi tạo một đối tượng JsonDocument để lưu trữ dữ liệu JSON được giải mã từ payload của message trong buffer
    DeserializationError error = deserializeJson(doc, messageBuffer[i].payload); // Giải mã payload của message trong buffer thành đối tượng JSON và lưu trữ vào doc. Nếu giải mã thành công, error sẽ có giá trị Ok, ngược lại sẽ có giá trị khác Ok.

    if (error || !doc["event_id"].is<const char *>())
    {
      continue;
    }
    if (doc["event_id"].as<String>() != eventId)
    {
      continue;
    }

    // Dịch các bản tin còn lại để lấp vị trí vừa xoá.
    for (int j = i; j < bufferCount - 1; j++)
    {
      messageBuffer[j] = messageBuffer[j + 1];
    }
    bufferCount--;

    // Giải phóng dữ liệu còn giữ ở ô cuối.
    messageBuffer[bufferCount].topic = String();
    messageBuffer[bufferCount].payload = String();
    messageBuffer[bufferCount].lastSentMs = 0;

    Serial.printf(
        "[BUFFER] Removed STORED: %s | remaining=%d\n",
        eventId.c_str(),
        bufferCount);
    return;
  }
  //Bỏ qua nếu không tìm thấy eventId
  Serial.printf(
      "[BUFFER] ACK ignored; event_id not found: %s\n",
      eventId.c_str());
}
// Xử lý ACK từ backend khi nhận được telemetry đã được lưu trữ thành công
void handleTelemetryAck(byte *payload, unsigned int length)
{
  JsonDocument doc;
  DeserializationError error = deserializeJson(doc, payload, length);

  if (error || !doc.is<JsonObject>())// Nếu giải mã thất bại hoặc dữ liệu không phải là một đối tượng JSON
  {
    Serial.println("[TELEMETRY_ACK] Invalid JSON");
    return;
  }

  if (!doc["event_id"].is<const char *>() ||
      !doc["status"].is<const char *>() ||
      !doc["device_id"].is<const char *>())
  {
    Serial.println("[TELEMETRY_ACK] Missing or invalid fields");
    return;
  }

  String ackDeviceId = doc["device_id"].as<String>();// Lấy giá trị device_id từ ACK
  if (ackDeviceId != DEVICE_ID)
  {
    Serial.println("[TELEMETRY_ACK] Ignored ACK for another device");
    return;
  }

  String eventId = doc["event_id"].as<String>();
  String status = doc["status"].as<String>();
  if (eventId.isEmpty() || status != "STORED")
  {
    Serial.println("[TELEMETRY_ACK] ACK not accepted");
    return;
  }

  removeBufferedTelemetryByEventId(eventId);
}

void resendBufferedMessages()
{
  if (!mqttOnline.load() || bufferCount == 0)
    return;
  // Lấy thời gian hiện tại tính bằng mili giây kể từ khi ESP32 khởi động
  unsigned long now = millis(); // Lấy thời gian hiện tại tính bằng mili giây kể từ khi ESP32 khởi động
  static unsigned long lastPass = 0; // Lưu thời điểm lần cuối cùng thực hiện việc gửi lại các message trong buffer
  static int cursor = 0; // Con trỏ để duyệt qua các message trong buffer, bắt đầu từ vị trí 0
  if (now - lastPass < 50) // Nếu thời gian kể từ lần gửi cuối cùng chưa đủ 50 mili giây, thì không thực hiện gửi lại
    return;
  lastPass = now; // Cập nhật thời điểm lần cuối cùng thực hiện việc gửi lại các message trong buffer
  int scanned = 0, sent = 0; // Biến đếm số message đã quét và số message đã gửi thành công
  while (scanned < bufferCount && sent < 2) // Quét tối đa 2 message trong buffer để gửi lại, hoặc quét hết tất cả các message trong buffer
  {
    if (cursor >= bufferCount) // Nếu con trỏ đã vượt quá số lượng message trong buffer, thì quay lại vị trí đầu tiên
      cursor = 0;
    BufferedMessage &item = messageBuffer[cursor++]; //
    scanned++;
    if (item.lastSentMs && now - item.lastSentMs < TELEMETRY_RETRY_INTERVAL)
      continue;
    if (!queuePublish(item.topic.c_str(), item.payload.c_str()))
      break;
    item.lastSentMs = now;
    sent++;
  }
}
// MQTT CONNECT
unsigned long lastMQTTAttempt = 0; // Thời điểm lần cuối cùng thử kết nối MQTT
const unsigned long MQTT_RETRY_INTERVAL = 3000; // Thử reconnect mỗi 3 giây

// Kiểm tra và kết nối lại MQTT khi bị mất kết nối
void tryConnectMQTT()
{
  static uint32_t retryMs = 1000; // Thời gian chờ giữa các lần thử kết nối MQTT, bắt đầu từ 1 giây
  if (mqttClient.connected()) 
    return;
  if (millis() - lastMQTTAttempt < retryMs)
    return;

  lastMQTTAttempt = millis(); // Cập nhật thời điểm lần cuối cùng thử kết nối MQTT
  connectAttempts.fetch_add(1); // Tăng số lần thử kết nối MQTT lên 1
  Serial.printf("[MQTT] connect attempt=%lu\n", (unsigned long)connectAttempts.load());
  if (!mqttClient.connect(DEVICE_ID))
  {
    retryMs = retryMs < 15000 ? retryMs * 2 : 30000; // Tăng thời gian chờ giữa các lần thử kết nối MQTT lên gấp đôi, tối đa là 30 giây
    Serial.printf("[MQTT] failed rc=%d retry_ms=%lu\n",mqttClient.state(), (unsigned long)retryMs);
    return;
  }
  bool subscribed = mqttClient.subscribe(CFG_TOPIC, 1); // Đăng ký nhận các message từ topic CFG_TOPIC với QoS 1
  subscribed = mqttClient.subscribe(COMMAND_TOPIC, 1) && subscribed; // Đăng ký nhận các message từ topic COMMAND_TOPIC với QoS 1, nếu đăng ký thành công cả hai topic thì subscribed sẽ là true
  subscribed = mqttClient.subscribe(TELEMETRY_ACK_TOPIC, 1) && subscribed; // Đăng ký nhận các message từ topic TELEMETRY_ACK_TOPIC với QoS 1, nếu đăng ký thành công cả ba topic thì subscribed sẽ là true
  if (!subscribed)
  {
    Serial.println("[MQTT] subscription send failed; reconnecting");
    mqttClient.disconnect();
    return;
  }
  retryMs = 1000;
  reconnectCount.fetch_add(1);
  mqttOnline.store(true); // Cập nhật trạng thái MQTT là online
  mqttJustConnected.store(true); // Cập nhật trạng thái MQTT là vừa kết nối
  Serial.println("[MQTT] CONNECTED; subscribed cfg + command + telemetry_ack");
}

void publishTelemetry(
    float temperature,
    float humidity,
    int co2,
    int light,
    bool dhtValid,
    bool co2Valid,
    bool lightValid)
{
  char timestamp[32];

  // Chi gui telemetry khi dong ho da duoc dong bo.
  if (!getTimestamp(timestamp, sizeof(timestamp)))
  {
    Serial.println("Telemetry skipped: clock not trusted");
    return;
  }

  messageCounter++;

  // Tao event_id rieng cho tung ban tin telemetry.
  char eventId[100];

  snprintf(
      eventId,
      sizeof(eventId),
      "%s-%08lx%08lx-%lu",
      DEVICE_ID,
      (unsigned long)esp_random(),
      (unsigned long)esp_random(),
      messageCounter);

  // Xac dinh trang thai cam bien.
  int validCount = (dhtValid ? 1 : 0) + (co2Valid ? 1 : 0) + (lightValid ? 1 : 0);

  const char *sensorStatus;

  if (validCount == 3)
  {
    sensorStatus = "OK";
  }
  else if (validCount == 0)
  {
    sensorStatus = "ERROR";
  }
  else
  {
    sensorStatus = "PARTIAL_ERROR";
  }

  char payload[512];

  // Dung JSON document de cac gia tri loi co the la null.
  JsonDocument doc;

  doc["event_id"] = eventId;
  doc["device_id"] = DEVICE_ID;
  doc["timestamp"] = timestamp;
  doc["sensor_status"] = sensorStatus;

  // DHT22: nhiet do va do am cung hop le hoac cung loi.
  if (dhtValid)
  {
    doc["temperature"] = temperature;
    doc["humidity"] = humidity;
  }
  else
  {
    doc["temperature"] = nullptr;
    doc["humidity"] = nullptr;
  }

  if (co2Valid)
  {
    doc["co2"] = co2;
  }
  else
  {
    doc["co2"] = nullptr;
  }

  if (lightValid)
  {
    doc["light"] = light;
  }
  else
  {
    doc["light"] = nullptr;
  }

  size_t payloadLength = serializeJson(doc, payload, sizeof(payload));

  if (payloadLength == 0 || payloadLength >= sizeof(payload))
  {
    Serial.println("Telemetry JSON serialization failed");
    return;
  }

  Serial.print("TELEMETRY: ");
  Serial.println(payload);

  // Luôn lưu telemetry trước để chờ Backend xác nhận STORED.
  if (!addToBuffer(TELEMETRY_TOPIC, payload))
  {
    Serial.println("Could not buffer telemetry");
    return;
  }

  // Nếu MQTT đang kết nối, thử gửi ngay.
  // Message vẫn nằm trong buffer cho đến khi nhận ACK STORED.
  resendBufferedMessages();
}
// HEARTBEAT
// Gửi trạng thái hoạt động của ESP32 định kỳ
// HEARTBEAT
// Đồng bộ schema heartbeat theo code nhóm trưởng.
void publishHeartbeat()
{
  if (!mqttOnline.load())
  {
    return;
  }

  char timestamp[32];

  if (!getTimestamp(timestamp, sizeof(timestamp)))
  {
    Serial.println("Heartbeat skipped: clock not trusted");
    return;
  }

  char payload[256];

  snprintf(
      payload,
      sizeof(payload),
      "{"
      "\"device_id\":\"%s\","
      "\"timestamp\":\"%s\""
      "}",
      DEVICE_ID,
      timestamp);

  bool sent = queuePublish(
      HEARTBEAT_TOPIC,
      payload);

  if (sent)
  {
    Serial.print("HEARTBEAT queued: ");
    Serial.println(payload);
  }
  else
  {
    Serial.println("HEARTBEAT publish failed");
  }
}

int getActuatorPin(const String &actuator)
{
  if (actuator == "FAN") return FAN_PIN;
  if (actuator == "MIST") return MIST_PIN;
  if (actuator == "HEATER") return HEATER_PIN;
  if (actuator == "AMPLIFIER") return AMPLIFIER_PIN;
  return -1;
}

bool getActuatorState(const String &actuator)
{
  if (actuator == "FAN") return fanState;
  if (actuator == "MIST") return humidifierState;
  if (actuator == "HEATER") return heaterState;
  if (actuator == "AMPLIFIER") return audioAmplifierState;
  return false;
}

bool setActuatorState(const String &actuator, bool turnOn)
{
  int pin = getActuatorPin(actuator);
  if (pin < 0) return false;

  bool *state = nullptr;
  if (actuator == "FAN") state = &fanState;
  else if (actuator == "MIST") state = &humidifierState;
  else if (actuator == "HEATER") state = &heaterState;
  else if (actuator == "AMPLIFIER") state = &audioAmplifierState;

  if (state == nullptr) return false;

  bool changed = (*state != turnOn);
  if (!changed)
    return true;

  *state = turnOn;
  digitalWrite(pin, turnOn ? HIGH : LOW);

  Serial.printf("%s=%s GPIO%d=%s\n",
                actuator.c_str(),
                turnOn ? "ON" : "OFF",
                pin,
                digitalRead(pin) == HIGH ? "HIGH" : "LOW");

  publishState();
  return true;
}

void publishCommandAck(
    const String &commandId,
    const String &status,
    const String &reason,
    bool isDuplicate = false,
    const String &originalStatus = "",
    const String &originalReason = "",
    const String &actuator = "FAN")
{
  JsonDocument doc;

  doc["device_id"] = DEVICE_ID;
  doc["command_id"] = commandId;
  doc["actuator"] = actuator;
  doc["status"] = status;
  doc["reason"] = reason;
  doc["mode"] = controlMode == MODE_AUTO ? "AUTO" : "MANUAL";
  doc["state"] = getActuatorState(actuator) ? "ON" : "OFF";
  int ackPin = getActuatorPin(actuator);
  doc["gpio_state"] = (ackPin >= 0 && digitalRead(ackPin) == HIGH) ? "HIGH" : "LOW";
  doc["safetyOverride"] = safetyOverride;

  char timestamp[25];

  if (getTimestamp(timestamp, sizeof(timestamp)))
  {
    doc["timestamp"] = timestamp;
  }
  else
  {
    doc["timestamp"] = nullptr;
  }

  if (isDuplicate)
  {
    doc["originalStatus"] = originalStatus;
    doc["originalReason"] = originalReason;
  }

  char payload[512];

  size_t length = serializeJson(doc, payload, sizeof(payload));

  if (length == 0 || length >= sizeof(payload))
  {
    Serial.println("[COMMAND_ACK] Serialization failed");
    return;
  }

  Serial.println("========== COMMAND ACK ==========");
  Serial.print("[COMMAND_ACK] command_id: ");
  Serial.println(commandId);

  Serial.print("[COMMAND_ACK] status: ");
  Serial.println(status);

  Serial.print("[COMMAND_ACK] reason: ");
  Serial.println(reason);

  Serial.print("[COMMAND_ACK] payload: ");
  Serial.println(payload);

  if (!mqttOnline.load())
  {
    Serial.println("[COMMAND_ACK] MQTT disconnected; ACK not published");
    return;
  }

  bool published = queuePublish(
      COMMAND_ACK_TOPIC,
      (const uint8_t *)payload,
      length,
      false);

  Serial.println(
      published
          ? "[COMMAND_ACK] Queued for MQTT"
          : "[COMMAND_ACK] Publish failed");
}

void publishState()
{
  if (!mqttOnline.load())
  {
    return;
  }

  char timestamp[32];
  bool clockTrusted = getTimestamp(timestamp, sizeof(timestamp));

  JsonDocument doc;

  doc["device_id"] = DEVICE_ID;
  doc["mode"] = controlMode == MODE_AUTO ? "AUTO" : "MANUAL";
  // Legacy fields remain mapped to FAN for backward compatibility.
  doc["actuator"] = "FAN";
  doc["state"] = fanState ? "ON" : "OFF";
  doc["gpio_state"] = digitalRead(FAN_PIN) == HIGH ? "HIGH" : "LOW";
  JsonObject actuators = doc["actuators"].to<JsonObject>();
  actuators["FAN"] = fanState ? "ON" : "OFF";
  actuators["MIST"] = humidifierState ? "ON" : "OFF";
  actuators["HEATER"] = heaterState ? "ON" : "OFF";
  actuators["AMPLIFIER"] = audioAmplifierState ? "ON" : "OFF";
  doc["safetyOverride"] = safetyOverride;

  if (clockTrusted)
  {
    doc["timestamp"] = timestamp;
  }
  else
  {
    doc["timestamp"] = nullptr;
  }

  doc["boot_id"] = bootId;

  char payload[512];
  size_t length = serializeJson(doc, payload, sizeof(payload));

  if (length == 0 || length >= sizeof(payload))
  {
    Serial.println("STATE serialization failed");
    return;
  }

  Serial.print("[STATE] Publishing all actuator states: ");
  Serial.println(payload);
  bool queued = queuePublish(STATE_TOPIC, payload, false);
  Serial.println(queued ? "[STATE] Queued to MQTT" : "[STATE] Queue failed");
}
void setFanState(bool turnOn)
{
  setActuatorState("FAN", turnOn);
}

bool isAudioScheduleActive()
{
  if (!isClockTrusted())
    return false;

  // Firmware stores UTC time; convert to Vietnam local time (UTC+7).
  time_t localNow = time(nullptr) + VIETNAM_UTC_OFFSET_SECONDS;
  struct tm localTime;
  if (!gmtime_r(&localNow, &localTime))
    return false;

  // Prototype schedule: daily 06:00 inclusive to 18:00 exclusive.
  return localTime.tm_hour >= AUDIO_START_HOUR &&
        localTime.tm_hour < AUDIO_END_HOUR;
}

void applyControlLogic()
{
  bool fresh = latestDhtValid && haveValidTemperature &&
              millis() - lastValidTemperatureMs <= SENSOR_STALE_MS;
  bool safe = !fresh || dhtFaultActive || dhtGoodStreak < 3;
  bool overrideChanged = safetyOverride != safe;
  safetyOverride = safe;

  if (controlMode != MODE_AUTO)
  {
    if (overrideChanged)
      publishState();
    return;
  }

  // Invalid/stale temperature or humidity must not drive humidifier/heater.
  if (!fresh || safe)
  {
    setActuatorState("MIST", false);
    setActuatorState("HEATER", false);
    // Keep the existing conservative FAN behavior when DHT22 is faulty.
    setFanState(true);
  }
  else
  {
    // MIST hysteresis: ON below 75%, OFF at/above 80%.
    if (!humidifierState && latestHumidity < zoneConfig.humidityOn)
      setActuatorState("MIST", true);
    else if (humidifierState && latestHumidity >= zoneConfig.humidityOff)
      setActuatorState("MIST", false);

// FAN hysteresis:
// ON when temperature >= 31 C OR CO2 > 1000 ppm.
// OFF only when temperature <= 30 C AND valid CO2 < 800 ppm.
bool highTemperature = latestTemperature >= FAN_ON_TEMP;
bool highCo2 = latestCo2Valid && latestCo2 > FAN_ON_CO2_PPM;
bool fanShouldTurnOn = highTemperature || highCo2;
bool fanCanTurnOff = latestTemperature <= FAN_OFF_TEMP &&
                    latestCo2Valid &&
                    latestCo2 < FAN_OFF_CO2_PPM;
    if (!fanState && fanShouldTurnOn)
      setFanState(true);
    else if (fanState && fanCanTurnOff)
      setFanState(false);

    // HEATER: ON below 26 C; OFF at/above 28 C. Do not heat while FAN runs.
    if (latestTemperature >= 28.0f || fanState)
      setActuatorState("HEATER", false);
    else if (!heaterState && latestTemperature < zoneConfig.tempMin)
      setActuatorState("HEATER", true);
  }

  // AMPLIFIER follows a daily schedule, not climate sensor thresholds.
  setActuatorState("AMPLIFIER", isAudioScheduleActive());

  if (overrideChanged)
    publishState();
}
time_t utcToEpoch(struct tm *t)
{
  // Convert calendar date to days since 1970-01-01.
  int year = t->tm_year + 1900;
  unsigned int month = t->tm_mon + 1;
  int day = t->tm_mday;

  year -= (month <= 2);

  const int era = (year >= 0 ? year : year - 399) / 400;
  const unsigned int yearOfEra = (unsigned int)(year - era * 400);
  const unsigned int dayOfYear =
      (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
  const unsigned int dayOfEra =
      yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;

  int days = era * 146097 + (int)dayOfEra - 719468;

  return (time_t)days * 86400 + (time_t)t->tm_hour * 3600 + (time_t)t->tm_min * 60 + t->tm_sec;
}
bool isValidUtcDate(
    int year,
    int month,
    int day,
    int hour,
    int minute,
    int second)
{
  if (year < 1970 || year > 2037 ||
      month < 1 || month > 12 ||
      hour < 0 || hour > 23 ||
      minute < 0 || minute > 59 ||
      second < 0 || second > 59)
  {
    return false;
  }

  const int daysInMonth[] = {
      31, 28, 31, 30, 31, 30,
      31, 31, 30, 31, 30, 31};

  int maxDay = daysInMonth[month - 1];

  bool leapYear =
      (year % 400 == 0) ||
      (year % 4 == 0 && year % 100 != 0);

  if (month == 2 && leapYear)
  {
    maxDay = 29;
  }

  return day >= 1 && day <= maxDay;
}
bool hasStrictUtcFormat(const char *s)
{
  if (s == nullptr || strlen(s) != 20)
  {
    return false;
  }

  const int positions[] = {4, 7, 10, 13, 16, 19};
  const char expected[] = {'-', '-', 'T', ':', ':', 'Z'};

  for (int i = 0; i < 6; i++)
  {
    if (s[positions[i]] != expected[i])
    {
      return false;
    }
  }

  for (int i = 0; i < 19; i++)
  {
    bool separator = false;

    for (int j = 0; j < 6; j++)
    {
      if (i == positions[j])
      {
        separator = true;
        break;
      }
    }

    if (!separator && (s[i] < '0' || s[i] > '9'))
    {
      return false;
    }
  }

  return true;
}
bool parseUtcExpiry(const char *value, time_t &expiry)
{
  if (!hasStrictUtcFormat(value))
  {
    return false;
  }

  int year, month, day;
  int hour, minute, second;
  char trailing;

  // Chỉ chấp nhận định dạng UTC: YYYY-MM-DDTHH:MM:SSZ
  int fields = sscanf(
      value,
      "%4d-%2d-%2dT%2d:%2d:%2dZ%c",
      &year, &month, &day,
      &hour, &minute, &second,
      &trailing);

  if (fields != 6)
  {
    return false;
  }

  if (!isValidUtcDate(year, month, day, hour, minute, second))
  {
    return false;
  }
  struct tm timeInfo = {};
  timeInfo.tm_year = year - 1900;
  timeInfo.tm_mon = month - 1;
  timeInfo.tm_mday = day;
  timeInfo.tm_hour = hour;
  timeInfo.tm_min = minute;
  timeInfo.tm_sec = second;

  // Chuyển thời gian UTC thành Unix timestamp.
  expiry = utcToEpoch(&timeInfo);

  return expiry > 0;
}
void rejectAndCache(
    const String &commandId,
    const String &normalizedContent,
    const char *reason,
    const String &actuator)
{
  rememberCommand(commandId, normalizedContent, "REJECTED", reason);
  publishCommandAck(commandId, "REJECTED", reason, false, "", "", actuator);
}

void publishCfgAck(uint32_t version, const char *status, const char *reason)
{
  if (!mqttOnline.load())
    return;
  char timestamp[32] = "";
  bool timeValid = getTimestamp(timestamp, sizeof(timestamp));
  JsonDocument d;
  d["device_id"] = DEVICE_ID;
  d["version"] = version;
  d["status"] = status;
  d["reason"] = reason;
  if (timeValid)
    d["timestamp"] = timestamp;
  else
    d["timestamp"] = nullptr;
  char out[320];
  size_t n = serializeJson(d, out, sizeof(out));
  if (n && n < sizeof(out))
    queuePublish(CFG_ACK_TOPIC, out, false);
  Serial.printf("[CFG_ACK] version=%lu status=%s reason=%s\n", (unsigned long)version, status, reason);
}
bool validateZoneConfig(const ZoneConfig &c)
{
  return c.version >= 1 && isfinite(c.humidityOn) && isfinite(c.humidityOff) &&
         isfinite(c.tempMin) && isfinite(c.tempMax) &&
         c.humidityOn >= 0 && c.humidityOff <= 100 &&
         c.humidityOn < c.humidityOff && c.tempMin >= -10 &&
         c.tempMax <= 60 && c.tempMin < c.tempMax &&
         c.telemetryIntervalMs >= 1000 && c.telemetryIntervalMs <= 60000;
}

// Single NVS blob: all values and the version are committed as one item.
// CRC rejects a corrupted record; no code depends on struct padding.
uint32_t configChecksum(const uint8_t *bytes, size_t size)
{
  uint32_t hash = 2166136261UL;
  for (size_t i = 0; i < size; i++)
    hash = (hash ^ bytes[i]) * 16777619UL;
  return hash;
}
bool saveZoneConfig(const ZoneConfig &c)
{
  JsonDocument doc;
  doc["version"] = c.version;
  doc["humidity_on"] = c.humidityOn;
  doc["humidity_off"] = c.humidityOff;
  doc["temp_min"] = c.tempMin;
  doc["temp_max"] = c.tempMax;
  doc["telemetry_interval_ms"] = c.telemetryIntervalMs;
  uint8_t record[388] = {};
  size_t n = serializeJson(doc, record + 4, sizeof(record) - 4);
  if (!n || n >= sizeof(record) - 4)
    return false;
  uint32_t crc = configChecksum(record + 4, n);
  memcpy(record, &crc, 4);
  if (!preferences.begin("swiftlet", false))
    return false;
  size_t written = preferences.putBytes("config2", record, n + 4);
  preferences.end();
  return written == n + 4;
}
void printZoneConfig()
{
  Serial.printf("[CFG] version=%lu humidity_on=%.2f humidity_off=%.2f "
                "temp_min=%.2f temp_max=%.2f interval_ms=%lu\n",
                (unsigned long)zoneConfig.version, zoneConfig.humidityOn,
                zoneConfig.humidityOff, zoneConfig.tempMin, zoneConfig.tempMax,
                (unsigned long)zoneConfig.telemetryIntervalMs);
}
void loadZoneConfig()
{
  zoneConfig = ZoneConfig();
  bool restored = false, migrate = false;
  if (preferences.begin("swiftlet", true))
  {
    size_t n = preferences.getBytesLength("config2");
    if (n >= 5 && n <= 388)
    {
      uint8_t record[388] = {};
      uint32_t crc = 0;
      if (preferences.getBytes("config2", record, n) == n)
      {
        memcpy(&crc, record, 4);
        JsonDocument doc;
        if (crc == configChecksum(record + 4, n - 4) &&
            !deserializeJson(doc, record + 4, n - 4))
        {
          ZoneConfig c;
          c.version = doc["version"].as<uint32_t>();
          c.humidityOn = doc["humidity_on"].as<float>();
          c.humidityOff = doc["humidity_off"].as<float>();
          c.tempMin = doc["temp_min"].as<float>();
          c.tempMax = doc["temp_max"].as<float>();
          c.telemetryIntervalMs = doc["telemetry_interval_ms"].as<uint32_t>();
          if (validateZoneConfig(c))
          {
            zoneConfig = c;
            restored = true;
          }
        }
      }
    }
    else if (n == 0 && preferences.isKey("ver"))
    {
      // Import valid legacy P1 configuration once; retain old keys as fallback.
      ZoneConfig c;
      c.version = preferences.getUInt("ver", 1);
      c.humidityOn = preferences.getFloat("hon", 75);
      c.humidityOff = preferences.getFloat("hoff", 80);
      c.tempMin = preferences.getFloat("tmin", 26);
      c.tempMax = preferences.getFloat("tmax", 31);
      c.telemetryIntervalMs = preferences.getUInt("tele", 5000);
      if (validateZoneConfig(c))
      {
        zoneConfig = c;
        restored = true;
        migrate = true;
      }
    }
    preferences.end();
  }
  if (migrate && !saveZoneConfig(zoneConfig))
    Serial.println("[CFG] legacy import in RAM; NVS save failed");
  TELEMETRY_INTERVAL = zoneConfig.telemetryIntervalMs;
  Serial.println(restored ? "[CFG] restored" : "[CFG] using valid defaults");
  printZoneConfig();
}
void handleConfigMessage(byte *payload, unsigned int length)
{
  JsonDocument d;
  if (deserializeJson(d, payload, length) != DeserializationError::Ok || !d.is<JsonObject>())
  {
    publishCfgAck(0, "REJECTED", "INVALID_JSON");
    return;
  }
  if (!d["version"].is<uint32_t>())
  {
    publishCfgAck(0, "REJECTED", "INVALID_VERSION");
    return;
  }
  uint32_t v = d["version"].as<uint32_t>();
  if (v < zoneConfig.version)
  {
    publishCfgAck(v, "STALE", "OLDER_VERSION");
    return;
  }
  if (!d["humidity_on"].is<float>() && !d["humidity_on"].is<int>())
  {
    publishCfgAck(v, "REJECTED", "INVALID_HUMIDITY_ON");
    return;
  }
  if (!d["humidity_off"].is<float>() && !d["humidity_off"].is<int>())
  {
    publishCfgAck(v, "REJECTED", "INVALID_HUMIDITY_OFF");
    return;
  }
  if (!d["temp_min"].is<float>() && !d["temp_min"].is<int>())
  {
    publishCfgAck(v, "REJECTED", "INVALID_TEMP_MIN");
    return;
  }
  if (!d["temp_max"].is<float>() && !d["temp_max"].is<int>())
  {
    publishCfgAck(v, "REJECTED", "INVALID_TEMP_MAX");
    return;
  }
  if (!d["telemetry_interval_ms"].is<uint32_t>())
  {
    publishCfgAck(v, "REJECTED", "INVALID_TELEMETRY_INTERVAL");
    return;
  }
  ZoneConfig c = zoneConfig;
  c.version = v;
  c.humidityOn = d["humidity_on"].as<float>();
  c.humidityOff = d["humidity_off"].as<float>();
  c.tempMin = d["temp_min"].as<float>();
  c.tempMax = d["temp_max"].as<float>();
  c.telemetryIntervalMs = d["telemetry_interval_ms"].as<uint32_t>();
  if (!validateZoneConfig(c))
  {
    publishCfgAck(v, "REJECTED", "RANGE_OR_THRESHOLD_INVALID");
    return;
  }
  if (v == zoneConfig.version)
  {
    bool same = c.humidityOn == zoneConfig.humidityOn &&
                c.humidityOff == zoneConfig.humidityOff &&
                c.tempMin == zoneConfig.tempMin && c.tempMax == zoneConfig.tempMax &&
                c.telemetryIntervalMs == zoneConfig.telemetryIntervalMs;
    publishCfgAck(v, same ? "ALREADY_CURRENT" : "REJECTED",
                  same ? "SAME_VERSION" : "VERSION_CONFLICT");
    return;
  }
  if (!saveZoneConfig(c))
  {
    publishCfgAck(v, "REJECTED", "PERSISTENCE_FAILED");
    return;
  }
  zoneConfig = c;
  TELEMETRY_INTERVAL = c.telemetryIntervalMs;
  printZoneConfig();
  applyControlLogic();
  publishState();
  publishCfgAck(v, "APPLIED", "OK");
}
// Fault transitions are kept as fields until both MQTT and the clock are ready.
// This avoids sending timestamp:null, which the current Backend rejects.
struct PendingSensorEvent
{
  String type;
  String sensor;
  String reason;
};
PendingSensorEvent sensorEvents[16];
uint8_t sensorEventCount = 0;
uint32_t sensorEventsDropped = 0;

void publishSensorEvent(const char *type, const char *sensor, const char *reason)
{
  if (!type || !sensor || !reason)
    return;

  // The currently deployed Backend accepts SENSOR_FAULT and MANUAL_LIMIT,
  // but not SENSOR_RECOVERED. Keep recovery visible locally until both sides
  // agree on the event contract; do not send an event known to be rejected.
  if (strcmp(type, "SENSOR_RECOVERED") == 0)
  {
    Serial.printf("[EVENT] %s sensor=%s (local only; Backend contract not updated)\n",
                  type, sensor);
    return;
  }

  if (sensorEventCount >= 16)
  {
    sensorEventsDropped++;
    Serial.printf("[EVENT] queue full; dropped=%lu\n",
                  (unsigned long)sensorEventsDropped);
    return;
  }

  PendingSensorEvent &event = sensorEvents[sensorEventCount++];
  event.type = type;
  event.sensor = sensor;
  event.reason = reason;
  Serial.printf("[EVENT] %s sensor=%s queued=%u dropped=%lu\n", type, sensor,
                sensorEventCount, (unsigned long)sensorEventsDropped);
}

void flushSensorEvents()
{
  if (!mqttOnline.load() || !sensorEventCount)
    return;

  char timestamp[32];
  if (!getTimestamp(timestamp, sizeof(timestamp)))
  {
    // Keep the event queued; retry after SNTP has synchronized.
    return;
  }

  JsonDocument doc;
  doc["device_id"] = DEVICE_ID;
  doc["event_type"] = sensorEvents[0].type;
  doc["sensor"] = sensorEvents[0].sensor;
  doc["reason"] = sensorEvents[0].reason;
  doc["timestamp"] = timestamp;

  String json;
  if (serializeJson(doc, json) == 0 || json.isEmpty())
  {
    Serial.println("[EVENT] JSON serialization failed; event retained");
    return;
  }

  if (!queuePublish(EVENTS_TOPIC, json.c_str()))
    return;

  for (int i = 0; i < sensorEventCount - 1; i++)
    sensorEvents[i] = sensorEvents[i + 1];
  sensorEvents[--sensorEventCount] = PendingSensorEvent{};
}

void mqttCallback(
    char *topic,
    byte *payload,
    unsigned int length)
{
  if (length == 0 || length > 512)
  {
    Serial.println("Rejected: invalid payload length");
    return;
  }

  // Xử lý ACK telemetry trước.
  if (strcmp(topic, TELEMETRY_ACK_TOPIC) == 0)
  {
    handleTelemetryAck(payload, length);
    return;
  }
  if (strcmp(topic, CFG_TOPIC) == 0)
  {
    handleConfigMessage(payload, length);
    return;
  }

  // Các phần còn lại chỉ xử lý command.
  if (strcmp(topic, COMMAND_TOPIC) != 0)
  {
    Serial.println("Ignored message from unexpected topic");
    return;
  }

  JsonDocument doc;

  DeserializationError error =
      deserializeJson(doc, payload, length);

  if (error || !doc.is<JsonObject>())
  {
    Serial.println("Rejected: invalid JSON");
    return;
  }

  // Kiểm tra command_id trước tiên.
  // Không thể ACK nếu không có command_id dạng chuỗi hợp lệ.
  if (!doc["command_id"].is<const char *>())
  {
    Serial.println(
        "[COMMAND] Invalid command_id type; ACK omitted");
    return;
  }

  const char *rawCommandId = doc["command_id"];

  if (!isValidUuid(rawCommandId))
  {
    Serial.println(
        "[COMMAND] Invalid UUID format; ACK omitted");
    return;
  }

  String commandId = rawCommandId;

  Serial.print("[COMMAND] Received command_id: ");
  Serial.println(commandId);

  // Kiểm tra các trường bắt buộc còn lại.
  bool requiredFieldsValid =
      doc["action"].is<const char *>() &&
      doc["actuator"].is<const char *>() &&
      doc["expiresAt"].is<const char *>();

  if (!requiredFieldsValid)
  {
    Serial.println(
        "[COMMAND] REJECTED: INVALID_PAYLOAD");

    publishCommandAck(
        commandId,
        "REJECTED",
        "INVALID_PAYLOAD");

    return;
  }

  String action = doc["action"].as<String>();
  String actuator = doc["actuator"].as<String>();
  String expiresAt = doc["expiresAt"].as<String>();

  Serial.print("[COMMAND] action: ");
  Serial.println(action);

  Serial.print("[COMMAND] actuator: ");
  Serial.println(actuator);

  Serial.print("[COMMAND] expiresAt: ");
  Serial.println(expiresAt);

  Serial.println("========== COMMAND RECEIVED ==========");
  Serial.print("[COMMAND] command_id: ");
  Serial.println(commandId);

  Serial.print("[COMMAND] action: ");
  Serial.println(action);

  Serial.print("[COMMAND] actuator: ");
  Serial.println(actuator);

  Serial.print("[COMMAND] expiresAt: ");
  Serial.println(expiresAt);
  bool hasMode = !doc["targetMode"].isUnbound();
  bool hasState = !doc["targetState"].isUnbound();
  String targetMode = "";
  String targetState = "";

  bool targetModeTypeValid =
      !hasMode || doc["targetMode"].is<const char *>();

  bool targetStateTypeValid =
      !hasState || doc["targetState"].is<const char *>();

  if (targetModeTypeValid && hasMode)
  {
    targetMode = doc["targetMode"].as<String>();
  }

  if (targetStateTypeValid && hasState)
  {
    targetState = doc["targetState"].as<String>();
  }

  if (!targetModeTypeValid)
  {
    publishCommandAck(
        commandId,
        "REJECTED",
        "INVALID_TARGET_MODE");
    return;
  }

  if (!targetStateTypeValid)
  {
    publishCommandAck(
        commandId,
        "REJECTED",
        "INVALID_TARGET_STATE");
    return;
  }
  // Kiểm tra cache trước khi kiểm tra hết hạn.
  // Lệnh gửi lại cùng ID và cùng nội dung phải nhận kết quả cũ.// Chỉ cho phép đúng các trường trong hợp đồng.
  for (JsonPair kv : doc.as<JsonObject>())
  {
    const char *key = kv.key().c_str();

    bool allowed =
        strcmp(key, "command_id") == 0 ||
        strcmp(key, "action") == 0 ||
        strcmp(key, "actuator") == 0 ||
        strcmp(key, "expiresAt") == 0 ||
        strcmp(key, "targetMode") == 0 ||
        strcmp(key, "targetState") == 0;

    if (!allowed)
    {
      publishCommandAck(
          commandId,
          "REJECTED",
          "INVALID_PAYLOAD");
      return;
    }
  }
  String normalizedContent =
      action + "|" + actuator + "|" +
      targetMode + "|" + targetState + "|" +
      expiresAt;
  int cachedIndex = findCachedCommand(commandId);

  if (cachedIndex >= 0)
  {
    CachedCommand &cached = commandCache[cachedIndex];

    if (cached.normalizedContent == normalizedContent)
    {
      publishCommandAck(
      commandId,
      "DUPLICATE",
      "ALREADY_PROCESSED",
      true,
      cached.originalStatus,
      cached.originalReason,
      actuator);

      Serial.println("Duplicate command: original result retained");
    }
    else
    {
      // Không ghi đè cache gốc khi ID bị dùng cho nội dung khác.
      publishCommandAck(commandId, "REJECTED", "COMMAND_ID_CONFLICT", false, "", "", actuator);

      Serial.println("Command ID conflict");
    }

    return;
  }
  // 1. Kiểm tra action trước.
  if (action != "SET_MODE" && action != "SET_STATE")
  {
    rejectAndCache(commandId, normalizedContent, "INVALID_ACTION", actuator);  
    return;
  }

  // 2. Kiểm tra actuator sau.
  if (getActuatorPin(actuator) < 0)
  {
    rejectAndCache(commandId, normalizedContent, "INVALID_ACTUATOR", actuator);
    return;
  }

  // 3. Mỗi action phải có đúng một trường target tương ứng.
  if ((action == "SET_MODE" && (!hasMode || hasState)) ||
      (action == "SET_STATE" && (!hasState || hasMode)))
  {
    publishCommandAck(
        commandId,
        "REJECTED",
        "INVALID_PAYLOAD");
    return;
  }
  if (action == "SET_MODE")
  {
    if (targetMode != "AUTO" && targetMode != "MANUAL")
    {
      rejectAndCache(commandId, normalizedContent, "INVALID_TARGET_MODE", actuator);
      return;
    }
  }

  if (action == "SET_STATE")
  {
    if (targetState != "ON" && targetState != "OFF")
    {
      rejectAndCache(commandId, normalizedContent, "INVALID_TARGET_STATE", actuator);
      return;
    }
  }
  if (!isClockTrusted())
  {
    rejectAndCache(commandId, normalizedContent, "CLOCK_UNTRUSTED", actuator);
    return;
  }

  time_t expiryTime = 0;

  if (!parseUtcExpiry(expiresAt.c_str(), expiryTime))
  {
    rejectAndCache(commandId, normalizedContent, "INVALID_EXPIRY", actuator);
    return;
  }
  const uint32_t syncAgeMs =
    (uint32_t)(millis() - lastNtpSyncMs.load());

  Serial.printf("[CLOCK-CHECK] synced=%d sync_age_s=%lu\n",
                ntpSynced.load() ? 1 : 0,
                (unsigned long)(syncAgeMs / 1000UL));
  time_t now = time(nullptr);
  Serial.printf("[B2-41] id=%s action=%s now=%lld expires=%lld ttl_s=%lld\n", commandId.c_str(), action.c_str(), (long long)now, (long long)expiryTime, (long long)expiryTime - (long long)now);

  if (expiryTime <= now)
  {
    rejectAndCache(commandId, normalizedContent, "EXPIRED", actuator);
    return;
  }

  if (expiryTime > now + 15)
  {
    rejectAndCache(commandId, normalizedContent, "INVALID_EXPIRY", actuator);
    return;
  }
  // Xử lý lệnh SET_MODE.
  if (action == "SET_MODE")
  {
    controlMode =
        (targetMode == "AUTO") ? MODE_AUTO : MODE_MANUAL;
    manualModeStartedMs = (controlMode == MODE_MANUAL) ? millis() : 0;

    if (controlMode == MODE_AUTO)
    {
      applyControlLogic();
    }

    // Publish trạng thái sau khi logic điều khiển đã ổn định.
    publishState();
    rememberCommand(
        commandId,
        normalizedContent,
        "APPLIED",
        "OK");
    publishCommandAck(commandId, "APPLIED", "OK", false, "", "", actuator);
    return;
  }

  // SET_STATE chỉ được thực hiện trong MANUAL.
  if (controlMode != MODE_MANUAL)
  {
    rejectAndCache(commandId, normalizedContent, "MODE_NOT_MANUAL", actuator);
    return;
  }

  // Kiểm tra trạng thái đích.
  if (targetState != "ON" && targetState != "OFF")
  {
    rejectAndCache(commandId, normalizedContent, "INVALID_TARGET_STATE", actuator);
    return;
  }

  // Cập nhật logic an toàn trước khi xử lý lệnh cho FAN.
  if (actuator == "FAN")
  {
    applyControlLogic();
    // Không cho phép tắt quạt khi có safety override.
    if (targetState == "OFF" && safetyOverride)
    {
      rejectAndCache(commandId, normalizedContent, "SAFETY_OVERRIDE", actuator);
      return;
    }
  }

  if (!setActuatorState(actuator, targetState == "ON"))
  {
    rejectAndCache(commandId, normalizedContent, "INVALID_ACTUATOR", actuator);
    return;
  }

  rememberCommand(
      commandId,
      normalizedContent,
      "APPLIED",
      "OK");
  publishCommandAck(commandId, "APPLIED", "OK", false, "", "", actuator);
}
// SETUP
void setup()
{
  bootId = String((uint32_t)esp_random(), HEX) +
           String((uint32_t)esp_random(), HEX);
  Serial.begin(115200);
  Serial.println("[BOOT] B2-41 CLOCK-TEST-01");
  Serial.println("[B2-41] fan command diagnostics v1");
  pinMode(FAN_PIN, OUTPUT);
  pinMode(MIST_PIN, OUTPUT);
  pinMode(HEATER_PIN, OUTPUT);
  pinMode(AMPLIFIER_PIN, OUTPUT);
  digitalWrite(MIST_PIN, LOW);
  digitalWrite(HEATER_PIN, LOW);
  digitalWrite(AMPLIFIER_PIN, LOW);
  fanState = true;
  digitalWrite(FAN_PIN, HIGH);
  Serial.printf("[BOOT] P1 FIXED B2 reset_reason=%d RAM buffer only\n",
                (int)esp_reset_reason());
  loadZoneConfig();
  bootStartedMs = millis();

  // Khởi động cảm biến DHT22
  dht.begin();

  // Các chân actuator đã được khởi tạo ở đầu setup().

  Serial.println();
  Serial.println("==============================");
  Serial.println("ESP32 Swiftlet Firmware - AUTO 4 actuators");
  Serial.println("Starting sensors...");
  Serial.println("==============================");

  // Kết nối WiFi
  // WiFi connection is owned by the network task.

  // Cấu hình MQTT Broker
  mqttClient.setServer(MQTT_HOST, MQTT_PORT);
  mqttClient.setCallback(onNetworkMessage);
  mqttClient.setSocketTimeout(1);
  mqttClient.setKeepAlive(10);
  if (!mqttClient.setBufferSize(1024))
  {
    Serial.println("FATAL: MQTT buffer allocation failed; safe FAN ON");
    while (true)
      delay(1000);
  }
  mqttTxQueue = xQueueCreate(12, sizeof(MqttPacket));
  mqttRxQueue = xQueueCreate(12, sizeof(MqttPacket));
  if (!mqttTxQueue || !mqttRxQueue ||
      xTaskCreatePinnedToCore(networkTask, "mqtt-network", 8192, nullptr, 1,
                              &networkTaskHandle, 0) != pdPASS)
  {
    Serial.println("FATAL: network task allocation failed; safe FAN ON");
    while (true)
      delay(1000);
  }
  Serial.println("[TEST] Serial HELP lists manual fault/offline tests");
}

// LOOP
void loop()
{
  if (controlMode == MODE_MANUAL && manualModeStartedMs && millis() - manualModeStartedMs >= MANUAL_TIMEOUT_MS)
  {
    controlMode = MODE_AUTO;
    manualModeStartedMs = 0;
    Serial.println("[MODE] MANUAL timeout -> AUTO");
    applyControlLogic();
  }
  uint32_t loopStart = millis();
  processSerialTests();
  processMqttMessages();
  if (mqttJustConnected.exchange(false))
    publishState();
  if (mqttOnline.load())
  {
    resendBufferedMessages();
    flushSensorEvents();
    if (millis() - lastState >= STATE_INTERVAL)
    {
      lastState = millis();
      publishState();
    }
  }
  applyControlLogic();

  // 2. Đọc cảm biến định kỳ mỗi 2 giây
  if (millis() - lastSensorSampleMs >= SENSOR_INTERVAL)
  {
    lastSensorSampleMs = millis();

    float humidity = dht.readHumidity();
    float temperature = dht.readTemperature();

    int light = analogRead(LDR_PIN);
    int co2Raw = analogRead(CO2_PIN);
    int co2 = map(co2Raw, 0, 4095, 400, 2000);

    bool dhtValid = !simulateDhtFault && isfinite(humidity) &&
                    isfinite(temperature) && humidity >= 0 && humidity <= 100 &&
                    temperature >= -10 && temperature <= 60;
    latestCo2 = co2;
    latestLight = light;
    latestCo2Valid = !simulateCo2Fault && co2Raw >= 0 && co2Raw <= 4095;   // Cảm biến CO2 đang được mô phỏng bằng ADC.
    latestLightValid = !simulateLightFault && light >= 0 && light <= 4095; // Cảm biến ánh sáng đang được mô phỏng bằng ADC.

    // Hai cảm biến này hiện đang được mô phỏng.

    latestDhtValid = dhtValid;
    if (dhtValid)
    {
      if (dhtGoodStreak < 3)
        dhtGoodStreak++;
      if (dhtFaultActive && dhtGoodStreak >= 3)
      {
        dhtFaultActive = false;
        publishSensorEvent("SENSOR_RECOVERED", "DHT22", "THREE_GOOD_READINGS");
      }
    }
    else
    {
      dhtGoodStreak = 0;
      if (!dhtFaultActive)
      {
        dhtFaultActive = true;
        publishSensorEvent("SENSOR_FAULT", "DHT22", "INVALID_OR_NAN_READING");
      }
    }

    if (dhtValid)
    {
      latestTemperature = temperature;
      latestHumidity = humidity;
      haveValidTemperature = true;
      lastValidTemperatureMs = millis();

      Serial.printf(
          "Temperature: %.2f C | Humidity: %.2f %%\n",
          temperature,
          humidity);
    }
    else
    {
      Serial.println("DHT22: ERROR");
    }

    Serial.printf("CO2: %d ppm\n", co2);
    Serial.printf("Light: %d\n", light);

    // Luôn chạy logic an toàn, kể cả khi DHT22 bị lỗi.
    applyControlLogic();

    Serial.printf(
        "Mode: %s | MIST: %s | FAN: %s | HEATER: %s | AMPLIFIER: %s | Safety: %s\n",
        controlMode == MODE_AUTO ? "AUTO" : "MANUAL",
        humidifierState ? "ON" : "OFF",
        fanState ? "ON" : "OFF",
        heaterState ? "ON" : "OFF",
        audioAmplifierState ? "ON" : "OFF",
        safetyOverride ? "ON" : "OFF");
  }

  // Lịch telemetry độc lập với chu kỳ đọc cảm biến (mỗi 5 giây).
  // Dữ liệu cảm biến lỗi sẽ được publish dưới dạng null bởi publishTelemetry().
  if (millis() - lastTelemetry >= TELEMETRY_INTERVAL)
  {
    lastTelemetry = millis();
    publishTelemetry(
        latestTemperature,
        latestHumidity,
        latestCo2,
        latestLight,
        latestDhtValid && haveValidTemperature &&
            (millis() - lastValidTemperatureMs <= SENSOR_STALE_MS),
        latestCo2Valid,
        latestLightValid);
  }

  static unsigned long lastResourceLog = 0;
  if (millis() - lastResourceLog >= 60000UL)
  {
    lastResourceLog = millis();
    Serial.printf("[RESOURCE] uptime_ms=%lu free_heap=%u min_heap=%u "
                  "buffer=%d/%d peak=%lu dropped=%lu connects=%lu attempts=%lu "
                  "tx_fail=%lu tx_queue_drop=%lu rx_drop=%lu event_drop=%lu "
                  "loop_max_ms=%lu net_stack_words=%u\n",
                  (unsigned long)millis(), (unsigned int)ESP.getFreeHeap(),
                  (unsigned int)ESP.getMinFreeHeap(), bufferCount, BUFFER_SIZE,
                  (unsigned long)bufferPeak, (unsigned long)droppedTelemetryCount,
                  (unsigned long)reconnectCount.load(), (unsigned long)connectAttempts.load(),
                  (unsigned long)txFailures.load(), (unsigned long)txQueueDrops,
                  (unsigned long)rxDrops.load(), (unsigned long)sensorEventsDropped,
                  (unsigned long)loopMaxMs,
                  (unsigned int)uxTaskGetStackHighWaterMark(networkTaskHandle));
  }
  if (millis() - lastHeartbeat >= HEARTBEAT_INTERVAL)
  {
    lastHeartbeat = millis();
    publishHeartbeat();
  }

  uint32_t loopDuration = millis() - loopStart;
  if (loopDuration > loopMaxMs)
    loopMaxMs = loopDuration;
  delay(10);
}
void setupClock()
{
  configTime(0, 0, "pool.ntp.org", "time.google.com");
}

bool getTimestamp(char *output, size_t outputSize)
{
  if (!output || outputSize < 21 || !isClockTrusted())
    return false;
  time_t now = time(nullptr);
  struct tm t;
  if (!gmtime_r(&now, &t))
    return false;
  return strftime(output, outputSize, "%Y-%m-%dT%H:%M:%SZ", &t) > 0;
}
bool isClockTrusted()
{
  // Require a real SNTP notification this boot; allow a six-hour holdover.
  return ntpSynced.load() && time(nullptr) >= 1760000000 &&
         (uint32_t)(millis() - lastNtpSyncMs.load()) <= 6UL * 60UL * 60UL * 1000UL;
}

// ================= NETWORK TASK AND MANUAL TEST INPUT =================
bool queuePublish(const char *topic, const uint8_t *payload,
                  unsigned int length, bool retained)
{
  if (!mqttOnline.load() || !mqttTxQueue || !topic || !payload ||
      strlen(topic) >= 160 || length >= 768)
    return false;
  MqttPacket packet = {};
  strlcpy(packet.topic, topic, sizeof(packet.topic));
  memcpy(packet.payload, payload, length);
  packet.length = length;
  packet.retained = retained;
  if (xQueueSend(mqttTxQueue, &packet, 0) != pdTRUE)
  {
    txQueueDrops++;
    return false;
  }
  return true; // Queued only. Telemetry stays buffered until STORED.
}
bool queuePublish(const char *topic, const char *payload, bool retained)
{
  return payload && queuePublish(topic, (const uint8_t *)payload,
                                strlen(payload), retained);
}
void onNetworkMessage(char *topic, byte *payload, unsigned int length)
{
  // Copy before PubSubClient reuses its internal receive buffer.
  if (length == 0 || length > 512 || strlen(topic) >= 160)
  {
    rxDrops.fetch_add(1);
    return;
  }
  MqttPacket packet = {};
  strlcpy(packet.topic, topic, sizeof(packet.topic));
  memcpy(packet.payload, payload, length);
  packet.length = length;
  if (xQueueSend(mqttRxQueue, &packet, 0) != pdTRUE)
    rxDrops.fetch_add(1);
}
void processMqttMessages()
{
  MqttPacket packet;
  for (int i = 0; i < 4 && xQueueReceive(mqttRxQueue, &packet, 0) == pdTRUE; i++)
    mqttCallback(packet.topic, packet.payload, packet.length);
}
void networkTask(void *)
{
  connectWiFi();
  // SNTP renews periodically; callback is the source of clock trust.
  sntp_set_time_sync_notification_cb(onTimeSync);
  MqttPacket pending = {};
  bool hasPending = false;
  while (true)
  {
    if (!networkEnabled.load())
    {
      mqttClient.disconnect();
      mqttOnline.store(false);
      vTaskDelay(pdMS_TO_TICKS(20));
      continue;
    }
    maintainWiFi();
    if (WiFi.status() != WL_CONNECTED)
    {
      mqttClient.disconnect();
      mqttOnline.store(false);
      vTaskDelay(pdMS_TO_TICKS(20));
      continue;
    }
    if (!ntpConfigured)
    {
      sntp_set_sync_interval(60000UL);
      configTime(0, 0, "pool.ntp.org", "time.google.com");
      ntpConfigured = true;
    }
    tryConnectMQTT();
    if (mqttClient.connected())
    {
      mqttClient.loop();
      if (!hasPending && xQueueReceive(mqttTxQueue, &pending, 0) == pdTRUE)
        hasPending = true;
      if (hasPending)
      {
        if (mqttClient.publish(pending.topic, pending.payload, pending.length,
                              pending.retained))
          hasPending = false;
        else
        {
          txFailures.fetch_add(1);
          mqttClient.disconnect();
        }
      }
    }
    mqttOnline.store(mqttClient.connected());
    vTaskDelay(pdMS_TO_TICKS(10));
  }
}

void processSerialTests()
{
  // Manual test controls; no Python, no extra libraries.
  // TEST NETWORK OFF disconnects MQTT only, while sensors/AUTO keep running.
  static char line[80];
  static uint8_t used = 0;
  for (int n = 0; n < 80 && Serial.available(); n++)
  {
    char ch = (char)Serial.read();
    if (ch == '\r')
      continue;
    if (ch != '\n')
    {
      if (used < sizeof(line) - 1)
        line[used++] = ch;
      else
        used = 0;
      continue;
    }
    line[used] = 0;
    used = 0;
    String cmd(line);
    cmd.trim();
    cmd.toUpperCase();
    if (cmd == "TEST DHT OFF")
      simulateDhtFault = true;
    else if (cmd == "TEST DHT ON")
      simulateDhtFault = false;
    else if (cmd == "TEST CO2 OFF")
      simulateCo2Fault = true;
    else if (cmd == "TEST CO2 ON")
      simulateCo2Fault = false;
    else if (cmd == "TEST LIGHT OFF")
      simulateLightFault = true;
    else if (cmd == "TEST LIGHT ON")
      simulateLightFault = false;
    else if (cmd == "TEST NETWORK OFF")
      networkEnabled.store(false);
    else if (cmd == "TEST NETWORK ON")
      networkEnabled.store(true);
    else if (cmd == "TEST CLEAR")
    {
      simulateDhtFault = simulateCo2Fault = simulateLightFault = false;
      networkEnabled.store(true);
    }
    else if (cmd == "TEST STATUS")
    {
      char utc[32];
      bool trusted = getTimestamp(utc, sizeof(utc));
      Serial.printf("[TEST] utc=%s uptime_ms=%lu buffer=%d dropped=%lu "
                    "dht_good=%u safety=%d mqtt=%d\n",
                    trusted ? utc : "UNTRUSTED",
                    (unsigned long)millis(), bufferCount, (unsigned long)droppedTelemetryCount,
                    dhtGoodStreak, safetyOverride, mqttOnline.load());
      printZoneConfig();
    }
    else
    {
      Serial.println("[TEST] HELP: TEST DHT/CO2/LIGHT OFF|ON; " "TEST NETWORK OFF|ON; TEST CLEAR; TEST STATUS");
    }
    Serial.printf("[TEST] %s\n", cmd.c_str());
  }
}
