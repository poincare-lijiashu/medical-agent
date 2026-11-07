"""本地告警接收器（只读日志 sink，第 52 批）。

用途：验证 Alertmanager → Webhook 的送达链路。只把告警**摘要**打印到 stdout
（alertname/status），不落库、不转发、不含任何业务/患者数据。

安全：仅监听 compose 内网 8080，不映射宿主机端口；生产不得使用本进程。
"""
from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class _Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802 —— http.server 约定
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        raw = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        try:
            payload = json.loads(raw)
            names = ",".join(
                str(a.get("labels", {}).get("alertname", "?"))
                for a in (payload.get("alerts") or []))
            status = str(payload.get("status") or "")
        except Exception:  # noqa: BLE001 —— sink 绝不能因坏包崩溃
            names, status = "unparsed", ""
        print(f"ALERT_WEBHOOK status={status} alerts={names}", flush=True)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args) -> None:  # 静默默认访问日志，避免刷屏
        return


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), _Handler).serve_forever()
