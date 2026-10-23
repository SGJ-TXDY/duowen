import copy
import unittest
from audit_bank_quality import audit
from build_category_map import normalize

class QualityTest(unittest.TestCase):
    def test_audit_does_not_change_input_and_reports_conflicts(self):
        bank={"questions":[{"id":"one","stem":"相同题干","kind":"SINGLE","category":"1 C语言","options":[{"key":"A"}]},
                           {"id":"two","stem":"相同 题干","kind":"SINGLE","category":"1 Linux","options":[]}]}
        original=copy.deepcopy(bank)
        report=audit(bank)
        self.assertEqual(bank,original)
        self.assertEqual(report["missingStandardAnswer"],["one","two"])
        self.assertEqual(report["suspectedDuplicateStems"],[["one","two"]])
        self.assertIn("1",report["categoryNumberConflicts"])
    def test_display_mapping_removes_conflicting_numbers(self):
        self.assertEqual(normalize("1 C语言基础与内存管理"),"C语言与内存")
        self.assertEqual(normalize("5 C语言进阶 / 指针与运算符"),"C语言与内存")
        self.assertEqual(normalize("未标注"),"未标注")
