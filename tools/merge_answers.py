"""Match the supplied answer supplement by original question number; retain all other questions."""
import argparse, json, re, hashlib
from pathlib import Path
from docx import Document

def merge(bank, path):
    records={}; current=None; section=None
    for p in Document(path).paragraphs:
        m=re.match(r'^题目(\d{3})\s+(.+)',p.text)
        if m:
            current='company-'+m[1]
            if current in records: raise ValueError('Repeated question '+current)
            records[current]={'title':m[2],'answer':[],'explanation':[],'body':[]}
            section='body'
        elif p.style.name.startswith('Heading'):
            current=None
        elif current:
            if p.style.name=='Answer' and p.text.startswith('答案'):
                section='answer'; records[current][section].append(p.text[2:].strip())
            elif p.style.name=='Answer' and p.text.startswith('解析'):
                section='explanation'; records[current][section].append(p.text[2:].strip())
            else: records[current][section].append(p.text)
    lookup={q['id']:q for q in bank['questions']}
    report={'matched':0,'autoGraded':0,'pending':[],'source':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    for id,r in records.items():
        if id not in lookup: raise ValueError('Unmatched question '+id)
        q=lookup[id]
        # Require both original number and source title. Never match solely on an array index.
        if r['title'] not in q['locator'].split('\n')[0]: raise ValueError('Source mismatch '+id)
        normalize=lambda text: re.sub(r'\s+','',text)
        if normalize('\n'.join(r['body'])) != normalize(q['raw']):
            raise ValueError('Question body/options mismatch '+id)
        q['answer']='\n'.join(r['answer']).strip()
        q['explanation']='\n'.join(r['explanation']).strip()
        if not q['answer'] or not q['explanation']: raise ValueError('Missing answer or explanation '+id)
        q['answerSource']=path.name
        q['locator']+='\n答案来源：'+path.name+' / 题目'+id.split('-')[1]
        plain=re.fullmatch(r'[A-H](?:[\s、,，]+[A-H])*',q['answer'])
        keys=re.findall('[A-H]',q['answer']) if plain else []
        reliable=bool(keys) and len(q['options'])>=2 and set(keys)<=set(o['key'] for o in q['options']) and q['kind']!='PROGRAMMING'
        q['correct']=keys if reliable else []
        q['reviewRequired']=not reliable
        if reliable:q['kind']='MULTIPLE' if len(keys)>1 or q['kind']=='MULTIPLE' else 'SINGLE'
        else:report['pending'].append(id)
        report['matched']+=1;report['autoGraded']+=int(reliable)
    report['total']=len(bank['questions'])
    bank['bankRevision']=2
    return bank,report

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('bank',type=Path);ap.add_argument('answers',type=Path);ap.add_argument('--out',type=Path,required=True)
    a=ap.parse_args();bank,report=merge(json.loads(a.bank.read_text(encoding='utf-8')),a.answers)
    a.out.write_text(json.dumps(bank,ensure_ascii=False,indent=2),encoding='utf-8')
    a.out.with_suffix('.answers-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=True))
