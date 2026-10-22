import pathlib
import unittest

from pdf_extract import extract_pdf, markdown_row


FIXTURES = pathlib.Path(__file__).parent / 'fixtures'


class PdfExtractTest(unittest.TestCase):
    def test_repeated_margins_removed_but_unique_note_kept(self):
        pages = extract_pdf((FIXTURES / 'repeated_table.pdf').read_bytes())['pages']

        self.assertEqual(3, len(pages))
        self.assertTrue(all('COMMON HEADER' not in page['body'] for page in pages))
        self.assertTrue(all('COMMON FOOTER' not in page['body'] for page in pages))
        self.assertIn('Unique footnote', pages[0]['body'])
        self.assertIn('PAGE 2 BODY', pages[1]['body'])
        self.assertNotIn('Engineering', pages[1]['body'])

    def test_single_page_title_is_not_treated_as_repeated_header(self):
        page = extract_pdf((FIXTURES / 'one_page.pdf').read_bytes())['pages'][0]

        self.assertIn('COMMON HEADER', page['body'])

    def test_table_rows_keep_merged_group_and_column_labels(self):
        table = extract_pdf((FIXTURES / 'repeated_table.pdf').read_bytes())['pages'][1]['tables'][0]

        self.assertEqual(2, len(table['rows']))
        self.assertEqual(['Job', 'Position', 'Skills'], table['rows'][1]['headers'])
        self.assertEqual(['Engineering', 'Frontend', 'React'], table['rows'][1]['cells'])
        self.assertIn('| Engineering | Frontend | React |', table['rows'][1]['markdown'])
        self.assertIn('Java \\| SQL', table['rows'][0]['markdown'])
        self.assertEqual('COMPLETE', table['rows'][0]['extractionStatus'])

    def test_markdown_row_escapes_pipes_and_line_breaks(self):
        self.assertIn('| Java \\| SQL | first<br>second |',
                      markdown_row(['Skill', 'Notes'], ['Java | SQL', 'first\nsecond']))

    def test_repeated_header_only_page_requires_ocr(self):
        pages = extract_pdf((FIXTURES / 'header_only_scan.pdf').read_bytes())['pages']

        self.assertTrue(all(page['ocrRequired'] for page in pages))
        self.assertTrue(all(not page['body'].strip() for page in pages))

    def test_one_row_table_text_remains_in_body(self):
        page = extract_pdf((FIXTURES / 'one_row_table.pdf').read_bytes())['pages'][0]

        self.assertIn('Single row', page['body'])
        self.assertIn('Preserve me', page['body'])
        self.assertFalse(page['ocrRequired'])


if __name__ == '__main__':
    unittest.main()
