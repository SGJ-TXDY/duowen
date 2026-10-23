"""Import 严选's numbered batches and their paragraph/table answer keys in body order."""
import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from docx import Document
from docx.table import Table
from import_docx import blocks


def parse(path):
    doc=Document(path);questions=[];current=None;batch=0;section='综合基础'
    answer_mode=False;answer_index=0;lookup={}
    def finish():
        nonlocal current
        if current is None:return
        parts=current.pop('_parts');body=[];options=[]
        current['raw']='\n'.join(parts)
        for line in '\n'.join(parts).splitlines():
            match=re.match(r'^([A-H])[.．、]\s*',line)
            if match:options.append(dict(key=match[1],text=line))
            elif options:options[-1]['text']+='\n'+line
            else:body.append(line)
        current['stem']='\n'.join(body)
        current['options']=options
        if [o['key'] for o in options]!=list('ABCD'):raise ValueError('Unexpected options '+current['id'])
        questions.append(current);lookup[(batch,current['originalNumber'])]=current;current=None
    def answer(number,key,explanation,locator):
        q=lookup.get((batch,number))
        if q is None:raise ValueError('Answer without matching question')
        if q['answer']:raise ValueError('Duplicate answer '+q['id'])
        if key not in [o['key'] for o in q['options']]:raise ValueError('Invalid answer key '+q['id'])
        q.update(answer=key,correct=[key],explanation=explanation,reviewRequired=False,answerLocator=locator)
        q['locator']+='\n答案：'+locator
    for index,style,text,element in blocks(doc):
        if style=='Table':
            finish();rows=Table(element,doc).rows
            if [c.text for c in rows[0].cells]!=['题号','答案','简析']:raise ValueError('Unexpected answer table')
            for row_index,row in enumerate(rows[1:],1):
                n,key,explanation=[c.text for c in row.cells]
                answer(int(n),key.strip(),explanation,f'{path.name} / body {index} / row {row_index}')
            answer_mode=False;continue
        if not text.strip():continue
        match=re.match(r'^(\d+)\.\s+(.+)',text,re.S)
        if match:
            finish();number=int(match[1])
            if number==1:batch+=1
            if (batch,number) in lookup:raise ValueError('Repeated question number')
            category=section
            if batch==1:
                theme=re.match(r'（([^）]+)）',match[2])
                category=theme[1] if theme else section
            elif batch==3:category='数据结构与算法 / '+section
            current=dict(id=f'yanxuan-{batch:02}-{number:03}',stem='',kind='SINGLE',source='严选（原创练习）',company='未注明',
                         category=category,difficulty='未标注',options=[],answer='',correct=[],explanation='',
                         reviewRequired=True,images=[],examples='',constraints='',approach='',referenceCode='',
                         locator=f'{path.name} / 第{batch}组 / 题目{number} / body {index}',duplicateOf='',
                         originalNumber=number,_parts=[match[2]])
            answer_mode=False;continue
        if re.match(r'^[一二三四五六七八九十]+、',text):
            finish();section=re.sub(r'^[一二三四五六七八九十]+、','',text);continue
        if text.startswith('参考答案'):
            finish();answer_mode=True;answer_index=0;continue
        if answer_mode:
            match=re.fullmatch(r'([A-H])(?:[（(](.*)[）)])?',text,re.S)
            if match:
                answer_index+=1;answer(answer_index,match[1],match[2] or '',f'{path.name} / body {index}')
            continue
        if current is not None:current['_parts'].append(text)
    finish()
    if not questions or any(not q['answer'] for q in questions):raise ValueError('Missing reference answers')
    for group in range(1,batch+1):
        numbers=sorted(n for b,n in lookup if b==group)
        if numbers!=list(range(1,len(numbers)+1)):raise ValueError('Nonconsecutive question numbers')
    return questions


def append(bank,path):
    questions=parse(path)
    if {q['id'] for q in bank['questions']} & {q['id'] for q in questions}:raise ValueError('Yanxuan IDs already present')
    def fingerprint(q):return re.sub(r'\s+','',q['stem'])+json.dumps(q['options'],ensure_ascii=False)
    seen={fingerprint(q):q['id'] for q in bank['questions']};duplicates=[]
    for q in questions:
        key=fingerprint(q)
        if key in seen:q['duplicateOf']=seen[key];duplicates.append([q['id'],seen[key]])
        else:seen[key]=q['id']
    bank['questions'].extend(questions);bank['bankRevision']=4
    return bank,dict(source=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),added=len(questions),
                     batches=dict(Counter(q['id'].split('-')[1] for q in questions)),duplicates=duplicates,
                     autoGraded=len(questions),programming=0,
                     notes=['按正文顺序识别三组题号，分组匹配段落答案与表格答案。','原文声明为原创练习，独立标记来源，不作为公司真题。','保留题干、选项、代码缩进与原文参考结论；不导入对话引言、邀请或指令。'])


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('bank',type=Path);ap.add_argument('docx',type=Path);ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args();bank,report=append(json.loads(args.bank.read_text(encoding='utf-8')),args.docx)
    args.out.write_text(json.dumps(bank,ensure_ascii=False,indent=2),encoding='utf-8')
    args.out.with_suffix('.yanxuan-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=True))
