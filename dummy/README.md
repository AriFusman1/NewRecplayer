# Demo ICD and pcaps for auto tagging

`demo-sensor.icd.json` (id `demo-sensor-v1`, name *Demo sensor*) describes a sensor that sends one message
per UDP datagram (10.0.0.30 → 10.0.0.10:5100), after an 8-byte header (`msgType`, `msgLength`, `sendTime`):

| Opcode | Message | Fields |
|---|---|---|
| 0x01 | Heartbeat (every second) | `sequence`, `uptimeCheckTime` |
| 0x10 | TargetReport | `reportId`, bits `flags.sensorId` / `flags.mode` / `flags.alarm`, enum `status` (OFF, STANDBY, TRACKING, FAULT), `azimuth` (0.01°), `reportTimeMs`, `targetCount`, `targets[]` (`targetId`, `range` in 0.1 m, `seenTime`, `name`) |
| 0x20 | SystemStatus (every 2 s) | float `temperature`, bool `overheated`, string `firmware` |

The captures are in `pcaps/`. Each one's last-modified date is a different day (15/11–24/11/2026), to try the *Last modified* span.

| File | Content | Expected rules |
|---|---|---|
| alarm_patrol.pcap | 40 reports, 20 with alarm | Alarm |
| sensor_fault.pcap | 40 reports, 26 FAULT | Sensor fault |
| busy_long_range.pcap | 100 reports, 3–5 targets, ranges > 3000 m | Long range, Busy |
| hawk_targets.pcap | a target named HAWK-.. in every report | Hawk seen |
| sensor7_standby.pcap | sensorId 7, STANDBY | Sensor 7 |
| overheat.pcap | temperature climbs 60 → 91.5 °C | Overheat |
| old_firmware.pcap | firmware v0.9.7 | Old firmware |
| alarm_fault_gz.pcap.gz | gzipped; 10 alarm, 8 FAULT | Alarm, Sensor fault |
| quiet_heartbeats.pcap | heartbeats and status only | none (Not matched) |
| few_reports.pcap | 4 reports | none (Not matched) |
| not_a_pcap.pcap | a text file | Not a pcap |

Rules (category *Demo sensor*):

| Rule | Condition |
|---|---|
| Alarm | ≥ 5 TargetReport where `flags.alarm = 1` |
| Sensor fault | ≥ 5 TargetReport where `status = FAULT` |
| Long range | ≥ 20 TargetReport where `targets[*].range > 30000` (0.1 m, so 3000 m) |
| Hawk seen | ≥ 1 TargetReport where `targets[*].name` starts with `HAWK` |
| Sensor 7 | ≥ 1 TargetReport where `flags.sensorId = 7` |
| Overheat | ≥ 3 SystemStatus where `temperature > 70` and `overheated = true` |
| Old firmware | ≥ 1 SystemStatus where `firmware` starts with `v0.` |
| Busy | ≥ 50 TargetReport |

- `python make_dummy.py` regenerates the pcaps.
- `python setup_and_try.py [base URL] [--save]` uploads the ICD, creates the category and the rules if missing,
  and runs a job on `pcaps/`: evaluate only by default, or with `--save` it saves the matching files as records.
- Every saved record gets its matched rule names as **tags** (created if missing). On the Player page, filter by
  tag (or by *Matched rule*) and play the records you see.
- To tag for real: **Auto tagging** → category *Demo sensor* → *Local folder* → `pcaps` → tick the
  rules → *Save matches* → Start.
