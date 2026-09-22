"""解析手机导出的 TXT。格式与短信导出包里的批次文件一致。"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class ParsedMessage:
    slot: str
    sender: str
    received_at: str
    direction: str
    body: str


def parse_export_text(text: str) -> list[ParsedMessage]:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    if not normalized.startswith("卡槽:"):
        return []
    header, _, rest = normalized.partition("\n----\n")
    slot = _header_value(header, "卡槽") or "未知卡槽"
    if rest.startswith("----\n"):
        rest = rest[5:]
    messages: list[ParsedMessage] = []
    for block in rest.split("\n----\n"):
        if "内容:\n" not in block and not block.endswith("内容:"):
            continue
        if block.endswith("\n"):
            block = block[:-1]
        fields, _, body = block.partition("内容:\n")
        if block.endswith("内容:") and not body:
            body = ""
        messages.append(
            ParsedMessage(
                slot=slot,
                sender=_field(fields, "对方") or "未知",
                received_at=_field(fields, "时间") or "",
                direction=_field(fields, "方向") or "",
                body=body,
            )
        )
    return messages


def _header_value(header: str, name: str) -> str:
    prefix = name + ":"
    for line in header.splitlines():
        if line.startswith(prefix):
            return line[len(prefix) :].strip()
    return ""


def _field(text: str, name: str) -> str:
    prefix = name + ": "
    for line in text.splitlines():
        if line.startswith(prefix):
            return line[len(prefix) :].strip()
    return ""
