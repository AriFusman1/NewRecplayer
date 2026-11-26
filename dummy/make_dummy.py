"""Writes dummy pcaps for trying auto tagging with the demo ICD (demo-sensor.icd.json, category "Demo sensor").

Every capture is UDP 10.0.0.30 -> 10.0.0.10:5100, one message per datagram: a Heartbeat (0x01) every
second, a SystemStatus (0x20) every 2 s, and TargetReports (0x10) at a rate set per scenario. Each scenario
is shaped so a different rule matches it (see README.md next to this script). Run: python make_dummy.py
"""
import gzip
import os
import struct
import sys
from dataclasses import dataclass, field

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools'))
from gen_demo import START, SERVICE, Capture  # noqa: E402
from gen_samples import eth, ipv4, udp  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'pcaps')
HEADER = struct.Struct('>HHI')  # msgType UINT16, msgLength UINT16, sendTime UINT32 (LTime)
OFF, STANDBY, TRACKING, FAULT = range(4)
DAY = 86400


@dataclass
class Scenario:
    name: str
    start_offset: int        # seconds after START; also sets the file's last-modified time
    seconds: int = 20
    reports_per_second: int = 2
    sensor_id: int = 3
    mode: int = 2
    status: callable = lambda i: TRACKING
    alarm: callable = lambda i: False
    target_count: callable = lambda i: 1 + i % 3
    target_name: callable = lambda i, t: f'TGT-{i % 100:02d}{t}'
    range_m: callable = lambda i, t: 1500 + 250 * t + i
    temperature: callable = lambda i: 45.0 + i % 5   # SystemStatus every 2 s, i = its index
    firmware: str = 'v1.4.2'
    gzipped: bool = False
    notes: list = field(default_factory=list)


def ltime(epoch_seconds):
    return int(epoch_seconds) % DAY


def message(opcode, body, epoch_seconds):
    return HEADER.pack(opcode, HEADER.size + len(body), ltime(epoch_seconds)) + body


def heartbeat(sequence, epoch_seconds):
    return message(0x01, struct.pack('>HI', sequence, ltime(epoch_seconds) - 2), epoch_seconds)


def target_report(scenario, report_id, epoch_seconds):
    count = scenario.target_count(report_id)
    flags = (scenario.sensor_id << 4) | (scenario.mode << 1) | (1 if scenario.alarm(report_id) else 0)
    azimuth = int((report_id * 13.5 % 360) / 0.01)
    body = struct.pack('>iBBiqB', report_id, flags, scenario.status(report_id), azimuth,
                       int(epoch_seconds * 1000) - 50, count)
    for t in range(count):
        name = scenario.target_name(report_id, t).encode('ascii')[:8].ljust(8, b'\0')
        range_raw = int(scenario.range_m(report_id, t) / 0.1)
        body += struct.pack('>HiI', 100 + t, range_raw, int(epoch_seconds) - 1 - t) + name
    return message(0x10, body, epoch_seconds)


def system_status(scenario, index, epoch_seconds):
    temperature = scenario.temperature(index)
    firmware = scenario.firmware.encode('ascii')[:8].ljust(8, b'\0')
    return message(0x20, struct.pack('>f?', temperature, temperature > 70) + firmware, epoch_seconds)


def build(scenario):
    capture = Capture()
    start = START + scenario.start_offset
    ident = 0
    report_id = 0
    steps_per_second = 10
    report_every = steps_per_second // scenario.reports_per_second if scenario.reports_per_second else None
    for step in range(scenario.seconds * steps_per_second):
        seconds = step / steps_per_second
        payloads = []
        if step % steps_per_second == 0:
            payloads.append(heartbeat(step // steps_per_second, start + seconds))
        if step % (2 * steps_per_second) == 5:
            payloads.append(system_status(scenario, step // (2 * steps_per_second), start + seconds))
        if report_every and step % report_every == 0:
            payloads.append(target_report(scenario, report_id, start + seconds))
            report_id += 1
        for payload in payloads:
            ident += 1
            capture.add(seconds + scenario.start_offset, eth(ipv4('10.0.0.30', SERVICE, 17, udp(40000, 5100, payload), ident=ident)))
    return capture, report_id


SCENARIOS = [
    Scenario('alarm_patrol', start_offset=0, alarm=lambda i: i % 2 == 0,
             notes=['20 reports with alarm = true']),
    Scenario('sensor_fault', start_offset=-1 * DAY, status=lambda i: FAULT if i % 3 else TRACKING,
             notes=['26 reports with status = FAULT']),
    Scenario('busy_long_range', start_offset=-2 * DAY, reports_per_second=5, target_count=lambda i: 3 + i % 3,
             range_m=lambda i, t: 3500 + 400 * t + i, notes=['100 reports, 3-5 targets each, ranges above 3000 m']),
    Scenario('hawk_targets', start_offset=-3 * DAY, target_name=lambda i, t: f'HAWK-{t}{i % 10}' if t == 0 else f'TGT-{t}',
             notes=['every report has a target named HAWK-..']),
    Scenario('sensor7_standby', start_offset=-4 * DAY, sensor_id=7, mode=1, status=lambda i: STANDBY,
             target_count=lambda i: 1, notes=['sensorId = 7, status = STANDBY, 1 target']),
    Scenario('quiet_heartbeats', start_offset=-5 * DAY, seconds=30, reports_per_second=0,
             notes=['heartbeats and SystemStatus only, no TargetReport']),
    Scenario('few_reports', start_offset=-6 * DAY, reports_per_second=1, seconds=4,
             notes=['only 4 reports, 4 heartbeats']),
    Scenario('overheat', start_offset=-8 * DAY, temperature=lambda i: 60.0 + 3.5 * i,
             notes=['SystemStatus temperature climbs 60 -> 91.5; 7 statuses above 70 (overheated)']),
    Scenario('old_firmware', start_offset=-9 * DAY, firmware='v0.9.7', notes=['firmware v0.9.7']),
    Scenario('alarm_fault_gz', start_offset=-7 * DAY, alarm=lambda i: i % 4 == 0,
             status=lambda i: FAULT if i % 5 == 0 else TRACKING, gzipped=True,
             notes=['gzipped; 10 alarm reports and 8 FAULT reports']),
]


def write(scenario):
    capture, reports = build(scenario)
    path = os.path.join(OUT, scenario.name + ('.pcap.gz' if scenario.gzipped else '.pcap'))
    capture.write(path)
    if scenario.gzipped:
        with open(path, 'rb') as raw:
            data = raw.read()
        with gzip.open(path, 'wb') as packed:
            packed.write(data)
    modified = START + scenario.start_offset + scenario.seconds
    os.utime(path, (modified, modified))
    return os.path.basename(path), len(capture.frames), reports


def main():
    os.makedirs(OUT, exist_ok=True)
    for scenario in SCENARIOS:
        name, packets, reports = write(scenario)
        print(f'{name:24} {packets:4} packets, {reports:3} reports  - {"; ".join(scenario.notes)}')
    not_a_pcap = os.path.join(OUT, 'not_a_pcap.pcap')
    with open(not_a_pcap, 'w', encoding='ascii') as text:
        text.write('This is a text file with a .pcap name: auto tagging reports it as Not a pcap.\n')
    print('not_a_pcap.pcap          (text file)')


main()
