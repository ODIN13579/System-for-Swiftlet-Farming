#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <math.h>
#include <memory>
#include <new>

namespace sc {
constexpr size_t BUFFER_CAPACITY = 128; // 120 samples + boundary/in-flight margin
constexpr uint32_t STALE_MS = 15000;
constexpr uint32_t MANUAL_TTL_MS = 30UL * 60 * 1000;
inline bool due(uint32_t now, uint32_t then, uint32_t interval) {
  return uint32_t(now - then) >= interval;
}
inline bool validTH(float t, float h) {
  return isfinite(t) && isfinite(h) && t >= -10 && t <= 60 && h >= 0 && h <= 100;
}
struct Control {
  bool manual = false, fan = false, safety = true;
  bool valid = false, haveSample = false;
  float temperature = NAN;
  uint32_t sampledAt = 0, manualAt = 0;
  unsigned goodSamples = 0;
  void sample(float t, float h, uint32_t now) {
    valid = validTH(t,h);
    if (valid) {
      temperature = t; sampledAt = now; haveSample = true;
      if (goodSamples < 3) ++goodSamples;
    } else goodSamples = 0;
  }
  bool tick(uint32_t now) {
    bool expiredManual = manual && due(now, manualAt, MANUAL_TTL_MS);
    if (expiredManual) manual = false;
    bool fresh = valid && haveSample && !due(now, sampledAt, STALE_MS + 1);
    if (!fresh) { safety = true; goodSamples = 0; }
    else if (goodSamples >= 3) safety = false;
    if (safety) fan = true;
    else if (!manual) {
      if (temperature >= 31) fan = true;
      else if (temperature <= 30) fan = false;
    }
    return expiredManual;
  }
};
inline bool uuid(const char* s) {
  if (!s || strlen(s)!=36) return false;
  for (int i=0;i<36;++i) {
    char c=s[i];
    if (i==8||i==13||i==18||i==23) { if(c!='-')return false; }
    else if (!((c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F'))) return false;
  }
  return true;
}
inline bool parseUtc(const char* s, int64_t& epoch) {
  if (!s || strlen(s)!=20) return false;
  for(int i=0;i<20;++i) {
    char e= i==4||i==7 ? '-' : i==10 ? 'T' : i==13||i==16 ? ':' : i==19 ? 'Z' : 0;
    if(e ? s[i]!=e : (s[i]<'0'||s[i]>'9')) return false;
  }
  auto n=[s](int i,int count){int x=0;while(count--)x=x*10+s[i++]-'0';return x;};
  int y=n(0,4), m=n(5,2), d=n(8,2), h=n(11,2), mi=n(14,2), se=n(17,2);
  int md[]={31,28,31,30,31,30,31,31,30,31,30,31};
  if(y<1970||y>2037||m<1||m>12||h>23||mi>59||se>59)return false;
  if(y%400==0||(y%4==0&&y%100!=0))md[1]=29;
  if(d<1||d>md[m-1])return false;
  y-=m<=2; int era=y/400; unsigned yo=y-era*400;
  unsigned doy=(153*(m+(m>2?-3:9))+2)/5+d-1;
  int64_t days=era*146097+int(yo*365+yo/4-yo/100+doy)-719468;
  epoch=days*86400+h*3600+mi*60+se; return true;
}
enum class CommandExpiry { Valid, ClockUntrusted, Malformed, Expired, TooFarAhead };

inline CommandExpiry validateCommandExpiry(const char* value, int64_t now, bool clockTrusted,
                                           int64_t& expiry) {
  if (!clockTrusted) return CommandExpiry::ClockUntrusted;
  if (!parseUtc(value, expiry)) return CommandExpiry::Malformed;
  if (expiry <= now) return CommandExpiry::Expired;
  if (expiry - now > 15) return CommandExpiry::TooFarAhead;
  return CommandExpiry::Valid;
}

inline const char* commandExpiryReason(CommandExpiry status) {
  switch (status) {
    case CommandExpiry::ClockUntrusted: return "CLOCK_UNTRUSTED";
    case CommandExpiry::Expired: return "EXPIRED";
    case CommandExpiry::Malformed:
    case CommandExpiry::TooFarAhead: return "INVALID_EXPIRY";
    case CommandExpiry::Valid: return nullptr;
  }
  return nullptr;
}
// One heap allocation at setup, never resized: preserves capacity without
// consuming the limited static .dram0.bss region; no per-message allocations.
struct Buffered { char event[100]{}; char json[512]{}; uint32_t sentAt=0; bool attempted=false; };
struct Buffer {
  std::unique_ptr<Buffered[]> entries; size_t count=0;
  bool begin() {
    if (!entries) entries.reset(new (std::nothrow) Buffered[BUFFER_CAPACITY]);
    return bool(entries);
  }
  bool add(const char* id,const char* json) {
    if(!id||!json||!begin()||count==BUFFER_CAPACITY||strlen(id)>=100||strlen(json)>=512)return false;
    auto& e=entries[count++]; e=Buffered{};
    strcpy(e.event,id);strcpy(e.json,json);return true;
  }
  bool ack(const char* device,const char* own,const char* event,const char* status) {
    if(!device||!own||!event||!status||strcmp(device,own)||strcmp(status,"STORED"))return false;
    for(size_t i=0;i<count;++i)if(!strcmp(entries[i].event,event)) {
      for(size_t j=i+1;j<count;++j)entries[j-1]=entries[j];
      entries[--count]=Buffered{}; return true;
    }
    return false;
  }
};
}
