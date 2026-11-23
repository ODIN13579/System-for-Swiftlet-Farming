# B3-35 - PID vs Rule-based Comparison

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

- Duration: 120 minutes
- Initial temperature: 24.0 C
- PID setpoint: 28.5 C
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
| Mean absolute error (C) | 1.622 | 0.994 |
| Maximum absolute error (C) | 4.500 | 4.500 |
| Maximum overshoot above setpoint (C) | 2.531 | 0.967 |
| Time within setpoint +/- 1.5 C (%) | 40.5 | 90.1 |
| FAN switching count | 2 | 40 |
| HEATER switching count | 2 | 2 |
| Total relay switching count | 4 | 42 |

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
