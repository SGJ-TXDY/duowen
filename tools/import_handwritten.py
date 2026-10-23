"""Import the supplied handwritten-code checklist; missing solutions remain missing."""
import argparse
import base64
import hashlib
import json
import re
from pathlib import Path
from docx import Document
from docx.oxml.ns import qn
from import_docx import blocks


def parse(path):
    doc=Document(path)
    category='C基础与算法';current=None;questions=[];covered=[]
    headings={'手撕代码':'C基础与算法','数据结构':'数据结构','其他：':'线程与设备通信'}
    def finish():
        nonlocal current
        if current:
            current['stem']='\n'.join(current.pop('_parts'))
            current['raw']=current['stem']
            questions.append(current);current=None
    for index,style,text,element in blocks(doc):
        if not text.strip():continue
        if text.strip() in headings:
            finish();category=headings[text.strip()];continue
        continuation=bool(re.match(r'^(如[”"“]|例如|[23][）.]|对加密后的)',text))
        continuation=continuation or (text.startswith('1）实现数据的读取函数') and current is not None and current['_parts'][0].startswith('触摸屏'))
        if not continuation or current is None:
            finish()
            current=dict(id=f'handwritten-{index:03}',kind='PROGRAMMING',source='手写代码',company='未注明',
                         category='手写代码 / '+category,difficulty='未标注',options=[],answer='',correct=[],
                         explanation='',reviewRequired=True,images=[],examples='',constraints='',approach='',
                         referenceCode='',locator=f'{path.name} / body {index}',duplicateOf='',_parts=[])
        current['_parts'].append(text);covered.append(index)
        for blip in element.iter(qn('a:blip')):
            rid=blip.get(qn('r:embed'))
            if rid:
                part=doc.part.related_parts[rid]
                current['images'].append(dict(name=str(part.partname).split('/')[-1],data=base64.b64encode(part.blob).decode()))
    finish()
    if not questions:raise ValueError('No handwritten questions found')
    return questions,covered


def append(bank,path):
    questions,covered=parse(path)
    existing={q['id'] for q in bank['questions']}
    if existing & {q['id'] for q in questions}:raise ValueError('Handwritten IDs already present')
    def fingerprint(q):
        return re.sub(r'\s+','',q['stem'])+json.dumps(q['options'],ensure_ascii=False)
    seen={fingerprint(q):q['id'] for q in bank['questions']};duplicates=[]
    for q in questions:
        key=fingerprint(q)
        if key in seen:
            q['duplicateOf']=seen[key];duplicates.append([q['id'],seen[key]])
        else:seen[key]=q['id']
    bank['questions'].extend(questions);bank['bankRevision']=3
    return bank,dict(source=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                     added=len(questions),body_blocks_preserved=covered,duplicates=duplicates,
                     programming_only=True,reference_answers=0,
                     notes=['原文为题目清单，无参考答案或代码；不生成答案。','连续字符压缩及加密、触摸屏读写解析按原文多步骤合为同题。','原文不完整、疑似笔误或未给出接口与结构体时保留原文并标记待核对。'])


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('bank',type=Path);ap.add_argument('docx',type=Path);ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args();bank,report=append(json.loads(args.bank.read_text(encoding='utf-8')),args.docx)
    args.out.write_text(json.dumps(bank,ensure_ascii=False,indent=2),encoding='utf-8')
    args.out.with_suffix('.handwritten-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=True))
