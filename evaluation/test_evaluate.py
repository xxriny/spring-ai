import unittest

from evaluate import evaluate_case, summarize


class EvaluationTest(unittest.TestCase):
    def setUp(self):
        self.question = {'id': 'q1', 'language': 'en', 'query': 'Where?',
                         'expectedUrl': 'https://docs.example/a',
                         'evidencePhrases': ['first fact', 'other valid fact']}

    def test_document_and_any_evidence_phrase(self):
        result = evaluate_case(self.question, {'candidates': [
            {'url': 'https://docs.example/a', 'text': 'unrelated', 'vectorScore': .9},
            {'url': 'https://docs.example/a', 'text': 'OTHER VALID FACT', 'vectorScore': .8}],
            'reranked': [{'url': 'https://docs.example/a', 'text': 'OTHER VALID FACT', 'rerankerScore': 2}]})
        self.assertEqual(1, result['candidates']['documentRank'])
        self.assertEqual(2, result['candidates']['chunkRank'])
        self.assertEqual(1, result['reranked']['chunkRank'])
        self.assertEqual('hit', result['status'])

    def test_candidate_miss_and_rerank_miss_are_distinct(self):
        missing = evaluate_case(self.question, {'candidates': [], 'reranked': []})
        self.assertEqual('candidate_miss', missing['status'])
        dropped = evaluate_case(self.question, {'candidates': [
            {'url': self.question['expectedUrl'], 'text': 'first fact'}], 'reranked': []})
        self.assertEqual('rerank_miss', dropped['status'])

    def test_empty_denominator_and_language_groups(self):
        self.assertEqual(0, summarize([])['all']['n'])
        en = evaluate_case(self.question, {'candidates': [
            {'url': self.question['expectedUrl'], 'text': 'first fact'}], 'reranked': []})
        ko_question = dict(self.question, id='q2', language='ko')
        ko = evaluate_case(ko_question, {'candidates': [], 'reranked': []})
        report = summarize([en, ko])
        self.assertEqual(2, report['all']['n'])
        self.assertEqual(1, report['en']['n'])
        self.assertEqual(1, report['ko']['n'])
        self.assertEqual(.5, report['all']['candidates']['document']['hit@1'])

    def test_reports_vector_bm25_fusion_rerank_and_final_passage_separately(self):
        evidence = {'url': self.question['expectedUrl'], 'text': 'first fact'}
        result = evaluate_case(self.question, {
            'vectorCandidates': [], 'bm25Candidates': [evidence], 'candidates': [evidence],
            'reranked': [evidence], 'passages': [evidence]})
        self.assertIsNone(result['vectorCandidates']['chunkRank'])
        self.assertEqual(1, result['bm25Candidates']['chunkRank'])
        self.assertEqual(1, result['passages']['chunkRank'])
        self.assertEqual(1, summarize([result])['all']['passages']['chunk']['hit@1'])


if __name__ == '__main__':
    unittest.main()
