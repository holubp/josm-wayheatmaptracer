import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET

import legacy_tracker_jfr_privacy as privacy


class PrivacyTest(unittest.TestCase):
    def test_controls_cannot_enable_private_events(self):
        profile = """<configuration><event name="jdk.InitialEnvironmentVariable">
        <setting name="enabled" control="override">true</setting></event>
        <event name="jdk.ExecutionSample"><setting name="enabled" control="override">true</setting>
        <setting name="period" control="interval">10 ms</setting></event>
        <event name="vendor.Other"><setting name="enabled">true</setting></event>
        <control><flag name="override" default="true"/></control></configuration>"""
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "profile.jfc"
            output = pathlib.Path(directory) / "safe.jfc"
            source.write_text(profile)
            privacy.generate(source, output)
            root = ET.parse(output).getroot()
            events = {e.get("name"): e for e in root.findall("event")}
            for name in privacy.FORBIDDEN | {"vendor.Other"}:
                self.assertEqual("false", events[name].find("setting").text)
            self.assertEqual("true", events["jdk.ExecutionSample"].find("setting").text)
            self.assertFalse(root.findall("control"))
            self.assertTrue(all("control" not in s.attrib for s in root.iter("setting")))

    def test_summary_rejects_private_and_unknown_recorded_events(self):
        base = "".join(f"{name} 0 0\n" for name in sorted(privacy.FORBIDDEN))
        rows, unsafe = privacy.check_summary(base + "jdk.ExecutionSample 5 120\njdk.Metadata 1 20\n")
        self.assertEqual([], unsafe)
        self.assertEqual(5, rows["jdk.ExecutionSample"][0])
        _, unsafe = privacy.check_summary(base.replace("jdk.SystemProcess 0 0", "jdk.SystemProcess 1 5")
                                          + "vendor.Secret 1 3\n")
        self.assertEqual(["jdk.SystemProcess", "vendor.Secret"], unsafe)
        with self.assertRaises(ValueError):
            privacy.check_summary("jdk.ExecutionSample 1 3\n")


if __name__ == "__main__":
    unittest.main()
