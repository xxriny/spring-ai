import unittest

from server import recognize, extract_pdf_request, handler


class OcrTest(unittest.TestCase):
    def test_health_is_ready_after_model_load(self):
        endpoint_type = handler(lambda image: [], lambda data: data)
        endpoint = endpoint_type.__new__(endpoint_type)
        codes = []
        endpoint.path = '/health'
        endpoint.send_response = codes.append
        endpoint.end_headers = lambda: None
        endpoint.do_GET()
        self.assertEqual([200], codes)

    def test_recognize_joins_paddle_text_lines(self):
        image = b'fake-png'
        seen = []

        class Result:
            json = {'res': {'rec_texts': ['첫 줄', 'second line']}}

        def predict(data):
            seen.append(data)
            return [Result()]

        self.assertEqual({'text': '첫 줄\nsecond line'}, recognize({'image': 'ZmFrZS1wbmc='}, predict, lambda data: data))
        self.assertEqual([image], seen)

    def test_rejects_missing_or_invalid_image(self):
        for request in ({}, {'image': ''}, {'image': '*'}):
            with self.subTest(request=request), self.assertRaises(ValueError):
                recognize(request, lambda data: [], lambda data: data)

    def test_pdf_request_rejects_invalid_input(self):
        for content_type, body in [('text/plain', b'%PDF-1.4'), ('application/pdf', b''),
                                   ('application/pdf', b'x' * 20_000_001)]:
            with self.subTest(content_type=content_type, size=len(body)), self.assertRaises(ValueError):
                extract_pdf_request(content_type, body)


if __name__ == '__main__':
    unittest.main()
