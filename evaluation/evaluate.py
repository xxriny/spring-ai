"""Offline fixture validation or explicit live retrieval evaluation."""

import argparse
import base64
import datetime as dt
import json
import os
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen


QUESTIONS = Path(__file__).with_name('questions.json')
K = (1, 3, 5, 10)
STAGES = ('vectorCandidates', 'bm25Candidates', 'candidates', 'reranked', 'passages')


def validate(questions):
    if len(questions) < 12 or len(questions) > 20:
        raise ValueError('expected 12 to 20 fixed questions')
    urls = {item['expectedUrl'] for item in questions}
    if len(urls) != 4 or any(sum(item['expectedUrl'] == url and item['language'] == 'en'
                                 for item in questions) < 3 for url in urls):
        raise ValueError('expected four URLs with at least three English questions each')
    if len({item['id'] for item in questions}) != len(questions):
        raise ValueError('question IDs must be unique')
    for item in questions:
        if item['language'] not in ('en', 'ko') or not item['query'].strip() or not item['evidencePhrases']:
            raise ValueError('invalid question')
    return sorted(urls)


def rank(hits, question, chunk):
    for index, hit in enumerate(hits, 1):
        if hit.get('url') != question['expectedUrl']:
            continue
        if not chunk or any(phrase.casefold() in hit.get('text', '').casefold()
                            for phrase in question['evidencePhrases']):
            return index
    return None


def evaluate_case(question, response):
    stages = {}
    for stage in STAGES:
        hits = response.get(stage, [])
        stages[stage] = {'documentRank': rank(hits, question, False),
                         'chunkRank': rank(hits, question, True)}
    if stages['candidates']['chunkRank'] is None:
        status = 'candidate_miss'
    elif stages['reranked']['chunkRank'] is None:
        status = 'rerank_miss'
    elif 'passages' in response and stages['passages']['chunkRank'] is None:
        status = 'passage_miss'
    else:
        status = 'hit'
    return {'id': question['id'], 'language': question['language'], 'query': question['query'],
            'expectedUrl': question['expectedUrl'], 'status': status, **stages, 'response': response}


def collect_answer(record, request_fn):
    if 'response' not in record:
        return {'id': record['id'], 'stage': 'search', 'error': record['error']}
    try:
        conversation = request_fn('/api/conversations', {'scope': 'SHARED'})
        answer = request_fn(f"/api/conversations/{conversation['id']}/messages", {'text': record['query']})
        return {'id': record['id'], 'query': record['query'], 'expectedUrl': record['expectedUrl'],
                'passages': record['response']['passages'], 'answer': answer['answer'],
                'sources': answer['sources']}
    except Exception as error:
        return {'id': record['id'], 'stage': 'generation', 'error': str(error)}


def resume_answers(records, output, request_fn):
    completed = [json.loads(line) for line in output.read_text().splitlines()] if output.exists() else []
    if [row['id'] for row in completed] != [row['id'] for row in records[:len(completed)]]:
        raise ValueError('answer results do not match the search question order')
    with output.open('a') as raw:
        for record in records[len(completed):]:
            raw.write(json.dumps(collect_answer(record, request_fn), ensure_ascii=False) + '\n')
            raw.flush()
    return len(records)


def summarize(records):
    result = {}
    for language in ('all', 'en', 'ko'):
        group = [record for record in records if 'response' in record and
                 (language == 'all' or record['language'] == language)]
        report = {'n': len(group), 'errors': sum('error' in record and
                 (language == 'all' or record['language'] == language) for record in records)}
        for stage in STAGES:
            report[stage] = {}
            for level, key in (('document', 'documentRank'), ('chunk', 'chunkRank')):
                ranks = [record[stage][key] for record in group]
                report[stage][level] = {f'hit@{k}': sum(r is not None and r <= k for r in ranks) / len(ranks)
                                        if ranks else 0 for k in K}
                report[stage][level]['mrr'] = sum(1 / r for r in ranks if r is not None) / len(ranks) if ranks else 0
        result[language] = report
    return result


def request(base, path, password, payload=None, timeout=90):
    headers = {'Authorization': 'Basic ' + base64.b64encode(f'admin:{password}'.encode()).decode()}
    if payload is not None:
        headers['Content-Type'] = 'application/json'
    data = json.dumps(payload).encode() if payload is not None else None
    with urlopen(Request(base.rstrip('/') + path, data=data, headers=headers), timeout=timeout) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--execute', action='store_true', help='ingest four pages and issue 16 live searches')
    parser.add_argument('--answers', action='store_true', help='also generate answers for Spring AI evaluation')
    parser.add_argument('--resume-dir', help='continue missing answers in an existing result directory')
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    args = parser.parse_args()
    if args.answers and not args.execute:
        parser.error('--answers requires --execute')
    if args.resume_dir and args.execute:
        parser.error('--resume-dir cannot be combined with --execute')
    questions = json.loads(QUESTIONS.read_text())
    urls = validate(questions)
    if args.resume_dir:
        output = Path(args.resume_dir).resolve()
        if output.parent != (Path(__file__).parent / 'results').resolve():
            parser.error('--resume-dir must be an evaluation/results run directory')
        records = [json.loads(line) for line in (output / 'search.jsonl').read_text().splitlines()]
        if len(records) != len(questions) or [row['id'] for row in records] != [row['id'] for row in questions]:
            parser.error('search results are incomplete or do not match questions.json')
        password = os.environ.get('EVAL_ADMIN_PASSWORD')
        if not password:
            parser.error('EVAL_ADMIN_PASSWORD is required for --resume-dir')
        resume_answers(records, output / 'answer.jsonl', lambda path, payload:
            request(args.base_url, path, password, payload, timeout=300))
        print(json.dumps({'output': str(output), 'answers': len(records)}, ensure_ascii=False))
        return
    if not args.execute:
        print(json.dumps({'questions': len(questions), 'corpusUrls': urls, 'quality': '미측정'}, ensure_ascii=False, indent=2))
        return
    password = os.environ.get('EVAL_ADMIN_PASSWORD')
    if not password:
        parser.error('EVAL_ADMIN_PASSWORD is required for --execute')
    now = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = Path(__file__).parent / 'results' / now
    output.mkdir(parents=True, exist_ok=False)
    metadata = {'timeUtc': now, 'corpusUrls': urls, 'embeddingModel': 'qwen3-embedding:8b/1536',
                'rerankerModel': 'Qwen/Qwen3-Reranker-0.6B', 'topK': 10, 'threshold': 0.3,
                'bm25': 'pg_search 0.26.0', 'fusion': 'RRF k=60', 'passageTopK': 3,
                'category': 'Spring AI docs', 'maxPages': 1}
    (output / 'config.json').write_text(json.dumps(metadata, indent=2))
    with (output / 'ingest.jsonl').open('w') as raw:
        for url in urls:
            response = request(args.base_url, '/api/admin/crawl', password,
                               {'url': url, 'category': 'Spring AI docs', 'maxPages': 1})
            raw.write(json.dumps({'url': url, 'response': response}) + '\n')
            raw.flush()
            if response['errors'] or response['indexed'] != 1:
                raise RuntimeError(f'ingest failed for {url}; see {output / "ingest.jsonl"}')
    records = []
    with (output / 'search.jsonl').open('w') as raw:
        for question in questions:
            try:
                path = '/api/search?' + urlencode({'query': question['query'], 'scope': 'SHARED'})
                record = evaluate_case(question, request(args.base_url, path, password))
            except Exception as error:
                record = {'id': question['id'], 'language': question['language'], 'error': str(error)}
            records.append(record)
            raw.write(json.dumps(record, ensure_ascii=False) + '\n')
            raw.flush()
    report = summarize(records)
    (output / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    if args.answers:
        resume_answers(records, output / 'answer.jsonl', lambda path, payload:
            request(args.base_url, path, password, payload, timeout=300))
    print(json.dumps({'output': str(output), 'summary': report}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
