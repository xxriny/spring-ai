"""Loopback OCR adapter for Korean PP-OCRv5."""

import base64
import binascii
import io
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

from pdf_extract import extract_pdf


def recognize(request, predict, decode_image):
    if not isinstance(request, dict) or not isinstance(request.get('image'), str) or not request['image']:
        raise ValueError('image required')
    try:
        image = base64.b64decode(request['image'], validate=True)
    except binascii.Error as error:
        raise ValueError('invalid image') from error
    if not image:
        raise ValueError('image required')
    lines = []
    for page in predict(decode_image(image)):
        lines.extend(page.json['res']['rec_texts'])
    return {'text': '\n'.join(lines)}


def extract_pdf_request(content_type, body):
    if content_type.split(';', 1)[0].strip().lower() != 'application/pdf':
        raise ValueError('PDF content type required')
    if not body or len(body) > 20_000_000:
        raise ValueError('invalid PDF request size')
    return extract_pdf(body)


def handler(predict, decode_image):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/health':
                self.send_error(404)
                return
            self.send_response(200)
            self.end_headers()

        def do_POST(self):
            if self.path not in ('/ocr', '/pdf/extract'):
                self.send_error(404)
                return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                limit = 15_000_000 if self.path == '/ocr' else 20_000_000
                if size < 1 or size > limit:
                    raise ValueError('invalid request size')
                body = self.rfile.read(size)
                result = (recognize(json.loads(body), predict, decode_image) if self.path == '/ocr'
                          else extract_pdf_request(self.headers.get('Content-Type', ''), body))
            except ValueError:
                self.send_error(400, 'invalid document request')
                return
            except Exception:
                self.send_error(503, 'document extraction failed')
                return
            payload = json.dumps(result, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return Handler


def main():
    import numpy as np
    from PIL import Image
    from paddleocr import PaddleOCR

    model = PaddleOCR(lang='korean', ocr_version='PP-OCRv5',
                      use_doc_orientation_classify=False, use_doc_unwarping=False,
                      use_textline_orientation=False)

    def decode_image(data):
        with Image.open(io.BytesIO(data)) as image:
            return np.asarray(image.convert('RGB'))

    HTTPServer((os.getenv('SERVICE_HOST', '127.0.0.1'), 8092), handler(model.predict, decode_image)).serve_forever()


if __name__ == '__main__':
    main()
