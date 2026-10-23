"""Lossless, source-specific DOCX importer. Never generates missing answers.
Usage: python tools/import_docx.py file1.docx file2.docx --out bank.json
"""
import argparse
import base64
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from docx import Document
from docx.oxml.ns import qn
from docx.text.paragraph import Paragraph
from docx.table import Table

PUTE_PROGRAMMING = {106, 107, 205, 206, 207, 210, 212}

def blocks(doc):
    for index, element in enumerate(doc.element.body):
        if element.tag == qn('w:p'):
            p = Paragraph(element, doc)
            yield index, p.style.name, p.text, element
        elif element.tag == qn('w:tbl'):
            table = Table(element, doc)
            yield index, 'Table', '\n'.join('\t'.join(c.text for c in r.cells) for r in table.rows), element

def parse(path):
    doc = Document(path)
    pute = any(p.style.name == 'Question' and re.match(r'Q\d{3}\s', p.text) for p in doc.paragraphs)
    source = '普特面试题 v2.3' if pute else '公司笔试题合集'
    category, section, current = '未分类', '', None
    result = []
    def finish():
        nonlocal current
        if not current:
            return
        parts = current.pop('_parts')
        number = current.pop('_number')
        current['raw'] = '\n'.join(t for _, t in parts)
        if pute:
            current['answer'] = current['raw']
            current['referenceCode'] = '\n'.join(t for s, t in parts if s == 'Code')
            current['approach'] = '\n'.join(t for s, t in parts if s != 'Code') if current['kind'] == 'PROGRAMMING' else ''
            current['reviewRequired'] = not bool(current['answer'].strip()) or bool(re.search('原文.*(缺失|不清晰|未给出)', current['answer']))
        else:
            body, options = [], []
            for _, text in parts:
                if text.startswith('来源：'):
                    current['locator'] += '\n' + text
                    continue
                match = re.match(r'^([A-H])(?:（[^）]*）)?[.．、]\s*(.*)', text, re.S)
                if match:
                    options.append({'key': match[1], 'text': text})
                elif options:
                    options[-1]['text'] += '\n' + text
                else:
                    body.append(text)
            current['stem'] = '\n'.join(body)
            current['options'] = options
            if options:
                current['kind'] = 'MULTIPLE' if re.search('多选|不定项',current['stem']) else 'SINGLE'
            elif '判断题' in section:
                current['kind'] = 'TRUE_FALSE'
                current['options'] = [{'key':'T','text':'正确'}, {'key':'F','text':'错误'}]
            # Examples and constraints remain verbatim in the full stem even if extraction is uncertain.
            current['examples'] = '\n'.join(t for _,t in parts if re.search('输入|输出|示例|样例',t))
            current['constraints'] = '\n'.join(t for _,t in parts if re.search('约束|范围|限制',t))
        if not current['stem'].strip():
            raise ValueError(f'Empty question: {current["id"]}')
        result.append(current)
        current = None
    for index, style, text, element in blocks(doc):
        if style.startswith('Heading'):
            finish()
            if style == 'Heading 1':
                category = text
            else:
                section = text
            continue
        match = re.match(r'^Q(\d{3})\s+(.+)', text) if pute else re.match(r'^题目(\d{3})\s+(.+)', text)
        if match:
            finish()
            number, title = int(match[1]), match[2]
            kind = 'SHORT'
            if (pute and number in PUTE_PROGRAMMING) or (not pute and '编程题' in section):
                kind = 'PROGRAMMING'
            elif not pute and '代码阅读' in section:
                kind = 'CODE_READING'
            current = dict(id=('pute-' if pute else 'company-')+match[1], stem=title if pute else '',
                           kind=kind, source=source, company='普特' if pute else re.sub(r'-\d+$','',title),
                           category=category+(' / '+section if pute else ''), difficulty='未标注',
                           options=[], answer='', correct=[], explanation='', reviewRequired=True,
                           images=[], examples='', constraints='', approach='', referenceCode='',
                           locator=f'{path.name} / body {index} / {text}', duplicateOf='', _parts=[], _number=number)
        elif current:
            current['_parts'].append((style,text))
        if current:
            for blip in element.iter(qn('a:blip')):
                rid = blip.get(qn('r:embed'))
                if rid:
                    part = doc.part.related_parts[rid]
                    current['images'].append(dict(name=str(part.partname).split('/')[-1], data=base64.b64encode(part.blob).decode()))
    finish()
    return result

def convert(paths):
    questions = [q for p in paths for q in parse(p)]
    programming_stems = {re.sub(r'\s+','',q['stem']) for q in questions if q['kind']=='PROGRAMMING'}
    for q in questions:
        if re.sub(r'\s+','',q['stem']) in programming_stems:
            q['kind']='PROGRAMMING'
    seen, duplicates = {}, []
    for q in questions:
        fingerprint = hashlib.sha256((re.sub(r'\s+','',q['stem']) + json.dumps(q['options'],ensure_ascii=False)).encode()).hexdigest()
        if fingerprint in seen:
            q['duplicateOf'] = seen[fingerprint]
            duplicates.append([q['id'],seen[fingerprint]])
        else:
            seen[fingerprint] = q['id']
    if len({q['id'] for q in questions}) != len(questions):
        raise ValueError('Duplicate IDs: import each source only once')
    report = dict(total=len(questions), sources=dict(Counter(q['source'] for q in questions)),
                  kinds=dict(Counter(q['kind'] for q in questions)),
                  pending=sum(q['reviewRequired'] for q in questions), images=sum(len(q['images']) for q in questions),
                  duplicates=duplicates, notes=['原文缺少答案时不生成答案。', '难度未标注，不推测。', '重复题保留独立来源和进度。',
                  '填空归入简答；分类合集未标明单/多选时按单选展示，但无答案不自动评分。',
                  '普特编程实现分流使用人工检查后的题号集合；后续不同文档版式需调整解析器。'])
    return dict(schemaVersion=1, questions=questions), report

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files',nargs='+',type=Path)
    ap.add_argument('--out',required=True,type=Path)
    args = ap.parse_args()
    bank, report = convert(args.files)
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(bank,ensure_ascii=False,indent=2),encoding='utf-8')
    args.out.with_suffix('.report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('duplicates','notes')},ensure_ascii=True,indent=2))

if __name__ == '__main__':
    main()
