import csv
import math
from pathlib import Path

DURATION = 120
SETPOINT = 28.5
DT = 1.0

# Same environmental input for both controllers.
def ambient_temperature(t):
    return (
        29.0
        + 3.0 * math.sin(math.pi * t / DURATION)
        + 0.8 * math.sin(t / 9.0)
    )

def thermal_step(temp, ambient, fan, heater):
    # Simplified thermal model for comparison only.
    natural_change = 0.035 * (ambient - temp)
    cooling = 0.12 if fan else 0.0
    heating = 0.10 if heater else 0.0
    return temp + natural_change - cooling + heating

# Rule-based controller based on current firmware thresholds.
def rule_controller(temp, fan, heater, co2=400):
    fan_on = temp >= 31.0 or co2 > 1000
    fan_off = temp <= 30.0 and co2 < 800

    if not fan and fan_on:
        fan = True
    elif fan and fan_off:
        fan = False

    if temp >= 28.0 or fan:
        heater = False
    elif not heater and temp < 26.0:
        heater = True

    return fan, heater

# PID controller: simulated only, not connected to ESP32.
class PIDController:
    def __init__(self):
        self.kp = 18.0
        self.ki = 0.04
        self.kd = 1.0
        self.integral = 0.0
        self.previous_error = 0.0

    def update(self, temp):
        error = SETPOINT - temp
        self.integral += error * DT
        self.integral = max(-100.0, min(100.0, self.integral))

        derivative = (error - self.previous_error) / DT
        self.previous_error = error

        output = (
            self.kp * error
            + self.ki * self.integral
            + self.kd * derivative
        )
        output = max(-100.0, min(100.0, output))

        # Convert PID output into mutually exclusive relay states.
        heater = output > 15.0
        fan = output < -15.0
        return fan, heater

def switching_count(values):
    return sum(
        1 for a, b in zip(values, values[1:]) if a != b
    )

def calculate_metrics(temperatures, fans, heaters):
    errors = [abs(t - SETPOINT) for t in temperatures]
    overshoot = max(0.0, max(temperatures) - SETPOINT)
    in_band = sum(1 for e in errors if e <= 1.5)

    return {
        "mae": sum(errors) / len(errors),
        "max_error": max(errors),
        "overshoot": overshoot,
        "time_in_band": 100.0 * in_band / len(errors),
        "fan_switches": switching_count(fans),
        "heater_switches": switching_count(heaters),
        "total_switches": (
            switching_count(fans) + switching_count(heaters)
        ),
    }

def main():
    rule_temp = 24.0
    pid_temp = 24.0

    rule_fan = False
    rule_heater = False
    pid_fan = False
    pid_heater = False

    pid = PIDController()
    rows = []

    rule_temps = []
    pid_temps = []
    rule_fans = []
    rule_heaters = []
    pid_fans = []
    pid_heaters = []

    for minute in range(DURATION + 1):
        ambient = ambient_temperature(minute)

        if minute > 0:
            rule_fan, rule_heater = rule_controller(
                rule_temp, rule_fan, rule_heater
            )
            pid_fan, pid_heater = pid.update(pid_temp)

            rule_temp = thermal_step(
                rule_temp, ambient, rule_fan, rule_heater
            )
            pid_temp = thermal_step(
                pid_temp, ambient, pid_fan, pid_heater
            )

        rule_temps.append(rule_temp)
        pid_temps.append(pid_temp)
        rule_fans.append(int(rule_fan))
        rule_heaters.append(int(rule_heater))
        pid_fans.append(int(pid_fan))
        pid_heaters.append(int(pid_heater))

        rows.append({
            "minute": minute,
            "ambient_temperature_C": round(ambient, 3),
            "setpoint_C": SETPOINT,
            "rule_temperature_C": round(rule_temp, 3),
            "rule_fan": "ON" if rule_fan else "OFF",
            "rule_heater": "ON" if rule_heater else "OFF",
            "pid_temperature_C": round(pid_temp, 3),
            "pid_fan": "ON" if pid_fan else "OFF",
            "pid_heater": "ON" if pid_heater else "OFF",
        })

    output_dir = Path("docs/iot")
    output_dir.mkdir(parents=True, exist_ok=True)

    csv_path = output_dir / "pid-comparison-results.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)

    rule = calculate_metrics(
        rule_temps, rule_fans, rule_heaters
    )
    pid_result = calculate_metrics(
        pid_temps, pid_fans, pid_heaters
    )

    report = f"""# B3-35 - PID vs Rule-based Comparison

## 1. Objective

Compare the existing Rule-based controller with a simulated PID controller
using the same environmental input over 120 minutes.

## 2. Scope

- Rule-based logic follows the current firmware FAN and HEATER thresholds.
- PID is a software-only simulation.
- PID is NOT connected to ESP32 outputs.
- No firmware, MQTT, AUTO/MANUAL or safety behavior was changed.
- MIST and AMPLIFIER are outside this temperature-control comparison.

## 3. Simulation assumptions

- Duration: {DURATION} minutes
- Initial temperature: 24.0 C
- PID setpoint: {SETPOINT} C
- Ambient temperature is a deterministic simulated input.
- Both controllers use the same simplified thermal model.
- CO2 is held at 400 ppm in the Rule-based simulation.
- PID parameters: Kp=18.0, Ki=0.04, Kd=1.0.
- PID output is converted to mutually exclusive FAN/HEATER relay states.

These assumptions are for a reproducible software comparison. They are not
calibrated measurements of a real swiftlet house.

## 4. Comparison metrics

| Metric | Rule-based | PID |
|---|---:|---:|
| Mean absolute error (C) | {rule['mae']:.3f} | {pid_result['mae']:.3f} |
| Maximum absolute error (C) | {rule['max_error']:.3f} | {pid_result['max_error']:.3f} |
| Maximum overshoot above setpoint (C) | {rule['overshoot']:.3f} | {pid_result['overshoot']:.3f} |
| Time within setpoint +/- 1.5 C (%) | {rule['time_in_band']:.1f} | {pid_result['time_in_band']:.1f} |
| FAN switching count | {rule['fan_switches']} | {pid_result['fan_switches']} |
| HEATER switching count | {rule['heater_switches']} | {pid_result['heater_switches']} |
| Total relay switching count | {rule['total_switches']} | {pid_result['total_switches']} |

## 5. Discussion

Rule-based control uses explicit thresholds and hysteresis. It is simple,
predictable and easy to validate. Its temperature response is bounded by
the configured ON/OFF thresholds.

PID calculates its output from the temperature error, accumulated error
and rate of change. It can provide finer control in a suitable calibrated
system, but its performance depends on tuning and the accuracy of the
thermal model.

Relay switching count is important because frequent switching can increase
relay wear. The simulation results should be interpreted together with
temperature error and time in the target band.

## 6. Limitations

- The thermal model is simplified.
- Ambient temperature is simulated, not measured.
- PID parameters have not been tuned against a real house.
- No real actuator was controlled by PID.
- Results do not establish that PID is superior in real-world operation.

## 7. Conclusion

This experiment provides a reproducible software-level comparison between
the existing Rule-based approach and a simulated PID approach.

The current Rule-based firmware remains unchanged. PID is considered an
experimental alternative only and must undergo model calibration, safety
validation and hardware testing before any production use.

## 8. Evidence files

- `docs/iot/pid-comparison-results.csv`
- `tools/pid_comparison.py`

The CSV contains the minute-by-minute simulated temperatures and actuator
states for both controllers.
"""

    report_path = output_dir / "pid-comparison.md"
    report_path.write_text(report, encoding="utf-8")

    print("Simulation completed.")
    print(f"CSV: {csv_path}")
    print(f"Report: {report_path}")
    print("\nRule-based:", rule)
    print("PID:", pid_result)

if __name__ == "__main__":
    main()
