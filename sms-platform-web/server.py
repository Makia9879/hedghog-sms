"""本机 Web。页面负责下发，区分和保存都在这个进程里完成。"""

from __future__ import annotations

import json
import os
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from parser import parse_export_text
from store import Store

ROOT = Path(__file__).resolve().parent
DATA = Path(os.environ.get("SMS_PLATFORM_DATA", ROOT / "data"))
STATIC = ROOT / "static"
STORE = Store(DATA)
MAX_UPLOAD = 80 * 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    server_version = "sms-platform-web"

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/health":
            self._json({"ok": True})
            return
        if parsed.path == "/api/summary":
            self._json(STORE.summary())
            return
        if parsed.path == "/api/messages":
            query = parse_qs(parsed.query)
            self._json(
                {
                    "messages": STORE.messages(
                        _one(query, "slot"),
                        _one(query, "platform"),
                        _one(query, "status"),
                    )
                }
            )
            return
        if parsed.path in ("/", "/index.html"):
            self._file(STATIC / "index.html", "text/html; charset=utf-8")
            return
        self._json({"error": "没有这个地址"}, 404)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length > MAX_UPLOAD:
            self._json({"error": "文件太大"}, 413)
            return
        body = self.rfile.read(length) if length else b""
        try:
            if parsed.path == "/api/import":
                messages = _messages_from_upload(body, self.headers.get("Content-Type", ""))
                if not messages:
                    self._json({"error": "没有解析到短信。请上传手机导出的 zip 或 txt。"}, 400)
                    return
                result = STORE.import_messages(messages)
                result["parsed"] = len(messages)
                self._json(result)
                return
            if parsed.path == "/api/classify":
                self._json(STORE.classify_all())
                return
            if parsed.path == "/api/label":
                payload = json.loads(body.decode("utf-8"))
                ids = [int(item) for item in payload.get("ids", [])]
                name = str(payload.get("platformName", ""))
                if not ids or not name.strip():
                    self._json({"error": "要选择短信，并填写平台名称。"}, 400)
                    return
                self._json({"updated": STORE.label_messages(ids, name)})
                return
        except (ValueError, json.JSONDecodeError, zipfile.BadZipFile) as error:
            self._json({"error": str(error) or "请求无法处理"}, 400)
            return
        self._json({"error": "没有这个地址"}, 404)

    def log_message(self, fmt: str, *args) -> None:
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)

    def _json(self, payload: dict, status: int = 200) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _file(self, path: Path, content_type: str) -> None:
        if not path.is_file():
            self._json({"error": "页面不存在"}, 404)
            return
        raw = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def _messages_from_upload(body: bytes, content_type: str) -> list:
    messages = []
    for filename, content in _files(body, content_type):
        if filename.lower().endswith(".zip"):
            with zipfile.ZipFile(BytesIO(content)) as archive:
                for info in archive.infolist():
                    if info.is_dir() or not info.filename.lower().endswith(".txt"):
                        continue
                    text = archive.read(info).decode("utf-8")
                    messages.extend(parse_export_text(text))
        elif filename.lower().endswith(".txt"):
            messages.extend(parse_export_text(content.decode("utf-8")))
    return messages


def _files(body: bytes, content_type: str) -> list[tuple[str, bytes]]:
    if "multipart/form-data" not in content_type:
        raise ValueError("请用表单上传 zip 或 txt")
    marker = "boundary="
    boundary = content_type.split(marker, 1)[1].strip().strip('"')
    delimiter = ("--" + boundary).encode("utf-8")
    files: list[tuple[str, bytes]] = []
    for part in body.split(delimiter)[1:]:
        if part.startswith(b"--") or not part.strip(b"\r\n"):
            continue
        chunk = part[2:] if part.startswith(b"\r\n") else part
        header_blob, _, content = chunk.partition(b"\r\n\r\n")
        if content.endswith(b"\r\n"):
            content = content[:-2]
        filename = ""
        for line in header_blob.decode("utf-8", "replace").split("\r\n"):
            if "filename=" in line:
                filename = line.split("filename=", 1)[1].strip().strip('"')
        if filename:
            files.append((Path(filename).name, content))
    return files


def _one(query: dict[str, list[str]], key: str) -> str | None:
    values = query.get(key) or []
    value = values[0].strip() if values else ""
    return value or None


def main() -> None:
    host = os.environ.get("SMS_PLATFORM_HOST", "127.0.0.1")
    port = int(os.environ.get("SMS_PLATFORM_PORT", "8790"))
    httpd = ThreadingHTTPServer((host, port), Handler)
    print(f"sms-platform-web listening on {host}:{port}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
