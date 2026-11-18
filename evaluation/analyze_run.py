"""Post-hoc failure cases and query-embedding latency accounting. No model calls."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import statistics
import tempfile
import unittest


def read(path):
    return json.loads(path.read_text('utf-8'))


def rows(path):
    return [json.loads(line) for line in path.read_text('utf-8').splitlines()]


def percentile(values, p):
    values = sorted(values)
    at = (len(values) - 1) * p
    lo = int(at)
    hi = min(lo + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (at - lo)


def analyze(run):
    metrics = read(run / 'metrics.json')
    questions = rows(run / 'questions.jsonl')
    ledger = rows(run / 'cost-ledger.jsonl')
    starts, query_embedding_ms = {}, {}
    for item in ledger:
        if item['event'] == 'reserve':
            starts[item['request']] = item
        elif item['event'] == 'settle':
            start = starts[item['request']]
            if start['stage'] == 'embedding' and start.get('question'):
                key = (start['split'], start['arm'], start['question'])
                elapsed = (datetime.fromisoformat(item['at']) - datetime.fromisoformat(start['at'])).total_seconds() * 1000
                query_embedding_ms[key] = query_embedding_ms.get(key, 0) + elapsed
    normalized = {}
    for arm in ('A', 'B'):
        durations = [max(0, item['latencyMs'] - query_embedding_ms.get((item['split'], arm, item['questionId']), 0))
                     for item in questions if item['arm'] == arm]
        normalized[arm] = dict(n=len(durations), p50Ms=statistics.median(durations), p95Ms=percentile(durations, .95))
    scoring = {(item['arm'], item['questionId']): item['scores'] for item in metrics['perQuestion']}
    by_question = {}
    for item in questions:
        by_question.setdefault(item['questionId'], {})[item['arm']] = item
    reviews = read(run / 'reviews.json') if (run / 'reviews.json').exists() else {'items': []}
    review = {item['token']: item for item in reviews['items']}
    cases = []
    for ident, pair in by_question.items():
        gold = pair['A']['gold']
        if not gold['answerable']:
            continue
        a = scoring[('A', ident)].get('answerAccuracy')
        b = scoring[('B', ident)].get('answerAccuracy')
        if a is None or b is None or a == b == 1:
            continue
        case = dict(questionId=ident, split=pair['A']['split'], documentId=pair['A']['documentId'],
                    question=gold['question'], category=gold['category'], requiredFacts=gold['requiredFacts'],
                    classification='improved' if b > a else 'regressed' if b < a else 'both_incorrect')
        for arm in ('A', 'B'):
            result = pair[arm]
            decision = review[result['reviewToken']]
            case[arm] = dict(answer=result['answer'], grounded=result['grounded'],
                             answerCorrect=decision['answerCorrect'],
                             evidenceRecall=scoring[(arm, ident)].get('evidenceRecall@5'),
                             contextEvidenceRecall=scoring[(arm, ident)].get('contextEvidenceRecall'),
                             citedEvidenceRecall=scoring[(arm, ident)].get('citedEvidenceRecall'),
                             reviewNotes=decision.get('rationale', decision.get('notes')))
        cases.append(case)
    payload = dict(schemaVersion=1, normalizedQuestionLatencyExcludingSharedQueryEmbedding=normalized,
                   latencyMethod='Subtract measured reserve-to-settle query embedding duration from each raw answer latency. '
                                 'A fills the shared cache; B hits it. This is a post-hoc approximation, not randomized performance testing.',
                   comparisonLimitations=['Fixed A-then-B order can be affected by provider caching and network variation.',
                                          'Evidence gold spans are paragraph-level; interval completeness is stricter than semantic support alone.',
                                          'Only 8 fictional holdout documents; no claim of real financial-domain generalization.'],
                   goldIssues=[{'token': item['token'], 'issue': item['goldIssue']}
                               for item in reviews['items'] if item.get('goldIssue')],
                   failureCounts={kind: sum(case['classification'] == kind for case in cases)
                                  for kind in ('improved', 'regressed', 'both_incorrect')}, cases=cases)
    (run / 'post-analysis.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    lines = ['# 配对失败与改善案例', '', '由 `evaluation/analyze_run.py` 从冻结运行与独立复核输出生成；不是人工评分。', '']
    for kind, title in (('regressed', 'B 退步'), ('improved', 'B 改善'), ('both_incorrect', '两组均未正确')):
        selected = [case for case in cases if case['classification'] == kind]
        lines += [f'## {title}：{len(selected)} 题（以下至多展示 3 题）', '']
        for case in selected[:3]:
            lines += [f'### {case["questionId"]} · {case["question"]}', '',
                      '必要事实：' + '；'.join(case['requiredFacts']), '']
            for arm in ('A', 'B'):
                lines += [f'**{arm}**：{case[arm]["answer"]}', '', '复核：' + case[arm]['reviewNotes'], '']
    (run / 'failure-analysis.md').write_text('\n'.join(lines), 'utf-8')
    return payload


class AnalysisTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.run = Path(self.temp.name)
        questions, scores, reviews = [], [], []
        for number, (a, b) in enumerate(((0, 1), (1, 0), (0, 0), (1, 1))):
            for arm, correct in (('A', a), ('B', b)):
                token = f'{number}-{arm}'
                questions.append(dict(arm=arm, questionId=str(number), documentId='doc', split='validation',
                                      latencyMs=1000, gold=dict(answerable=True, question='question',
                                      category='facts', requiredFacts=['fact']), answer='answer', grounded=True,
                                      reviewToken=token))
                scores.append(dict(arm=arm, questionId=str(number), scores={'answerAccuracy': correct}))
                reviews.append(dict(token=token, answerCorrect=bool(correct), notes='reason',
                                    goldIssue='fixture issue' if token == '0-A' else None))
        (self.run / 'metrics.json').write_text(json.dumps({'perQuestion': scores}), 'utf-8')
        (self.run / 'questions.jsonl').write_text('\n'.join(map(json.dumps, questions)), 'utf-8')
        (self.run / 'reviews.json').write_text(json.dumps({'items': reviews}), 'utf-8')
        ledger = [dict(event='reserve', request='one', stage='embedding', question='0', split='validation',
                       arm='A', at='2026-11-18T00:00:00+00:00'),
                  dict(event='settle', request='one', at='2026-11-18T00:00:00.200+00:00')]
        (self.run / 'cost-ledger.jsonl').write_text('\n'.join(map(json.dumps, ledger)), 'utf-8')

    def test_cases_preserve_both_arms_and_gold_issues(self):
        result = analyze(self.run)
        self.assertEqual(result['failureCounts'], dict(improved=1, regressed=1, both_incorrect=1))
        self.assertEqual(len(result['goldIssues']), 1)
        self.assertEqual(result['cases'][0]['A']['reviewNotes'], 'reason')
        self.assertTrue((self.run / 'failure-analysis.md').exists())

    def test_query_embedding_is_subtracted_only_from_its_own_arm_and_question(self):
        result = analyze(self.run)['normalizedQuestionLatencyExcludingSharedQueryEmbedding']
        self.assertEqual(result['A']['n'], 4)
        self.assertEqual(result['B']['p50Ms'], 1000)
        self.assertEqual(result['A']['p95Ms'], 1000)
        ledger = rows(self.run / 'cost-ledger.jsonl')
        for question in ('1', '2'):
            for item in ledger[:2]:
                item = dict(item, request=question)
                if item['event'] == 'reserve':
                    item['question'] = question
                ledger.append(item)
        (self.run / 'cost-ledger.jsonl').write_text('\n'.join(map(json.dumps, ledger)), 'utf-8')
        result = analyze(self.run)['normalizedQuestionLatencyExcludingSharedQueryEmbedding']
        self.assertEqual(result['A']['p50Ms'], 800)
        self.assertEqual(result['B']['p50Ms'], 1000)

    def test_unanswerable_is_excluded_from_answer_failure_cases(self):
        questions = rows(self.run / 'questions.jsonl')
        for item in questions:
            item['gold']['answerable'] = False
        (self.run / 'questions.jsonl').write_text('\n'.join(map(json.dumps, questions)), 'utf-8')
        self.assertEqual(analyze(self.run)['cases'], [])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=['analyze_run.py'])
    if not args.run:
        parser.error('--run is required')
    result = analyze(args.run)
    print(json.dumps({key: result[key] for key in ('failureCounts', 'normalizedQuestionLatencyExcludingSharedQueryEmbedding')}, indent=2))
