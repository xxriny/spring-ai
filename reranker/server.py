"""Loopback HTTP adapter for Qwen3-Reranker-0.6B."""

import json
import math
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def rerank(request, predict):
    if not isinstance(request, dict):
        raise ValueError('JSON object required')
    query, documents = request.get('query'), request.get('documents')
    if not isinstance(query, str) or not query.strip():
        raise ValueError('query required')
    if not isinstance(documents, list) or not 1 <= len(documents) <= 10 or any(
            not isinstance(document, str) or not document.strip() for document in documents):
        raise ValueError('documents must contain 1 to 10 nonempty strings')
    scores = [float(score) for score in predict([(query, document) for document in documents])]
    if len(scores) != len(documents) or not all(math.isfinite(score) for score in scores):
        raise ValueError('invalid model scores')
    return {'scores': scores}


def handler(predict):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != '/rerank':
                self.send_error(404)
                return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if size < 1 or size > 100_000:
                    raise ValueError('invalid request size')
                result = rerank(json.loads(self.rfile.read(size)), predict)
            except (ValueError, TypeError, json.JSONDecodeError) as error:
                self.send_error(400, str(error))
                return
            except Exception:
                self.send_error(503, 'reranker failed')
                return
            payload = json.dumps(result).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return Handler


def main():
    from sentence_transformers import CrossEncoder

    model = CrossEncoder('Qwen/Qwen3-Reranker-0.6B')
    ThreadingHTTPServer(('127.0.0.1', 8091), handler(model.predict)).serve_forever()


if __name__ == '__main__':
    main()
