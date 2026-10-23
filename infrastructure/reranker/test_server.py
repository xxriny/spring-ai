import json
import unittest

from server import rerank


class RerankTest(unittest.TestCase):
    def test_scores_follow_input_order(self):
        result = rerank({'query': 'q', 'documents': ['a', 'b']},
                        lambda pairs: [2.0, -1.0])
        self.assertEqual({'scores': [2.0, -1.0]}, result)

    def test_rejects_invalid_input(self):
        for value in ({'query': 'q', 'documents': []},
                      {'query': 'q', 'documents': ['x'] * 11},
                      {'query': '', 'documents': ['x']},
                      {'query': 'q', 'documents': ['']}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                rerank(value, lambda pairs: [1.0])

    def test_rejects_mismatched_or_nonfinite_scores(self):
        for scores in ([], [float('nan')]):
            with self.subTest(scores=scores), self.assertRaises(ValueError):
                rerank({'query': 'q', 'documents': ['x']}, lambda pairs: scores)


if __name__ == '__main__':
    unittest.main()
