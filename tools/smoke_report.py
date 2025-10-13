#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTDIR = ROOT / "build" / "reports" / "smoke"
QUALITY_DIR = OUTDIR / "quality"
KTLINT_TXT = QUALITY_DIR / "ktlint.txt"
DETEKT_XML = QUALITY_DIR / "detekt.xml"


def load_file(path: Path) -> str:
    try:
        return path.read_text()
    except FileNotFoundError:
        return ""


def parse_ktlint(txt: str) -> int:
    # crude: count non-empty lines as violations (fits console report)
    return sum(1 for line in txt.splitlines() if line.strip())


def parse_detekt(xml_text: str) -> tuple[int, int]:
    # returns (issues, weighted_issues); keep simple xml scanning
    if not xml_text.strip():
        return 0, 0
    # detekt XML has <Smell> entries; quick and robust count
    issues = xml_text.count("<Smell ")
    # Weighted issues may not be explicit; leave 0 if not detectable
    weighted = 0
    m = re.search(r'weightedIssues="(\d+)"', xml_text)
    if m:
        weighted = int(m.group(1))
    return issues, weighted


def build_report() -> str:
    lines: list[str] = []
    lines.append("# Smoke report")
    lines.append("")
    lines.append("## Summary")
    lines.append("- Smoke summary not available.")
    lines.append("")

    # Quality section
    lines.append("## Quality (non-blocking lint)")
    ktlint_txt = load_file(KTLINT_TXT)
    detekt_xml = load_file(DETEKT_XML)
    if ktlint_txt or detekt_xml:
        if ktlint_txt:
            ktlint_violations = parse_ktlint(ktlint_txt)
            lines.append(f"- ktlint: {ktlint_violations} issue(s). See `build/reports/smoke/quality/ktlint.txt`")
        else:
            lines.append("- ktlint: _no report_")
        if detekt_xml:
            detekt_issues, detekt_weighted = parse_detekt(detekt_xml)
            extra = f", weighted={detekt_weighted}" if detekt_weighted else ""
            lines.append(f"- detekt: {detekt_issues} issue(s){extra}. See `build/reports/smoke/quality/detekt.xml`")
        else:
            lines.append("- detekt: _no report_")
    else:
        lines.append("- _lint not executed or no reports found_")
    lines.append("")

    lines.append("## Changed files…")
    lines.append("- Change tracking not available.")
    lines.append("")

    return "\n".join(lines)


def main() -> None:
    OUTDIR.mkdir(parents=True, exist_ok=True)
    report_path = OUTDIR / "smoke_report.md"
    report_text = build_report()
    report_path.write_text(report_text)
    print(report_text)


if __name__ == "__main__":
    main()
