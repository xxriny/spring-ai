"""Extract positioned PDF text while keeping tables out of the page body."""

import io
import math
import re
from collections import Counter

import pdfplumber


def _margin_key(text):
    text = ' '.join(text.split())
    if re.fullmatch(r'(?:page\s+)?\d+(?:\s*(?:/|of)\s*\d+)?', text, re.IGNORECASE):
        return '<PAGE_NUMBER>'
    return text


def _inside(obj, box):
    x = (obj['x0'] + obj['x1']) / 2
    y = (obj['top'] + obj['bottom']) / 2
    return box[0] <= x <= box[2] and box[1] <= y <= box[3]


def markdown_row(headers, cells):
    def cell(value):
        return (value or '').replace('|', '\\|').replace('\n', '<br>').strip()

    return ('| ' + ' | '.join(map(cell, headers)) + ' |\n'
            + '| ' + ' | '.join('---' for _ in headers) + ' |\n'
            + '| ' + ' | '.join(map(cell, cells)) + ' |')


def _table_headers(page, table, extracted):
    headers = [(value or '').strip() for value in extracted[0]]
    if all(headers):
        return headers
    columns = table.rows[1].cells if len(table.rows) > 1 else []
    if not columns or any(box is None for box in columns):
        return [''] * len(headers)
    top, bottom = table.rows[0].bbox[1], table.rows[0].bbox[3]
    words = page.crop((table.bbox[0], top, table.bbox[2], bottom)).extract_words()
    recovered = [[] for _ in columns]
    for word in words:
        x = (word['x0'] + word['x1']) / 2
        for index, box in enumerate(columns):
            if box[0] <= x <= box[2]:
                recovered[index].append(word['text'])
                break
    return [' '.join(words) for words in recovered]


def _table_rows(page, table):
    extracted = table.extract()
    if len(extracted) < 2:
        return []
    headers = _table_headers(page, table, extracted)
    status = 'COMPLETE' if all(headers) else 'PARTIAL'
    spans = [None] * len(headers)
    rows = []
    for row_index, (raw_cells, row) in enumerate(zip(extracted[1:], table.rows[1:]), 1):
        bottoms = [box[3] for box in row.cells if box is not None]
        row_bottom = min(bottoms) if bottoms else row.bbox[3]
        cells = []
        for column, raw in enumerate(raw_cells):
            box = row.cells[column]
            value = (raw or '').strip()
            if box is None and spans[column] and spans[column][1] >= row_bottom - 1:
                value = spans[column][0]
            elif box is not None:
                spans[column] = (value, box[3]) if box[3] > row_bottom + 1 else None
            cells.append(value)
        rows.append({'rowIndex': row_index, 'headers': headers, 'cells': cells,
                     'markdown': markdown_row(headers, cells), 'extractionStatus': status})
    return rows


def extract_pdf(data: bytes) -> dict:
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        page_lines = [page.extract_text_lines() for page in pdf.pages]
        margin_counts = Counter()
        for page, lines in zip(pdf.pages, page_lines):
            keys = {_margin_key(line['text']) for line in lines
                    if line['top'] < page.height * 0.1 or line['bottom'] > page.height * 0.9}
            margin_counts.update(keys)
        repeated = {key for key, count in margin_counts.items()
                    if key and count >= 2 and count >= math.ceil(len(pdf.pages) * 0.6)}

        pages = []
        for number, (page, lines) in enumerate(zip(pdf.pages, page_lines), 1):
            tables = [(index, table, _table_rows(page, table))
                      for index, table in enumerate(page.find_tables(), 1)]
            excluded = [table.bbox for _, table, rows in tables if rows]
            excluded.extend((line['x0'], line['top'], line['x1'], line['bottom'])
                            for line in lines
                            if _margin_key(line['text']) in repeated
                            and (line['top'] < page.height * 0.1 or line['bottom'] > page.height * 0.9))
            body_page = page.filter(lambda obj: obj.get('object_type') != 'char'
                                    or not any(_inside(obj, box) for box in excluded))
            body = body_page.extract_text() or ''
            pages.append({'pageNumber': number,
                          'body': body,
                          'ocrRequired': not body.strip() and not any(rows for _, _, rows in tables),
                          'tables': [{'tableIndex': index, 'rows': rows}
                                     for index, _, rows in tables]})
        return {'pages': pages}
