"""Create arm-blind review bundles, deduplicating complete source texts by hash."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import tempfile
import unittest


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(run, parts=4):
    package = json.loads((run / 'blind-review.json').read_text('utf-8'))
    groups = {}
    tokens = set()
    for original in package['items']:
        item = dict(original)
        assert item['token'] not in tokens
        tokens.add(item['token'])
        source = item.pop('sourceText')
        ident = hashlib.sha256(source.encode('utf-8')).hexdigest()
        item['sourceRef'] = ident
        group = groups.setdefault(ident, {'text': source, 'items': []})
        assert source == group['text']
        group['items'].append(item)
    buckets = [[] for _ in range(parts)]
    sizes = [0] * parts
    for ident, group in sorted(groups.items(), key=lambda entry: (-len(entry[1]['items']), entry[0])):
        index = min(range(parts), key=lambda n: (sizes[n], n))
        buckets[index].append((ident, group))
        sizes[index] += len(group['items'])
    output = run / 'review-inputs'
    output.mkdir(exist_ok=True)
    for index, bucket in enumerate(buckets, 1):
        items = [item for _, group in bucket for item in group['items']]
        random.Random(20261118 + index).shuffle(items)
        payload = {'schemaVersion': 1, 'blindPackageSha256': sha(run / 'blind-review.json'),
                   'instructions': 'Review only this bundle and frozen rubric. No strategy, split or arm mapping is supplied.',
                   'sources': {ident: group['text'] for ident, group in bucket}, 'items': items}
        file = output / f'part-{index:02}.json'
        if file.exists():
            raise ValueError('Review input already exists; do not silently replace reviewed material')
        file.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return sizes


def merge(run):
    package = json.loads((run / 'blind-review.json').read_text('utf-8'))
    expected = {item['token']: item for item in package['items']}
    assignments = json.loads((run / 'review-assignments.json').read_text('utf-8'))
    merged, audit = {}, []
    for task in assignments['assignments']:
        source = run / task['input']
        result = run / task['output']
        bundle = json.loads(source.read_text('utf-8'))
        reviewed = json.loads(result.read_text('utf-8'))
        assert reviewed['independent'] is True
        required = {item['token'] for item in bundle['items']}
        found = {item['token'] for item in reviewed['items']}
        assert len(found) == len(reviewed['items']) and found == required
        assert bundle['blindPackageSha256'] == sha(run / 'blind-review.json')
        for item in reviewed['items']:
            token = item['token']
            assert token not in merged and token in expected
            needed = 'answerCorrect' if expected[token]['gold']['answerable'] else 'refusalCorrect'
            assert type(item.get(needed)) is bool
            assert isinstance(item.get('citationSupported'), list)
            assert len(item['citationSupported']) == len(expected[token]['citedTexts'])
            assert all(type(value) is bool for value in item['citationSupported'])
            item['rationale'] = item.get('rationale') or item.get('notes')
            assert isinstance(item['rationale'], str) and item['rationale'].strip()
            item['reviewerAgentId'] = task['agentId']
            merged[token] = item
        audit.append({**task, 'inputSha256': sha(source), 'outputSha256': sha(result)})
    assert set(merged) == set(expected)
    instructions = Path(__file__).with_name('blind-review-instructions.md')
    payload = {'schemaVersion': 1, 'blindPackageSha256': sha(run / 'blind-review.json'),
               'independent': True, 'reviewerKind': 'independent_codex_agent',
               'reviewer': ','.join(task['agentId'] for task in audit),
               'underlyingModelId': None, 'temperature': None,
               'modelMetadataNote': 'Not exposed by the Codex agent tool; no user vendor API calls for review.',
               'instructionsSha256': sha(instructions), 'reviewAudit': audit,
               'items': list(merged.values())}
    destination = run / 'reviews.json'
    if destination.exists():
        raise ValueError('Merged review already exists; preserve original adjudication')
    destination.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return len(merged)


class BlindReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.run = Path(self.temp.name)
        self.items = [dict(token=str(n), sourceText='source-' + str(n // 2),
                           gold={'answerable': True}, citedTexts=['evidence'], answer='answer') for n in range(4)]
        (self.run / 'blind-review.json').write_text(json.dumps({'items': self.items}), 'utf-8')

    def outputs(self):
        assignments = []
        for index in (1, 2):
            source = f'review-inputs/part-{index:02}.json'
            bundle = json.loads((self.run / source).read_text('utf-8'))
            output = f'output-{index}.json'
            (self.run / output).write_text(json.dumps({'independent': True, 'items': [
                dict(token=item['token'], answerCorrect=True, refusalCorrect=None,
                     citationSupported=[True], notes='independent fixture reason') for item in bundle['items']]}), 'utf-8')
            assignments.append(dict(input=source, output=output, agentId=f'fixture-agent-{index}'))
        (self.run / 'review-assignments.json').write_text(json.dumps({'assignments': assignments}), 'utf-8')

    def test_source_deduplication_token_coverage_and_complete_merge(self):
        self.assertEqual(prepare(self.run, 2), [2, 2])
        for path in (self.run / 'review-inputs').glob('*.json'):
            bundle = json.loads(path.read_text('utf-8'))
            self.assertEqual(len(bundle['sources']), 1)
            self.assertTrue(all('sourceText' not in item and 'arm' not in item for item in bundle['items']))
        self.outputs()
        self.assertEqual(merge(self.run), 4)
        combined = json.loads((self.run / 'reviews.json').read_text('utf-8'))
        self.assertEqual(len(combined['reviewAudit']), 2)
        self.assertIsNone(combined['underlyingModelId'])

    def test_unknown_token_cannot_be_merged(self):
        prepare(self.run, 2)
        self.outputs()
        result = self.run / 'output-1.json'
        obj = json.loads(result.read_text('utf-8'))
        obj['items'][0]['token'] = 'not-assigned'
        result.write_text(json.dumps(obj), 'utf-8')
        with self.assertRaises(AssertionError):
            merge(self.run)
        self.assertFalse((self.run / 'reviews.json').exists())

    def test_prepared_input_is_never_silently_overwritten(self):
        prepare(self.run, 2)
        with self.assertRaises(ValueError):
            prepare(self.run, 2)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path)
    parser.add_argument('--parts', type=int, default=4)
    parser.add_argument('--merge', action='store_true')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=['prepare_blind_review.py'])
    if not args.run:
        parser.error('--run is required')
    if args.parts < 1:
        parser.error('--parts must be positive')
    print(merge(args.run) if args.merge else prepare(args.run, args.parts))
