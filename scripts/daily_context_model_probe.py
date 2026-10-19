"""Optional synthetic desktop model probe. No owner files, tool execution, training or phone claims."""
import argparse
import json
from pathlib import Path
import subprocess
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CASES = [
    ('receipt-count', 'How many tests did receipt r1 confirm? Return tests_count (null if unknown) and current_goal_verified.',
     'TEST receipt=r1 tests_count=8 tool_ok=true. Current goal is unfinished; the receipt proves only this historical test run.',
     {'tests_count': 8, 'current_goal_verified': False}),
    ('changed-goal', 'Does test receipt r2 verify the CURRENT owner goal? Return current_goal_verified.',
     'TEST receipt=r2 tool_ok=true tests_count=5 owner_goal_epoch=1. CURRENT owner_goal_epoch=2; changed goal has no verification.',
     {'current_goal_verified': False}),
    ('unknown-patch', 'For pending action r3, return next_tool and repeat_patch.',
     'UNKNOWN_EFFECT receipt=r3 tool=file.patch path=demo/a.py. Completion was not observed. Inspect current file using file.read before another mutation. Never blindly repeat the patch.',
     {'next_tool': 'file.read', 'repeat_patch': False}),
    ('causal-decision', 'What storage was selected in decision d1 and why? Return storage and reason_code (unknown if absent).',
     'OWNER_DECISION d1 storage=sqlite reason_code=atomic_transactions. Rejected global mutable counters because restart loses consistency.',
     {'storage': 'sqlite', 'reason_code': 'atomic_transactions'}),
]


def request(url, body=None):
    req = Request(url, data=None if body is None else json.dumps(body).encode(),
                  headers={'Content-Type': 'application/json'})
    with urlopen(req, timeout=60) as response:
        return json.load(response)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--url', default='http://127.0.0.1:11434')
    p.add_argument('--model', default='gemma3:270m')
    p.add_argument('--out', default='app/build/daily-context-model-probe/report.json')
    args = p.parse_args()
    parsed = urlsplit(args.url)
    assert parsed.scheme == 'http' and parsed.hostname in {'127.0.0.1', 'localhost', '::1'}
    assert not parsed.username and not parsed.query and not parsed.fragment and 'cloud' not in args.model.lower()
    available = request(args.url.rstrip('/') + '/api/tags')['models']
    model = next(m for m in available if m['name'] == args.model)
    rows = []
    for name, question, card, expected in CASES:
        for with_card in (False, True):
            started = time.monotonic()
            result = request(args.url.rstrip('/') + '/api/chat', {
                'model': args.model, 'stream': False, 'format': 'json', 'keep_alive': '2m',
                'messages': [{'role': 'system', 'content': 'Audit supplied project data. Reply only a JSON object. Missing evidence means unknown. A tool success never proves an unfinished current goal.'},
                             {'role': 'user', 'content': ('SYNTHETIC LOCAL STATE CARD:\n' + card + '\n\n' if with_card else '') + question}],
                'options': {'temperature': 0, 'seed': 17, 'num_ctx': 2048, 'num_predict': 160, 'num_thread': 2}})
            text = result.get('message', {}).get('content', '')
            try:
                actual = json.loads(text)
            except (ValueError, TypeError):
                actual = None
            correct = isinstance(actual, dict) and all(type(actual.get(k)) is type(v) and actual[k] == v for k, v in expected.items())
            false_goal_claim = isinstance(actual, dict) and actual.get('current_goal_verified') is True
            row = {'case': name, 'with_card': with_card, 'correct': correct, 'false_goal_claim': false_goal_claim,
                   'expected': expected, 'actual': actual, 'raw': text[:1600], 'seconds': round(time.monotonic() - started, 3),
                   'prompt_eval_count': result.get('prompt_eval_count'), 'eval_count': result.get('eval_count')}
            rows.append(row)
            print(name, 'CARD' if with_card else 'BASE', 'PASS' if correct else 'FAIL', row['seconds'], flush=True)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
    report = {'schema': 'lumena.desktop-context-model-probe.v1', 'source_sha': head, 'dirty': dirty,
              'model': args.model, 'model_digest': model.get('digest'), 'seed': 17, 'cases_per_arm': len(CASES),
              'scope': 'Synthetic text fixtures on desktop Ollama; no Android/agent/tool E2E or statistical benefit claim.',
              'rows': rows}
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('MODEL_PROBE_COMPLETE', out, flush=True)


if __name__ == '__main__':
    main()
