"""只读题库质量审计：不补答案，不改原始题库。"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re

OBJECTIVE = {"SINGLE", "MULTIPLE", "TRUE_FALSE"}

def audit(bank):
    questions = bank["questions"]
    missing = [q["id"] for q in questions if q["kind"] in OBJECTIVE and not q.get("correct")]
    single = [q["id"] for q in questions if q["kind"] in OBJECTIVE and len(q.get("options", [])) < 2]
    categories = collections.defaultdict(set)
    duplicates = collections.defaultdict(list)
    for q in questions:
        category = q.get("category", "未分类")
        number = re.match(r"\s*(\d+)\s", category)
        if number:
            categories[number.group(1)].add(category.split(" / ")[0])
        normalized = re.sub(r"\s+", "", q["stem"]).casefold()
        duplicates[normalized].append(q["id"])
    repeated_ids = [key for key, n in collections.Counter(q["id"] for q in questions).items() if n > 1]
    return {
        "questionCount": len(questions),
        "missingStandardAnswer": missing,
        "fewerThanTwoOptions": single,
        "oneOption": [q["id"] for q in questions if q["kind"] in OBJECTIVE and len(q.get("options", [])) == 1],
        "zeroOptions": [q["id"] for q in questions if q["kind"] in OBJECTIVE and not q.get("options")],
        "categoryNumberConflicts": {n: sorted(names) for n, names in categories.items() if len(names) > 1},
        "suspectedDuplicateStems": [ids for ids in duplicates.values() if len(ids) > 1],
        "duplicateIds": repeated_ids,
        "notice": "仅统计；疑似重复不代表应合并，缺答案不会自动补全。",
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bank", nargs="?", default="app/src/main/assets/bank.json")
    parser.add_argument("--out")
    args = parser.parse_args()
    path = Path(args.bank)
    original = path.read_bytes()
    report = audit(json.loads(original))
    report["bankSha256"] = hashlib.sha256(original).hexdigest()
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        target = Path(args.out)
        if target.resolve() == path.resolve():
            parser.error("报告不能覆盖题库")
        target.write_text(output, encoding="utf-8")
    else:
        print(output)
    assert path.read_bytes() == original, "审计不得修改题库"

if __name__ == "__main__":
    main()
