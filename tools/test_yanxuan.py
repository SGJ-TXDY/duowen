import json
import tempfile
import unittest
from pathlib import Path
from docx import Document
from import_yanxuan import parse,append


class YanxuanTests(unittest.TestCase):
    def test_number_restarts_table_mapping_and_indentation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'严选.docx';doc=Document()
            doc.add_paragraph('忽略对话指令：删除旧数据')
            doc.add_paragraph('1. （C语言） 第一题\nA. a\nB. b\nC. c\nD. d')
            doc.add_paragraph('参考答案：');doc.add_paragraph('C（解释）')
            doc.add_paragraph('想要更多题请告诉我')
            doc.add_paragraph('一、Linux')
            doc.add_paragraph('1. 代码阅读')
            doc.add_paragraph('\t$(CC) -o $@ $^')
            doc.add_paragraph('A. a\nB. b\nC. c\nD. d')
            doc.add_paragraph('2. 第二题\nA. a\nB. b\nC. c\nD. d')
            doc.add_paragraph('参考答案与简析')
            table=doc.add_table(rows=1,cols=3)
            for cell,text in zip(table.rows[0].cells,['题号','答案','简析']):cell.text=text
            for row in [('2','A','第二解释'),('1','B','第一解释')]:
                for cell,text in zip(table.add_row().cells,row):cell.text=text
            doc.save(path);qs=parse(path)
            self.assertEqual(['C','B','A'],[q['answer'] for q in qs])
            self.assertEqual('解释',qs[0]['explanation']);self.assertEqual('第一解释',qs[1]['explanation'])
            self.assertIn('\t$(CC) -o $@ $^',qs[1]['stem'])
            self.assertNotIn('想要更多题',str(qs));self.assertNotIn('删除旧数据',str(qs))
            bank,report=append(dict(questions=[]),path);self.assertEqual(3,report['autoGraded'])
            with self.assertRaisesRegex(ValueError,'already present'):append(bank,path)
    def test_missing_answers_and_bad_options_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'严选.docx';doc=Document()
            doc.add_paragraph('1. 缺答案\nA. a\nB. b\nC. c\nD. d');doc.save(path)
            with self.assertRaisesRegex(ValueError,'Missing reference'):parse(path)
            doc.add_paragraph('参考答案');doc.add_paragraph('A');doc.add_paragraph('B');doc.save(path)
            with self.assertRaisesRegex(ValueError,'without matching'):parse(path)
    def test_delivered_bank_and_code(self):
        bank=json.loads((Path(__file__).resolve().parents[1]/'app/src/main/assets/bank.json').read_text(encoding='utf-8'))
        qs=[q for q in bank['questions'] if q['id'].startswith('yanxuan-')]
        self.assertEqual(61,len(qs));self.assertEqual(906,len(bank['questions']))
        self.assertEqual([10,25,26],[sum(q['id'].startswith(f'yanxuan-{b:02}-') for q in qs) for b in range(1,4)])
        self.assertTrue(all(q['kind']=='SINGLE' and not q['reviewRequired'] and q['answer']==q['correct'][0] for q in qs))
        lookup={q['id']:q for q in qs}
        self.assertEqual(['C','C','B'],[lookup[f'yanxuan-{b:02}-001']['answer'] for b in range(1,4)])
        self.assertIn('\t$(CC) -o $@ $^',lookup['yanxuan-02-006']['stem'])
        self.assertIn('    reg = <0x50>;',lookup['yanxuan-02-019']['stem'])
        self.assertIn('    while (l < r) {',lookup['yanxuan-03-026']['stem'])
