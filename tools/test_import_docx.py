import base64
import json
import tempfile
import unittest
from pathlib import Path
from docx import Document
from import_docx import parse, convert

class ImportTests(unittest.TestCase):
    def test_no_fabricated_answer_code_and_company(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'test.docx'
            d=Document();d.add_heading('C语言',1);d.add_heading('编程题',2)
            d.add_paragraph('题目001  公司-001');d.add_paragraph('来源：原图.png')
            d.add_paragraph('编写程序');d.add_paragraph('int main() {\n    return 0;\n}')
            d.add_heading('选择题',2);d.add_paragraph('题目002  公司-002')
            d.add_paragraph('哪些正确？（不定项）');d.add_paragraph('A. foo');d.add_paragraph('B. bar')
            d.save(p);qs=parse(p)
            self.assertEqual(len(qs),2);self.assertEqual(qs[0]['kind'],'PROGRAMMING')
            self.assertIn('\n    return 0;',qs[0]['stem']);self.assertEqual(qs[0]['answer'],'')
            self.assertTrue(qs[0]['reviewRequired']);self.assertEqual(qs[1]['kind'],'MULTIPLE')
            self.assertEqual(qs[1]['company'],'公司');self.assertEqual(len(qs[1]['options']),2)
    def test_putes_answer_code_and_heading_boundary(self):
        from docx.enum.style import WD_STYLE_TYPE
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'test.docx';d=Document()
            d.styles.add_style('Question',WD_STYLE_TYPE.PARAGRAPH);d.styles.add_style('Code',WD_STYLE_TYPE.PARAGRAPH)
            d.add_heading('C',1);d.add_paragraph('Q106  带参宏实现两个数比较',style='Question')
            d.add_paragraph('说明');d.add_paragraph('    #define MAX(a,b) ...',style='Code')
            d.add_heading('参考资料',1);d.add_paragraph('不应混入答案')
            d.save(p);q=parse(p)[0]
            self.assertEqual(q['kind'],'PROGRAMMING');self.assertFalse(q['reviewRequired'])
            self.assertIn('    #define',q['referenceCode']);self.assertNotIn('不应混入答案',q['answer'])
    def test_delivered_bank(self):
        root=Path(__file__).resolve().parents[1]
        qs=json.loads((root/'app/src/main/assets/bank.json').read_text(encoding='utf-8'))['questions']
        self.assertEqual(906,len(qs));self.assertEqual(906,len({q['id'] for q in qs}))
        self.assertEqual(498,sum(q['id'].startswith('company-') for q in qs))
        self.assertEqual(306,sum(q['id'].startswith('pute-') for q in qs))
        self.assertEqual(152,sum(q['id'].startswith('company-') and bool(q['answer']) for q in qs))
        self.assertEqual(67,sum(q['id'].startswith('company-') and not q['reviewRequired'] for q in qs))
        for q in qs:
            if q['reviewRequired']: self.assertEqual([],q['correct'])
            for image in q['images']:
                self.assertGreater(len(base64.b64decode(image['data'])),0)
        self.assertEqual(4,sum(len(q['images']) for q in qs))
        self.assertEqual('PROGRAMMING',next(q for q in qs if q['id']=='pute-212')['kind'])

if __name__=='__main__':unittest.main()
