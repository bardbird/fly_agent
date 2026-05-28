from __future__ import annotations

import json
import tempfile
import unittest
import sys
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate_task_sca as sca


class GenerateTaskScaTest(unittest.TestCase):
    def test_normalizes_syft_spdx_packages_and_marks_unknown_license_risks(self) -> None:
        payload = {
            "spdxVersion": "SPDX-2.3",
            "packages": [
                {
                    "name": "root-project",
                    "SPDXID": "SPDXRef-DocumentRoot-Directory",
                    "versionInfo": "待复核",
                    "licenseConcluded": "MIT",
                    "licenseDeclared": "MIT",
                    "downloadLocation": "NOASSERTION",
                },
                {
                    "name": "left-pad",
                    "SPDXID": "SPDXRef-Package-npm-left-pad",
                    "versionInfo": "1.3.0",
                    "licenseConcluded": "NOASSERTION",
                    "licenseDeclared": "NOASSERTION",
                    "downloadLocation": "pkg:npm/left-pad@1.3.0",
                },
            ],
        }

        components, risks = sca.normalize_spdx_packages(payload)

        self.assertEqual(2, len(components))
        self.assertEqual("left-pad", components[1]["组件名称"])
        self.assertEqual("1.3.0", components[1]["组件版本"])
        self.assertEqual("待复核", components[1]["许可证 SPDX ID"])
        self.assertEqual("是", components[1]["是否存在未知许可证"])
        self.assertEqual("MISSING_COMPONENT_LICENSE", risks[0]["风险类型"])
        self.assertIn("left-pad", risks[0]["风险描述"])

    def test_generates_delivery_directory_from_existing_scan_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-1"
            repo = package / "repo"
            repo.mkdir(parents=True)
            (package / "task.json").write_text(json.dumps({
                "task_id": "production-task-demo-1",
                "repo": "acme/demo",
                "repo_language": "javascript",
                "base_commit": "abc123",
                "metadata": {"source_pr": "https://github.com/acme/demo/pull/7"},
            }), encoding="utf-8")
            (repo / "LICENSE").write_text("MIT License\n", encoding="utf-8")
            (repo / "NOTICE").write_text("Demo notice\n", encoding="utf-8")
            scan_output = {
                "spdxVersion": "SPDX-2.3",
                "packages": [
                    {
                        "name": "left-pad",
                        "versionInfo": "1.3.0",
                        "licenseConcluded": "MIT",
                        "licenseDeclared": "MIT",
                        "downloadLocation": "pkg:npm/left-pad@1.3.0",
                    }
                ],
            }
            raw_scan = package / "scan.spdx.json"
            raw_scan.write_text(json.dumps(scan_output), encoding="utf-8")

            result = sca.generate_from_scan(package, raw_scan, scanner_name="syft")

            self.assertEqual((package / "SCA_交付材料").resolve(), result.output_dir)
            self.assertTrue((result.output_dir / "01_task_SCA报告.md").is_file())
            self.assertTrue((result.output_dir / "02_数据级SCA明细表.csv").is_file())
            self.assertTrue((result.output_dir / "03_开源组件与许可证清单.csv").is_file())
            self.assertTrue((result.output_dir / "04_SBOM文件" / "production-task-demo-1_sbom.spdx.json").is_file())
            self.assertTrue((result.output_dir / "05_原始扫描日志" / "production-task-demo-1_sca_scan.json").is_file())
            self.assertTrue((result.output_dir / "06_LICENSE_NOTICE归档" / "production-task-demo-1_LICENSE.txt").is_file())
            self.assertTrue((result.output_dir / "06_LICENSE_NOTICE归档" / "production-task-demo-1_NOTICE.txt").is_file())
            self.assertTrue((result.output_dir / "07_风险数据清单.csv").is_file())
            report = (result.output_dir / "01_task_SCA报告.md").read_text(encoding="utf-8")
            self.assertIn("production-task-demo-1", report)
            self.assertIn("acme/demo", report)

    def test_fails_when_no_supported_scanner_is_available(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-1"
            (package / "repo").mkdir(parents=True)
            (package / "task.json").write_text("{}", encoding="utf-8")

            with patch("shutil.which", return_value=None):
                with self.assertRaisesRegex(RuntimeError, "No supported SCA scanner"):
                    sca.run_scanner(package)


if __name__ == "__main__":
    unittest.main()
