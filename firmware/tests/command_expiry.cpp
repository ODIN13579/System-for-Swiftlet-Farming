#include "control_core.h"
#include <cassert>
#include <cstring>

int main() {
  using sc::CommandExpiry;
  constexpr auto wire = "2026-11-27T08:29:38Z";
  constexpr int64_t expected = 1795768178;
  int64_t deadline = 0;
  assert(sc::validateCommandExpiry(wire, expected - 10, true, deadline) == CommandExpiry::Valid);
  assert(deadline == expected);
  assert(sc::validateCommandExpiry(wire, expected - 15, true, deadline) == CommandExpiry::Valid);
  assert(sc::validateCommandExpiry(wire, expected - 16, true, deadline) == CommandExpiry::TooFarAhead);
  assert(sc::validateCommandExpiry(wire, expected - 1, true, deadline) == CommandExpiry::Valid);
  assert(sc::validateCommandExpiry(wire, expected, true, deadline) == CommandExpiry::Expired);
  assert(sc::validateCommandExpiry(wire, expected + 20, true, deadline) == CommandExpiry::Expired);
  assert(sc::validateCommandExpiry(wire, expected - 10, false, deadline) == CommandExpiry::ClockUntrusted);
  for (const auto* bad : {"2026-11-27T08:29:38+00:00", "2026-11-27T08:29:38.000Z",
                          "2026-02-29T08:29:38Z", "2026-11-27T08:29:38z"}) {
    assert(sc::validateCommandExpiry(bad, expected - 10, true, deadline) == CommandExpiry::Malformed);
  }
  assert(!strcmp(sc::commandExpiryReason(CommandExpiry::TooFarAhead), "INVALID_EXPIRY"));
  assert(!strcmp(sc::commandExpiryReason(CommandExpiry::Expired), "EXPIRED"));
}
