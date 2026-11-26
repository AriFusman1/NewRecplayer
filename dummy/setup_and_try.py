"""Prepares the server for auto tagging the dummy pcaps, then runs an "evaluate only" job on them.

1. Uploads demo-sensor.icd.json (skipped if the ICD is already there).
2. Creates the UDP-server category "Demo sensor" with that ICD (reused if it exists).
3. Creates the example rules below (rules with the same name are kept as they are).
4. Runs a job on every file in pcaps/ and prints each file's status and matched rules. By default it is
   evaluate only (nothing saved); with --save the matching files become records tagged with their rules.

Run: python setup_and_try.py [base URL, default http://localhost:8090] [--save]
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PCAPS = os.path.join(HERE, 'pcaps')
ARGS = [arg for arg in sys.argv[1:] if arg != '--save']
SAVE = '--save' in sys.argv[1:]
BASE = ARGS[0] if ARGS else 'http://localhost:8090'
ICD_FILE = os.path.join(HERE, 'demo-sensor.icd.json')
ICD_ID = 'demo-sensor-v1'
CATEGORY_NAME = 'Demo sensor'
TARGET_REPORT = 0x10
SYSTEM_STATUS = 0x20


def fields(min_count, *predicates, opcode=TARGET_REPORT):
    return {'kind': 'FIELDS', 'opcode': opcode, 'minCount': min_count, 'predicates': list(predicates)}


def predicate(path, op, **value):
    return {'path': path, 'op': op, 'values': [value]}


RULES = [
    {'name': 'Alarm', 'conditions': [fields(5, predicate('flags.alarm', 'EQ', number='1'))]},
    {'name': 'Sensor fault', 'conditions': [fields(5, predicate('status', 'EQ', name='FAULT', number='3'))]},
    {'name': 'Long range', 'conditions': [fields(20, predicate('targets[*].range', 'GT', number='30000'))]},
    {'name': 'Hawk seen', 'conditions': [fields(1, predicate('targets[*].name', 'STARTS_WITH', text='HAWK'))]},
    {'name': 'Sensor 7', 'conditions': [fields(1, predicate('flags.sensorId', 'EQ', number='7'))]},
    {'name': 'Overheat', 'conditions': [fields(3, predicate('temperature', 'GT', number='70'),
                                               predicate('overheated', 'EQ', bool=True), opcode=SYSTEM_STATUS)]},
    {'name': 'Old firmware', 'conditions': [fields(1, predicate('firmware', 'STARTS_WITH', text='v0.'), opcode=SYSTEM_STATUS)]},
    {'name': 'Busy', 'conditions': [{'kind': 'COUNT', 'opcode': TARGET_REPORT, 'minCount': 50, 'predicates': []}]},
]


def call(method, path, body=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers={'Content-Type': 'application/json'} if data else {})
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f'{method} {path} -> {e.code}: {e.read().decode(errors="replace")}')


def ensure_icd():
    if not any(icd['id'] == ICD_ID for icd in call('GET', '/api/icds')):
        with open(ICD_FILE, 'rb') as icd:
            call('POST', '/api/icds', raw=icd.read())
        print('uploaded ICD', ICD_ID)


def ensure_category():
    catalog = call('GET', '/api/catalog')
    category = next((c for c in catalog['categories'] if c['name'] == CATEGORY_NAME), None)
    if category is None:
        category = call('POST', '/api/categories', {'name': CATEGORY_NAME, 'mode': 'UDP_SERVER'})
        print('created category', CATEGORY_NAME)
    if category.get('icdId') != ICD_ID:
        call('PUT', f'/api/categories/{category["id"]}/icd', {'icdId': ICD_ID})
    return category['id']


def ensure_rules(category_id):
    existing = {rule['name']: rule['id'] for rule in call('GET', f'/api/categories/{category_id}/tag-rules')['rules']}
    for rule in RULES:
        if rule['name'] not in existing:
            existing[rule['name']] = call('POST', f'/api/categories/{category_id}/tag-rules', {'id': None, **rule})['id']
            print('created rule', rule['name'])
    return [existing[rule['name']] for rule in RULES]


def run_job(category_id, rule_ids):
    names = sorted(os.listdir(PCAPS))
    inputs = [{'name': name, 'path': name, 'size': os.path.getsize(os.path.join(PCAPS, name))} for name in names]
    job = call('POST', '/api/tag-jobs', {'categoryId': category_id, 'ruleIds': rule_ids, 'evaluateOnly': not SAVE,
                                         'kind': 'LOCAL_FILES', 'files': inputs, 'createdBy': 'dummy script'})
    for index, name in enumerate(names):
        with open(os.path.join(PCAPS, name), 'rb') as pcap:
            call('POST', f'/api/tag-jobs/{job["id"]}/files/{index}', raw=pcap.read())
    call('POST', f'/api/tag-jobs/{job["id"]}/start')
    while job['status'] not in ('DONE', 'CANCELLED', 'FAILED'):
        time.sleep(0.5)
        job = call('GET', f'/api/tag-jobs/{job["id"]}')
    print(f'\njob {job["id"]}: {job["status"]}')
    for result in sorted(job['results'], key=lambda r: r['name']):
        rules = ', '.join(result['matchedRules']) or '-'
        print(f'{result["name"]:24} {result["status"]:12} {result["totalMessages"]:4} msgs  rules: {rules}')


def main():
    ensure_icd()
    category_id = ensure_category()
    run_job(category_id, ensure_rules(category_id))


main()
