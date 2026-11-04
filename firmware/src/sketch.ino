
#include <Arduino.h>
#include <ArduinoJson.h>
#include <math.h>
#include <atomic>
#include <esp_sntp.h>
#include <esp_system.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>
#include <Preferences.h>
#include <time.h>
#include <esp_system.h>   // esp_random()
#include <WiFi.h>         // ESP32 kết nối WiFi để giao tiếp với MQTT Broker
#include <PubSubClient.h> // Thư viện MQTT cho ESP32
#include "DHT.h"          // Thư viện cảm biến DHT22

// SENSOR & DEVICE PIN
#define DHTPIN 15     // Chân kết nối DHT22
#define DHTTYPE DHT22 // Loại cảm biến DHT
#define LDR_PIN 34    // Chân đọc cảm biến ánh sáng
#define CO2_PIN 35    // Chân đọc cảm biến CO2
#define FAN_PIN 4     // Chân điều khiển quạt

struct ZoneConfig; // Forward declaration for Arduino sketch preprocessing.
DHT dht(DHTPIN, DHTTYPE);
bool getTimestamp(char *output, size_t outputSize);
bool isClockTrusted();
void publishState();
void applyControlLogic();
void setFanState(bool turnOn); // Tạo đối tượng DHT22

// FAN THRESHOLD — fallback defaults; AUTO uses ZoneConfig.tempMax.
#define FAN_ON_TEMP 31.0  // Nhiệt độ >= 31°C thì bật quạt
#define FAN_OFF_TEMP 30.0 // Nhiệt độ <= 30°C thì tắt quạt

bool fanState = false; // false = quạt tắt, true = quạt bật
bool safetyOverride = true;

bool latestDhtValid = false;
bool haveValidTemperature = false;

float latestTemperature = NAN;
float latestHumidity = NAN;
int latestCo2 = 0;
int latestLight = 0;
bool latestCo2Valid = false;
bool latestLightValid = false;

unsigned long lastValidTemperatureMs = 0;
unsigned long lastSensorSampleMs = 0;
unsigned long lastState = 0;

const unsigned long SENSOR_INTERVAL = 2000;
const unsigned long SENSOR_STALE_MS = 15000;
const unsigned long STATE_INTERVAL = 5000;

String bootId;
enum ControlMode
{
  MODE_AUTO,
  MODE_MANUAL
};

ControlMode controlMode = MODE_AUTO;
// Cache command_id đã xử lý, chỉ lưu trong RAM.
const int COMMAND_CACHE_SIZE = 64;

struct CachedCommand
{
  bool used = false;
  String commandId;
  String normalizedContent;
  String originalStatus;
  String originalReason;
};

CachedCommand commandCache[COMMAND_CACHE_SIZE];
int commandCacheNext = 0;

int findCachedCommand(const String &commandId)
{
  for (int i = 0; i < COMMAND_CACHE_SIZE; i++)
  {
    if (commandCache[i].used &&
        commandCache[i].commandId == commandId)
    {
      return i;
    }
  }

  return -1;
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

void rememberCommand(
    const String &commandId,
    const String &normalizedContent,
    const char *status,
    const char *reason)
{
  if (commandId.isEmpty())
  {
    return;
  }

  CachedCommand &entry = commandCache[commandCacheNext];

  entry.used = true;
  entry.commandId = commandId;
  entry.normalizedContent = normalizedContent;
  entry.originalStatus = status;
  entry.originalReason = reason;

  commandCacheNext =
      (commandCacheNext + 1) % COMMAND_CACHE_SIZE;
}
// WIFI CONFIGURATION
const char *WIFI_SSID = "Wokwi-GUEST";
const char *WIFI_PASSWORD = "";

// MQTT CONFIGURATION
const char *MQTT_HOST = "host.wokwi.internal"; // Địa chỉ MQTT Broker
const int MQTT_PORT = 1883;                    // Cổng MQTT Broker

// Phải lấy đúng giá trị từ Backend hoặc database của nhóm.
const char *HOUSE_ID = "1";
const char *ZONE_ID = "1";
const char *DEVICE_ID = "ESP32-HOUSE01-ZONE01";

// Tạm dùng ID ví dụ ở trên.
// Khi tích hợp, thay bằng ID thật do nhóm Backend cung cấp.

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

Preferences preferences;
struct ZoneConfig
{
  uint32_t version = 1;
  float humidityOn = 70.0f;
  float humidityOff = 75.0f;
  float tempMin = 20.0f;
  float tempMax = 31.0f;
  uint32_t telemetryIntervalMs = 5000;
};
ZoneConfig zoneConfig;
unsigned long manualModeStartedMs = 0;
const unsigned long MANUAL_TIMEOUT_MS = 10UL * 60UL * 1000UL;
uint8_t dhtGoodStreak = 0;
bool dhtFaultActive = false;
uint32_t droppedTelemetryCount = 0;
unsigned long bootStartedMs = 0;
const char *TELEMETRY_ACK_TOPIC =
    "swiftletcare/1/1/ESP32-HOUSE01-ZONE01/telemetry_ack";
// MQTT CLIENT
WiFiClient espClient;               // Tạo kết nối TCP/IP
PubSubClient mqttClient(espClient); // Tạo MQTT Client

// TIMERS
unsigned long lastTelemetry = 0;
unsigned long lastHeartbeat = 0;

unsigned long TELEMETRY_INTERVAL = 5000;       // Configurable 1..60s
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

// PubSubClient and WiFi belong exclusively to networkTask after setup.
// Main loop owns control state, command handling, NVS and telemetry buffer.
struct MqttPacket
{
  char topic[160];
  uint8_t payload[768];
  uint16_t length;
  bool retained;
};
QueueHandle_t mqttTxQueue = nullptr, mqttRxQueue = nullptr;
TaskHandle_t networkTaskHandle = nullptr;
bool queuePublish(const char *topic, const uint8_t *payload,
                  unsigned int length, bool retained = false);
bool queuePublish(const char *topic, const char *payload,
                  bool retained = false);
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

void maintainWiFi()
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
  for (int i = 0; i < bufferCount; i++)
  {
    JsonDocument doc;

    DeserializationError error =
        deserializeJson(doc, messageBuffer[i].payload);

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

  // ACK lặp hoặc ACK cho bản tin không còn trong buffer.
  // Không xoá bản tin khác.
  Serial.printf(
      "[BUFFER] ACK ignored; event_id not found: %s\n",
      eventId.c_str());
}

void handleTelemetryAck(byte *payload, unsigned int length)
{
  JsonDocument doc;

  DeserializationError error =
      deserializeJson(doc, payload, length);

  if (error || !doc.is<JsonObject>())
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

  String ackDeviceId = doc["device_id"].as<String>();
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
  // At most two attempts per pass. Never flush 120 messages in a tight loop.
  unsigned long now = millis();
  static unsigned long lastPass = 0;
  static int cursor = 0;
  if (now - lastPass < 50)
    return;
  lastPass = now;
  int scanned = 0, sent = 0;
  while (scanned < bufferCount && sent < 2)
  {
    if (cursor >= bufferCount)
      cursor = 0;
    BufferedMessage &item = messageBuffer[cursor++];
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
unsigned long lastMQTTAttempt = 0;
const unsigned long MQTT_RETRY_INTERVAL = 3000; // Thử reconnect mỗi 3 giây

// Kiểm tra và kết nối lại MQTT khi bị mất kết nối
// Kiểm tra và kết nối lại MQTT khi bị mất kết nối
void tryConnectMQTT()
{
  // Called only by networkTask. Exponential retry 1, 2, 4 ... 30 seconds.
  static uint32_t retryMs = 1000;
  if (mqttClient.connected())
    return;
  if (millis() - lastMQTTAttempt < retryMs)
    return;
  lastMQTTAttempt = millis();
  connectAttempts.fetch_add(1);
  Serial.printf("[MQTT] connect attempt=%lu\n",
                (unsigned long)connectAttempts.load());
  if (!mqttClient.connect(DEVICE_ID))
  {
    retryMs = retryMs < 15000 ? retryMs * 2 : 30000;
    Serial.printf("[MQTT] failed rc=%d retry_ms=%lu\n",
                  mqttClient.state(), (unsigned long)retryMs);
    return;
  }
  bool subscribed = mqttClient.subscribe(CFG_TOPIC, 1);
  subscribed = mqttClient.subscribe(COMMAND_TOPIC, 1) && subscribed;
  subscribed = mqttClient.subscribe(TELEMETRY_ACK_TOPIC, 1) && subscribed;
  if (!subscribed)
  {
    Serial.println("[MQTT] subscription send failed; reconnecting");
    mqttClient.disconnect();
    return;
  }
  retryMs = 1000;
  reconnectCount.fetch_add(1);
  mqttOnline.store(true);
  mqttJustConnected.store(true);
  Serial.println("[MQTT] CONNECTED; subscribed cfg + command + telemetry_ack");
}
// TELEMETRY
// Tạo và gửi dữ liệu nhiệt độ, độ ẩm, CO2 và ánh sáng
// TELEMETRY
// Đồng bộ schema telemetry theo code nhóm trưởng.

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

void publishCommandAck(
    const String &commandId,
    const String &status,
    const String &reason,
    bool isDuplicate = false,
    const String &originalStatus = "",
    const String &originalReason = "")
{
  JsonDocument doc;

  doc["device_id"] = DEVICE_ID;
  doc["command_id"] = commandId;
  doc["actuator"] = "FAN";
  doc["status"] = status;
  doc["reason"] = reason;
  doc["mode"] = controlMode == MODE_AUTO ? "AUTO" : "MANUAL";
  doc["state"] = fanState ? "ON" : "OFF";
  doc["gpio_state"] = digitalRead(FAN_PIN) == HIGH ? "HIGH" : "LOW";
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
  doc["state"] = fanState ? "ON" : "OFF";
  doc["gpio_state"] = digitalRead(FAN_PIN) == HIGH ? "HIGH" : "LOW";
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

  queuePublish(STATE_TOPIC, payload, false);
}
void setFanState(bool turnOn)
{
  bool changed = fanState != turnOn;

  fanState = turnOn;

  digitalWrite(
      FAN_PIN,
      turnOn ? HIGH : LOW);

  Serial.printf(
      "FAN=%s GPIO=%s\n",
      fanState ? "ON" : "OFF",
      digitalRead(FAN_PIN) == HIGH ? "HIGH" : "LOW");

  if (changed)
  {
    publishState();
  }
}
void applyControlLogic()
{
  bool fresh = latestDhtValid && haveValidTemperature &&
               millis() - lastValidTemperatureMs <= SENSOR_STALE_MS;
  bool safe = !fresh || dhtFaultActive || dhtGoodStreak < 3;
  bool overrideChanged = safetyOverride != safe;
  safetyOverride = safe;
  if (safe)
  {
    if (!fanState)
      setFanState(true);
  }
  else if (controlMode == MODE_AUTO)
  {
    // FAN only: ON at temp_max, OFF one degree lower, never below temp_min.
    // humidity_on/off describe a future humidifier, not the exhaust FAN.
    float fanOff = fmaxf(zoneConfig.tempMin, zoneConfig.tempMax - 1.0f);
    if (!fanState && latestTemperature >= zoneConfig.tempMax)
      setFanState(true);
    else if (fanState && latestTemperature <= fanOff)
      setFanState(false);
  }
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
    const char *reason)
{
  rememberCommand(
      commandId,
      normalizedContent,
      "REJECTED",
      reason);
  publishCommandAck(commandId, "REJECTED", reason);
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
      c.humidityOn = preferences.getFloat("hon", 70);
      c.humidityOff = preferences.getFloat("hoff", 75);
      c.tempMin = preferences.getFloat("tmin", 20);
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
// Fault transitions can be buffered while offline (bounded RAM queue).
struct PendingSensorEvent
{
  String json;
};
PendingSensorEvent sensorEvents[16];
uint8_t sensorEventCount = 0;
uint32_t sensorEventsDropped = 0;
void publishSensorEvent(const char *type, const char *sensor, const char *reason)
{
  JsonDocument d;
  char timestamp[32];
  d["device_id"] = DEVICE_ID;
  d["event_type"] = type;
  d["sensor"] = sensor;
  d["reason"] = reason;
  if (getTimestamp(timestamp, sizeof(timestamp)))
    d["timestamp"] = timestamp;
  else
    d["timestamp"] = nullptr;
  String json;
  serializeJson(d, json);
  if (sensorEventCount < 16)
    sensorEvents[sensorEventCount++].json = json;
  else
    sensorEventsDropped++;
  Serial.printf("[EVENT] %s sensor=%s queued=%u dropped=%lu\n", type, sensor,
                sensorEventCount, (unsigned long)sensorEventsDropped);
}
void flushSensorEvents()
{
  if (!mqttOnline.load() || !sensorEventCount)
    return;
  if (!queuePublish(EVENTS_TOPIC, sensorEvents[0].json.c_str()))
    return;
  for (int i = 0; i < sensorEventCount - 1; i++)
    sensorEvents[i] = sensorEvents[i + 1];
  sensorEvents[--sensorEventCount].json = String();
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
          cached.originalReason);

      Serial.println("Duplicate command: original result retained");
    }
    else
    {
      // Không ghi đè cache gốc khi ID bị dùng cho nội dung khác.
      publishCommandAck(
          commandId,
          "REJECTED",
          "COMMAND_ID_CONFLICT");

      Serial.println("Command ID conflict");
    }

    return;
  }
  // 1. Kiểm tra action trước.
  if (action != "SET_MODE" && action != "SET_STATE")
  {
    rejectAndCache(
        commandId,
        normalizedContent,
        "INVALID_ACTION");
    return;
  }

  // 2. Kiểm tra actuator sau.
  if (actuator != "FAN")
  {
    rejectAndCache(
        commandId,
        normalizedContent,
        "INVALID_ACTUATOR");
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
      rejectAndCache(
          commandId, normalizedContent, "INVALID_TARGET_MODE");
      return;
    }
  }

  if (action == "SET_STATE")
  {
    if (targetState != "ON" && targetState != "OFF")
    {
      rejectAndCache(
          commandId, normalizedContent, "INVALID_TARGET_STATE");
      return;
    }
  }
  if (!isClockTrusted())
  {
    rejectAndCache(
        commandId, normalizedContent, "CLOCK_UNTRUSTED");
    return;
  }

  time_t expiryTime = 0;

  if (!parseUtcExpiry(expiresAt.c_str(), expiryTime))
  {
    rejectAndCache(
        commandId, normalizedContent, "INVALID_EXPIRY");
    return;
  }

  time_t now = time(nullptr);

  if (expiryTime <= now)
  {
    rejectAndCache(
        commandId, normalizedContent, "EXPIRED");
    return;
  }

  if (expiryTime > now + 15)
  {
    rejectAndCache(
        commandId, normalizedContent, "INVALID_EXPIRY");
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
    publishCommandAck(commandId, "APPLIED", "OK");
    return;
  }

  // SET_STATE chỉ được thực hiện trong MANUAL.
  if (controlMode != MODE_MANUAL)
  {
    rejectAndCache(
        commandId,
        normalizedContent,
        "MODE_NOT_MANUAL");
    return;
  }

  // Kiểm tra trạng thái đích.
  if (targetState != "ON" && targetState != "OFF")
  {
    rejectAndCache(
        commandId,
        normalizedContent,
        "INVALID_TARGET_STATE");
    return;
  }

  applyControlLogic();
  // Không cho phép tắt quạt khi có safety override.
  if (targetState == "OFF" && safetyOverride)
  {
    rejectAndCache(
        commandId,
        normalizedContent,
        "SAFETY_OVERRIDE");
    return;
  }

  setFanState(targetState == "ON");

  rememberCommand(
      commandId,
      normalizedContent,
      "APPLIED",
      "OK");
  publishCommandAck(commandId, "APPLIED", "OK");
}
// SETUP
void setup()
{
  bootId = String((uint32_t)esp_random(), HEX) +
           String((uint32_t)esp_random(), HEX);
  Serial.begin(115200);
  pinMode(FAN_PIN, OUTPUT);
  fanState = true;
  digitalWrite(FAN_PIN, HIGH);
  Serial.printf("[BOOT] P1 FIXED B2 reset_reason=%d RAM buffer only\n",
                (int)esp_reset_reason());
  loadZoneConfig();
  bootStartedMs = millis();

  // Khởi động cảm biến DHT22
  dht.begin();

  // Cấu hình chân điều khiển quạt
  pinMode(FAN_PIN, OUTPUT);
  digitalWrite(FAN_PIN, HIGH);

  Serial.println();
  Serial.println("==============================");
  Serial.println("ESP32 Swiftlet Firmware");
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
        "Mode: %s | Fan: %s | Safety: %s\n",
        controlMode == MODE_AUTO ? "AUTO" : "MANUAL",
        fanState ? "ON" : "OFF",
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
      Serial.println("[TEST] HELP: TEST DHT/CO2/LIGHT OFF|ON; "
                     "TEST NETWORK OFF|ON; TEST CLEAR; TEST STATUS");
    }
    Serial.printf("[TEST] %s\n", cmd.c_str());
  }
}
