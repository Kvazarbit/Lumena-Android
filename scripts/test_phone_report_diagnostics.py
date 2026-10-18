import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("diagnostics", Path(__file__).with_name("phone_report_diagnostics.py"))
diagnostics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostics)


class DiagnosticCollectorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for suite in (diagnostics.CORE, diagnostics.UI):
            xml = ET.Element("testsuite")
            for s, name in sorted(set(diagnostics.CANDIDATES) | diagnostics.CONTROLS):
                if s == suite:
                    ET.SubElement(xml, "testcase", name=name, classname=suite)
            ET.ElementTree(xml).write(self.root / f"TEST-{suite}.xml")

    def modify(self, key, tag, **attributes):
        path = self.root / f"TEST-{key[0]}.xml"
        tree = ET.parse(path)
        case = next(c for c in tree.iter("testcase") if c.get("name") == key[1])
        ET.SubElement(case, tag, **attributes)
        tree.write(path)

    def test_passing_candidate_means_not_reproduced_not_confirmed_bug(self):
        checks = diagnostics.classify(self.root)
        self.assertEqual(len(diagnostics.CANDIDATES), sum(c["verdict"] == "NOT_REPRODUCED" for c in checks))

    def test_named_assertion_is_reproduction(self):
        key, marker = next(iter(diagnostics.CANDIDATES.items()))
        self.modify(key, "failure", type="java.lang.AssertionError", message=marker)
        self.assertEqual(1, sum(c["verdict"] == "REPRODUCED" for c in diagnostics.classify(self.root)))

    def test_infrastructure_failure_is_never_reproduction(self):
        key = next(iter(diagnostics.CANDIDATES))
        self.modify(key, "failure", type="java.lang.UnsatisfiedLinkError", message="missing JNI")
        with self.assertRaises(RuntimeError):
            diagnostics.classify(self.root)

    def test_wrong_assertion_is_never_reproduction(self):
        key = next(iter(diagnostics.CANDIDATES))
        self.modify(key, "failure", type="java.lang.AssertionError", message="unrelated error")
        with self.assertRaises(RuntimeError):
            diagnostics.classify(self.root)

    def test_failed_positive_control_invalidates_diagnosis(self):
        self.modify(next(iter(diagnostics.CONTROLS)), "failure", type="java.lang.AssertionError", message="bad")
        with self.assertRaises(RuntimeError):
            diagnostics.classify(self.root)

    def test_skipped_and_missing_reports_invalidate_diagnosis(self):
        self.modify(next(iter(diagnostics.CANDIDATES)), "skipped")
        with self.assertRaises(RuntimeError):
            diagnostics.classify(self.root)
        (self.root / f"TEST-{diagnostics.UI}.xml").unlink()
        with self.assertRaises(RuntimeError):
            diagnostics.classify(self.root)


if __name__ == "__main__":
    unittest.main()
