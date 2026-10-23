"""Reproduce the delivered bank and audit code/images against the supplied DOCX files."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
from docx import Document
from docx.oxml.ns import qn
from import_docx import convert

ap=argparse.ArgumentParser()
ap.add_argument('files',nargs='+',type=Path)
ap.add_argument('--bank',type=Path,required=True)
ap.add_argument('--answers',type=Path)
ap.add_argument('--handwritten',type=Path)
ap.add_argument('--yanxuan',type=Path)
args=ap.parse_args()
rebuilt,report=convert(args.files)
if args.answers:
    from merge_answers import merge
    rebuilt,supplement=merge(rebuilt,args.answers)
    print(json.dumps(supplement,ensure_ascii=False),file=__import__('sys').stderr)
if args.handwritten:
    from import_handwritten import append
    rebuilt,handwritten=append(rebuilt,args.handwritten)
    print(json.dumps(handwritten,ensure_ascii=False),file=__import__('sys').stderr)
if args.yanxuan:
    from import_yanxuan import append
    rebuilt,yanxuan=append(rebuilt,args.yanxuan)
    print(json.dumps(yanxuan,ensure_ascii=False),file=__import__('sys').stderr)
saved=json.loads(args.bank.read_text(encoding='utf-8'))
assert rebuilt==saved, 'Delivered JSON differs from reproducible conversion'
qs=saved['questions']
code_count=0
image_hashes=[]
for path in args.files:
    doc=Document(path)
    for paragraph in doc.paragraphs:
        if paragraph.style.name=='Code' and paragraph.text:
            code_count+=1
            assert any(paragraph.text in q['answer'] for q in qs), 'Code text/indentation lost'
    for blip in doc.element.body.iter(qn('a:blip')):
        rid=blip.get(qn('r:embed'))
        if rid:
            image_hashes.append(hashlib.sha256(doc.part.related_parts[rid].blob).hexdigest())
imported_hashes=[hashlib.sha256(base64.b64decode(i['data'])).hexdigest() for q in qs for i in q['images']]
assert sorted(image_hashes)==sorted(imported_hashes), 'Image bytes or occurrences changed'
print(json.dumps(dict(total=len(qs),source_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in args.files+([args.answers] if args.answers else [])+([args.handwritten] if args.handwritten else [])+([args.yanxuan] if args.yanxuan else [])},
    code_paragraphs_preserved=code_count,images_exact=len(image_hashes),duplicate_links=len(report['duplicates']),
    handwritten_added=handwritten['added'] if args.handwritten else 0,
    yanxuan_added=yanxuan['added'] if args.yanxuan else 0,
    daily=sum(q['kind']!='PROGRAMMING' for q in qs),programming=sum(q['kind']=='PROGRAMMING' for q in qs),
    deterministic_rebuild=True),ensure_ascii=False,indent=2))
