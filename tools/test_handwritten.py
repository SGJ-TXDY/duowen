import json
import tempfile
import unittest
from pathlib import Path
from docx import Document
from import_handwritten import parse, append

class HandwrittenTests(unittest.TestCase):
    def test_multistep_grouping_and_source_preservation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'手写代码.docx';doc=Document()
            lines=['手撕代码','1）将连续的出现的相同字符压缩','如”aaabbb”→a3b3','2）压缩后更长则返回原字符串','3）进行XOR加密','例如“secret”','对加密后的数据进行解密','单链表反转','其他：','触摸屏的应用层代码','1）实现数据的读取函数','2）数据的解析，按照结构体的要求']
            for text in lines:doc.add_paragraph(text)
            doc.save(path);qs,covered=parse(path)
            self.assertEqual(3,len(qs));self.assertEqual([1,2,3,4,5,6,7,9,10,11],covered)
            self.assertIn('对加密后的数据进行解密',qs[0]['stem'])
            self.assertIn('1）实现数据的读取函数',qs[2]['stem'])
            self.assertTrue(all(q['kind']=='PROGRAMMING' and q['reviewRequired'] and not q['answer'] and not q['referenceCode'] for q in qs))
            self.assertEqual('手写代码',qs[0]['source'])
            bank,report=append(dict(questions=[]),path)
            self.assertEqual(3,report['added'])
            with self.assertRaisesRegex(ValueError,'already present'):append(bank,path)
    def test_delivered_programming_separation(self):
        root=Path(__file__).resolve().parents[1]
        qs=json.loads((root/'app/src/main/assets/bank.json').read_text(encoding='utf-8'))['questions']
        handwritten=[q for q in qs if q['source']=='手写代码']
        self.assertEqual(41,len(handwritten));self.assertTrue(all(q['kind']=='PROGRAMMING' for q in handwritten))
        self.assertEqual(827,sum(q['kind']!='PROGRAMMING' for q in qs));self.assertEqual(79,sum(q['kind']=='PROGRAMMING' for q in qs))
        self.assertEqual(49,sum(len(q['raw'].splitlines()) for q in handwritten))
