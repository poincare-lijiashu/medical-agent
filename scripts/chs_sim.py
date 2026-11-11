#!/usr/bin/env python
"""国家医保 2.0（CHS）联调模拟器（仅本机/测试环境）。

用途：在没有省局前置机的情况下，验证 HIS 发出的报文是否符合 CHS 信封口径，
以及成功/失败回执下本地交易状态是否正确。

启动：
    python scripts/chs_sim.py --port 9099
    python scripts/chs_sim.py --port 9099 --fail "参保状态异常"

配合 .env.local：
    CHS_ENABLED=true
    CHS_BASE_URL=http://127.0.0.1:9099
    CHS_ALLOWED_HOSTS=127.0.0.1
    CHS_SECRET=chs-secret
    CHS_INFNO_SETTLE=2201
    CHS_PATH_SETTLE=/chs/settle

安全边界：仅监听 127.0.0.1；不校验签名真伪（模拟器不是验签服务），
只检查 sign 字段是否存在，避免把"模拟通过"误当成"签名算法已验证"。
"""
from __future__ import annotations

import argparse
import json
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REQUIRED = ("infno", "msgid", "fixmedins_code", "input")


def _reply(handler: BaseHTTPRequestHandler, status: int, body: dict) -> None:
    raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(raw)))
    handler.end_headers()
    handler.wfile.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description="CHS 2.0 联调模拟器")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9099)
    parser.add_argument("--fail", default="", help="配置后所有请求返回该失败原因")
    args = parser.parse_args()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length)
            try:
                body = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                _reply(self, 400, {"ret_code": "-1", "ret_msg": "报文不是合法 JSON"})
                return

            missing = [key for key in REQUIRED if not body.get(key)]
            if missing:
                _reply(self, 200, {
                    "ret_code": "-1",
                    "ret_msg": "CHS 信封缺少字段：" + ",".join(missing),
                })
                return
            if "sign" not in body:
                _reply(self, 200, {"ret_code": "-1", "ret_msg": "缺少 sign 字段"})
                return
            if args.fail:
                _reply(self, 200, {"ret_code": "-1", "ret_msg": args.fail})
                return

            setl_id = "SIM-" + uuid.uuid4().hex[:12].upper()
            print(f"[CHS-SIM] infno={body['infno']} msgid={body['msgid']} "
                  f"fixmedins={body['fixmedins_code']} setl_id={setl_id}", flush=True)
            _reply(self, 200, {
                "ret_code": "0",
                "ret_msg": "成功",
                "output": {"setl_id": setl_id},
            })

        def log_message(self, fmt: str, *values: object) -> None:
            print("[CHS-SIM] " + fmt % values, flush=True)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"CHS 模拟器已启动：http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
