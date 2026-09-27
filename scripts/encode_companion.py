#!/usr/bin/env python3
"""Encode one UTF-8 tool JSON file for loss-detecting Companion screen transport."""
import base64
import hashlib
import json
import sys
from pathlib import Path


def encode(raw):
    if len(raw) > 98304:
        raise ValueError('Decoded request exceeds 96 KiB; split the request')
    request = json.loads(raw.decode('utf-8'))
    if not isinstance(request, dict) or not isinstance(request.get('tool'), str) or 'encoding' in request:
        raise ValueError('Expected a normal tool request')
    return json.dumps({'encoding': 'base64-sha256-v1',
                       'payload_b64': base64.b64encode(raw).decode('ascii'),
                       'sha256': hashlib.sha256(raw).hexdigest()}, separators=(',', ':'))


if __name__ == '__main__':
    print('```text\nLUMENA_TOOL\n' + encode(Path(sys.argv[1]).read_bytes()) + '\n```')
