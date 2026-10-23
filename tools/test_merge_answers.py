import copy
import tempfile
import unittest
from pathlib import Path
from docx import Document
from docx.enum.style import WD_STYLE_TYPE
from merge_answers import merge

class SupplementTests(unittest.TestCase):
    def test_exact_answers_ambiguity_and_body_guard(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'answers.docx';d=Document()
            for style in ('Question','Answer'):d.styles.add_style(style,WD_STYLE_TYPE.PARAGRAPH)
            qs=[]
            for number,answer in ((1,'A'),(2,'通常 A，但取决于平台')):
                d.add_paragraph(f'题目{number:03}  公司-{number:03}',style='Question')
                for text in ('题干','A. 一','B. 二'):d.add_paragraph(text)
                d.add_paragraph('答案  '+answer,style='Answer');d.add_paragraph('解析  原文解释',style='Answer')
                qs.append(dict(id=f'company-{number:03}',locator=f'公司-{number:03}',raw='题干\nA. 一\nB. 二',stem='题干',options=[dict(key='A',text='A. 一'),dict(key='B',text='B. 二')],kind='SINGLE'))
            d.save(p);original=dict(questions=qs)
            bank,report=merge(copy.deepcopy(original),p)
            self.assertEqual(2,report['matched']);self.assertEqual(1,report['autoGraded'])
            self.assertEqual(['A'],bank['questions'][0]['correct']);self.assertTrue(bank['questions'][1]['reviewRequired'])
            self.assertEqual(qs[0]['options'],bank['questions'][0]['options'])
            changed=copy.deepcopy(original);changed['questions'][0]['raw']='changed'
            with self.assertRaisesRegex(ValueError,'body/options mismatch'):merge(changed,p)
            changed=copy.deepcopy(original);changed['questions'][0]['locator']='other'
            with self.assertRaisesRegex(ValueError,'Source mismatch'):merge(changed,p)
