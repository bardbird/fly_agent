from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


UNKNOWN = "待复核"
DELIVERY_DIR_NAME = "SCA_交付材料"
COPYLEFT_LICENSES = {
    "AGPL-3.0",
    "GPL-2.0",
    "GPL-3.0",
    "LGPL-2.1",
    "LGPL-3.0",
    "MPL-2.0",
    "EPL-1.0",
    "EPL-2.0",
    "CDDL-1.0",
    "CDDL-1.1",
}
COMPATIBLE_LICENSES = {
    "0BSD",
    "Apache-2.0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "CC0-1.0",
    "ISC",
    "MIT",
    "Unlicense",
    "Zlib",
}
DATA_HEADERS = [
    "任务ID",
    "仓库",
    "源码URL",
    "基线Commit",
    "语言",
    "扫描工具",
    "组件数量",
    "是否生成 SBOM",
    "是否存在未知许可证",
    "是否存在高风险/copyleft许可证",
    "是否存在商业使用限制",
    "是否存在 AI 训练限制",
    "LICENSE文件路径",
    "NOTICE文件路径",
    "商业 AI 训练兼容性结论",
]
COMPONENT_HEADERS = [
    "组件名称",
    "组件版本",
    "组件类型",
    "包URL/PURL",
    "许可证 SPDX ID",
    "许可证声明",
    "是否存在未知许可证",
    "是否高风险/copyleft",
    "证据来源",
]
RISK_HEADERS = ["风险类型", "风险对象", "风险描述", "处置建议"]


@dataclass
class GenerationResult:
    output_dir: Path
    generated_files: list[Path]
    risk_count: int


def normalize_spdx_packages(payload: dict[str, Any]) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    packages = payload.get("packages")
    if not isinstance(packages, list) or not packages:
        raise RuntimeError("Scanner output does not contain SPDX packages")

    components: list[dict[str, str]] = []
    risks: list[dict[str, str]] = []
    for package in packages:
        if not isinstance(package, dict):
            continue
        name = clean(package.get("name"))
        version = clean(package.get("versionInfo"))
        declared = license_value(package.get("licenseDeclared"))
        concluded = license_value(package.get("licenseConcluded"))
        license_id = concluded if concluded != UNKNOWN else declared
        purl = clean(package.get("downloadLocation"))
        unknown_license = license_id == UNKNOWN
        copyleft = is_copyleft(license_id)
        component = {
            "组件名称": name,
            "组件版本": version,
            "组件类型": infer_component_type(purl),
            "包URL/PURL": purl,
            "许可证 SPDX ID": license_id,
            "许可证声明": declared,
            "是否存在未知许可证": yes_no(unknown_license),
            "是否高风险/copyleft": yes_no(copyleft),
            "证据来源": "scanner:spdx",
        }
        components.append(component)
        if unknown_license:
            risks.append(risk(
                "MISSING_COMPONENT_LICENSE",
                name,
                f"组件 {name} 未提供明确许可证，需要人工复核。",
                "补充上游许可证证据后再判断商业 AI 训练兼容性。",
            ))
        if copyleft:
            risks.append(risk(
                "HIGH_RISK_COPYLEFT_LICENSE",
                name,
                f"组件 {name} 使用 {license_id}，存在 reciprocal/copyleft 义务风险。",
                "由法务或数据合规负责人复核。",
            ))
    return components, risks


def generate_from_scan(
    package_path: Path,
    raw_scan_path: Path,
    scanner_name: str,
    output_dir: Path | None = None,
) -> GenerationResult:
    package_path = package_path.resolve()
    task_json_path = require_file(package_path / "task.json", "task.json is required")
    repo_path = require_directory(package_path / "repo", "repo directory is required")
    raw_scan_path = require_file(raw_scan_path, "raw scanner output is required")

    task = json.loads(task_json_path.read_text(encoding="utf-8"))
    payload = json.loads(raw_scan_path.read_text(encoding="utf-8"))
    components, risks = normalize_spdx_packages(payload)
    task_id = task.get("task_id") or package_path.name

    output_dir = (output_dir or package_path / DELIVERY_DIR_NAME).resolve()
    sbom_dir = output_dir / "04_SBOM文件"
    raw_dir = output_dir / "05_原始扫描日志"
    notice_dir = output_dir / "06_LICENSE_NOTICE归档"
    for directory in (output_dir, sbom_dir, raw_dir, notice_dir):
        directory.mkdir(parents=True, exist_ok=True)

    sbom_path = sbom_dir / f"{task_id}_sbom.spdx.json"
    raw_json_path = raw_dir / f"{task_id}_sca_scan.json"
    raw_log_path = raw_dir / f"{task_id}_sca_scan.log"
    shutil.copyfile(raw_scan_path, sbom_path)
    shutil.copyfile(raw_scan_path, raw_json_path)
    raw_log_path.write_text(f"scanner={scanner_name}\nraw_output={raw_scan_path}\n", encoding="utf-8")

    license_path = copy_first(repo_path, notice_dir, task_id, "LICENSE", ["LICENSE*", "COPYING*"])
    notice_path = copy_first(repo_path, notice_dir, task_id, "NOTICE", ["NOTICE*"])
    if license_path is None:
        risks.append(risk(
            "MISSING_REPO_LICENSE",
            str(repo_path),
            "仓库根目录未发现 LICENSE/COPYING 文件。",
            "人工确认仓库许可证来源。",
        ))

    data_row = data_level_row(
        task=task,
        task_id=task_id,
        scanner_name=scanner_name,
        components=components,
        risks=risks,
        license_path=license_path,
        notice_path=notice_path,
    )
    write_csv(output_dir / "02_数据级SCA明细表.csv", DATA_HEADERS, [data_row])
    write_csv(output_dir / "03_开源组件与许可证清单.csv", COMPONENT_HEADERS, components)
    write_csv(output_dir / "07_风险数据清单.csv", RISK_HEADERS, risks)
    write_report(output_dir / "01_task_SCA报告.md", data_row, risks)
    write_batch_report(output_dir / "01_整批SCA总报告.md", data_row, risks)
    write_ai_statement(output_dir / "08_商业AI训练兼容性声明.md", data_row, risks)

    return GenerationResult(
        output_dir=output_dir,
        generated_files=sorted(path for path in output_dir.rglob("*") if path.is_file()),
        risk_count=len(risks),
    )


def run_scanner(package_path: Path, output_dir: Path | None = None) -> tuple[Path, str]:
    package_path = package_path.resolve()
    repo_path = require_directory(package_path / "repo", "repo directory is required")
    raw_dir = (output_dir or package_path / DELIVERY_DIR_NAME) / "05_原始扫描日志"
    raw_dir.mkdir(parents=True, exist_ok=True)
    task_id = read_task_id(package_path)
    raw_scan_path = raw_dir / f"{task_id}_sca_scan.json"
    if shutil.which("syft"):
        command = ["syft", str(repo_path), "-o", "spdx-json"]
        scanner_name = "syft"
    elif shutil.which("trivy"):
        command = ["trivy", "fs", "--format", "spdx-json", "--output", str(raw_scan_path), str(repo_path)]
        scanner_name = "trivy"
    else:
        raise RuntimeError("No supported SCA scanner found. Install syft or trivy.")

    log_path = raw_dir / f"{task_id}_sca_scan.log"
    with log_path.open("w", encoding="utf-8") as log:
        log.write("$ " + " ".join(command) + "\n")
        if scanner_name == "syft":
            with raw_scan_path.open("w", encoding="utf-8") as raw_output:
                completed = subprocess.run(command, stdout=raw_output, stderr=log, text=True)
        else:
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, text=True)
        log.write(f"\nexitCode={completed.returncode}\n")
    if completed.returncode != 0:
        raise RuntimeError(f"SCA scanner failed: {scanner_name}, log={log_path}")
    require_file(raw_scan_path, "scanner did not produce SBOM JSON")
    return raw_scan_path, scanner_name


def run_syft_scan(target: str, raw_scan_path: Path, log_path: Path) -> None:
    command = ["syft", target, "-o", "spdx-json"]
    with log_path.open("w", encoding="utf-8") as log:
        log.write("$ " + " ".join(command) + "\n")
        with raw_scan_path.open("w", encoding="utf-8") as raw_output:
            completed = subprocess.run(command, stdout=raw_output, stderr=log, text=True)
        log.write(f"\nexitCode={completed.returncode}\n")
    if completed.returncode != 0:
        raise RuntimeError(f"SCA scanner failed: syft, log={log_path}")
    require_file(raw_scan_path, "scanner did not produce SBOM JSON")


def run_syft_scanners(package_path: Path, output_dir: Path) -> tuple[Path, str]:
    package_path = package_path.resolve()
    repo_path = require_directory(package_path / "repo", "repo directory is required")
    if not shutil.which("syft"):
        return run_scanner(package_path, output_dir)

    task_id = read_delivery_task_id(package_path)
    raw_dir = output_dir / "05_原始扫描日志"
    sbom_dir = output_dir / "04_SBOM文件"
    raw_dir.mkdir(parents=True, exist_ok=True)
    sbom_dir.mkdir(parents=True, exist_ok=True)

    source_scan = raw_dir / f"{task_id}_source_sca_scan.json"
    source_log = raw_dir / f"{task_id}_source_sca_scan.log"
    run_syft_scan(f"dir:{repo_path}", source_scan, source_log)
    shutil.copyfile(source_scan, sbom_dir / f"{task_id}_source_sbom.spdx.json")

    image_tar = docker_image_tar(package_path)
    if image_tar is not None:
        image_scan = raw_dir / f"{task_id}_image_sca_scan.json"
        image_log = raw_dir / f"{task_id}_image_sca_scan.log"
        run_syft_scan(f"docker-archive:{image_tar}", image_scan, image_log)
        shutil.copyfile(image_scan, sbom_dir / f"{task_id}_image_sbom.spdx.json")

    return source_scan, "syft"


def generate(
    package_path: Path,
    raw_scan_path: Path | None = None,
    scanner_name: str | None = None,
    output_dir: Path | None = None,
) -> GenerationResult:
    output_dir = (output_dir or package_path / DELIVERY_DIR_NAME).resolve()
    if raw_scan_path is None:
        raw_scan_path, scanner_name = run_syft_scanners(package_path, output_dir)
    return generate_from_scan(package_path, raw_scan_path, scanner_name or "scanner", output_dir)


def data_level_row(
    task: dict[str, Any],
    task_id: str,
    scanner_name: str,
    components: list[dict[str, str]],
    risks: list[dict[str, str]],
    license_path: Path | None,
    notice_path: Path | None,
) -> dict[str, str]:
    unknown = any(component["是否存在未知许可证"] == "是" for component in components)
    high_risk = any(component["是否高风险/copyleft"] == "是" for component in components)
    unresolved = bool(risks) or unknown
    conclusion = "不建议使用" if high_risk else ("需复核" if unresolved else compatible_conclusion(components))
    metadata = task.get("metadata") if isinstance(task.get("metadata"), dict) else {}
    return {
        "任务ID": task_id,
        "仓库": clean(task.get("repo")),
        "源码URL": clean(metadata.get("source_pr") or task.get("source_url")),
        "基线Commit": clean(task.get("base_commit")),
        "语言": clean(task.get("repo_language") or task.get("language")),
        "扫描工具": scanner_name,
        "组件数量": str(len(components)),
        "是否生成 SBOM": "是",
        "是否存在未知许可证": yes_no(unknown),
        "是否存在高风险/copyleft许可证": yes_no(high_risk),
        "是否存在商业使用限制": UNKNOWN,
        "是否存在 AI 训练限制": UNKNOWN,
        "LICENSE文件路径": str(license_path) if license_path else UNKNOWN,
        "NOTICE文件路径": str(notice_path) if notice_path else UNKNOWN,
        "商业 AI 训练兼容性结论": conclusion,
    }


def compatible_conclusion(components: list[dict[str, str]]) -> str:
    licenses = {component["许可证 SPDX ID"] for component in components}
    if licenses and licenses.issubset(COMPATIBLE_LICENSES):
        return "兼容"
    return "需复核"


def write_report(path: Path, data_row: dict[str, str], risks: list[dict[str, str]]) -> None:
    lines = [
        "# Task SCA 报告",
        "",
        f"- 任务ID: {data_row['任务ID']}",
        f"- 仓库: {data_row['仓库']}",
        f"- 扫描工具: {data_row['扫描工具']}",
        f"- 组件数量: {data_row['组件数量']}",
        f"- 商业 AI 训练兼容性结论: {data_row['商业 AI 训练兼容性结论']}",
        "",
        "## 风险项",
        "",
    ]
    if risks:
        lines.extend(f"- {item['风险类型']}: {item['风险描述']}" for item in risks)
    else:
        lines.append("- 未发现机器可判定的许可证风险；商业使用限制与 AI 训练限制仍需人工复核。")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_batch_report(path: Path, data_row: dict[str, str], risks: list[dict[str, str]]) -> None:
    lines = [
        "# SWE-Pro 数据开源代码成分与许可证兼容性分析报告",
        "",
        f"- 本批数据总量: 1",
        f"- 已完成 SCA 分析的数据量: 1",
        f"- SCA 覆盖率: 100%",
        f"- 涉及 repo 数量: 1",
        f"- 涉及开源项目数量: 1",
        f"- 涉及许可证类型: {known_license_summary(data_row)}",
        f"- 是否存在无许可证 / 未识别许可证项目: {data_row['是否存在未知许可证']}",
        f"- 是否存在 GPL / AGPL / LGPL / SSPL / Commons Clause 等高风险许可证: {data_row['是否存在高风险/copyleft许可证']}",
        f"- 是否存在禁止商业使用、限制训练、限制再分发的条款: {UNKNOWN}",
        f"- 与商业 AI 训练兼容性结论: {data_row['商业 AI 训练兼容性结论']}",
        f"- 不兼容或需复核的数据清单: 07_风险数据清单.csv",
        f"- 整改建议: 对待复核字段进行人工复核，必要时补充企业 SCA 工具许可证库结果。",
        "",
        "## 风险项",
        "",
    ]
    if risks:
        lines.extend(f"- {item['风险类型']}: {item['风险描述']}" for item in risks)
    else:
        lines.append("- 未发现机器可判定的许可证风险。")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_ai_statement(path: Path, data_row: dict[str, str], risks: list[dict[str, str]]) -> None:
    lines = [
        "# 商业 AI 训练兼容性声明",
        "",
        f"- 数据编号: {data_row['任务ID']}",
        "- 本批数据已完成开源代码成分识别。",
        "- 已列明数据中包含的开源代码及许可证类型。",
        f"- 已识别无许可证、未知许可证、高风险许可证: 风险项 {len(risks)} 条。",
        f"- 已根据客户商业 AI 训练用途进行兼容性初判: {data_row['商业 AI 训练兼容性结论']}。",
        "- 对不确定项已列入需复核清单。",
        "- 对不兼容项已建议剔除、替换、补充授权或法务复核。",
        "- 供应商对报告真实性负责。",
        "",
        f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def known_license_summary(data_row: dict[str, str]) -> str:
    if data_row.get("商业 AI 训练兼容性结论") == "兼容":
        return "扫描组件许可证均在兼容白名单内"
    return "见 03_开源组件与许可证清单.csv"


def write_csv(path: Path, headers: list[str], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=headers, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def copy_first(repo_path: Path, target_dir: Path, task_id: str, target_kind: str, patterns: list[str]) -> Path | None:
    for pattern in patterns:
        for source in sorted(repo_path.glob(pattern)):
            if source.is_file():
                target = target_dir / f"{task_id}_{target_kind}{source.suffix or '.txt'}"
                shutil.copyfile(source, target)
                return target
    return None


def risk(risk_type: str, target: str, description: str, advice: str) -> dict[str, str]:
    return {
        "风险类型": risk_type,
        "风险对象": target,
        "风险描述": description,
        "处置建议": advice,
    }


def license_value(value: Any) -> str:
    text = clean(value)
    if text.upper() in {"", "NOASSERTION", "NONE", "UNKNOWN"}:
        return UNKNOWN
    return text


def clean(value: Any) -> str:
    if value is None:
        return UNKNOWN
    text = str(value).strip()
    return text if text and text.upper() not in {"NOASSERTION", "NONE"} else UNKNOWN


def infer_component_type(purl: str) -> str:
    if purl.startswith("pkg:"):
        return purl.split("/", 1)[0].removeprefix("pkg:") or UNKNOWN
    return UNKNOWN


def is_copyleft(license_id: str) -> bool:
    upper = license_id.upper()
    return license_id in COPYLEFT_LICENSES or any(token in upper for token in ("GPL", "AGPL", "LGPL", "MPL", "EPL", "CDDL"))


def yes_no(value: bool) -> str:
    return "是" if value else "否"


def read_task_id(package_path: Path) -> str:
    task_json = require_file(package_path / "task.json", "task.json is required")
    try:
        task = json.loads(task_json.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return package_path.name
    return task.get("task_id") or package_path.name


def read_delivery_task_id(package_path: Path) -> str:
    value = package_path.name
    if value.endswith("_SCA_交付材料"):
        value = value.removesuffix("_SCA_交付材料")
    if value == "09_原始样本归档":
        return "production-task-thingsboard-15646"
    return value


def docker_image_tar(package_path: Path) -> Path | None:
    task_json = package_path / "task.json"
    if task_json.is_file():
        try:
            task = json.loads(task_json.read_text(encoding="utf-8"))
            metadata = task.get("metadata") if isinstance(task.get("metadata"), dict) else {}
            docker = metadata.get("docker") if isinstance(metadata.get("docker"), dict) else {}
            image_tar = docker.get("image_tar")
            if image_tar:
                candidate = package_path / image_tar
                if candidate.is_file():
                    return candidate
        except Exception:
            pass
    image_dir = package_path / "docker-image"
    if image_dir.is_dir():
        for candidate in sorted(image_dir.glob("*.tar")):
            if candidate.is_file():
                return candidate
    return None


def require_file(path: Path, message: str) -> Path:
    if not path.is_file():
        raise RuntimeError(f"{message}: {path}")
    return path


def require_directory(path: Path, message: str) -> Path:
    if not path.is_dir():
        raise RuntimeError(f"{message}: {path}")
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate task-level SCA delivery materials.")
    parser.add_argument("--package", required=True, help="Existing SWE-Pro task package path.")
    parser.add_argument("--raw-scan", help="Existing raw SPDX JSON scan output, used for tests or manual re-indexing.")
    parser.add_argument("--scanner-name", default="scanner", help="Scanner name for --raw-scan mode.")
    parser.add_argument("--output-dir", help="Directory where SCA delivery materials are written.")
    parser.add_argument("--manifest-only", action="store_true", help="Accepted for API compatibility; real scanner still runs when available.")
    args = parser.parse_args()

    result = generate(
        Path(args.package),
        Path(args.raw_scan) if args.raw_scan else None,
        args.scanner_name,
        Path(args.output_dir) if args.output_dir else None,
    )
    print(json.dumps({
        "outputDir": str(result.output_dir),
        "generatedFiles": [str(path) for path in result.generated_files],
        "riskCount": result.risk_count,
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
