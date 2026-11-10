"""MLLP（HL7 v2 over TCP）最小客户端与报文工具（评测：HIS 深度集成老院区通道）。

为什么自研而不引第三方库：MLLP 的帧格式只有 3 个控制字符（起始 `\\x0b`、结束 `\\x1c\\x0d`），
协议本身极简；引入 `hl7apy` 只为解析几段文本不划算，且老院区网关对字符集/换行更敏感，
自己实现可以精确控制编码、超时与 ACK 语义。

边界（务必知情）：
- 只做**单次请求-响应**（不维护长连接池）；MLLP 网关通常要求短连接 + 逐条 ACK，
  长连接需要心跳/重连策略，属后续优化；
- ACK 语义：`MSA|AA` 接受、`AE/AR` 拒绝（返回给调用方判断，不自动重试）；
- 报文分隔符固定 `\\r`（HL7 v2 标准），字段分隔 `|`，编码字符 `^~\\&`。
"""
from __future__ import annotations

import socket
import time

MLLP_START = b"\x0b"
MLLP_END = b"\x1c\x0d"
_ENC_CHARS = "^~\\&"


class MllpError(RuntimeError):
    """MLLP 传输/协议错误（调用方转 502/503 并审计）。"""


class MllpTimeout(MllpError):
    """连接或等待 ACK 超时。"""


def now_ts() -> str:
    """HL7 时间戳：YYYYMMDDHHMMSS（本地时区，院内系统口径一致）。"""
    return time.strftime("%Y%m%d%H%M%S")


def esc(value: str) -> str:
    """HL7 字段转义（先反斜杠，避免二次转义）：\\ → \\E\\，| → \\F\\，^ → \\S\\，~ → \\R\\，& → \\T\\。"""
    s = str(value if value is not None else "")
    s = s.replace("\\", "\\E\\")
    for ch, code in (("|", "F"), ("^", "S"), ("~", "R"), ("&", "T")):
        s = s.replace(ch, f"\\{code}\\")
    return s.replace("\r", " ").replace("\n", " ")


def build_message(segments: list[list[str]]) -> str:
    """段列表 → HL7 文本（段间 `\\r`，末尾也带 `\\r`；字段由调用方已转义）。"""
    return "\r".join("|".join(seg) for seg in segments) + "\r"


def build_msh(msg_type: str, control_id: str, sending_app: str = "MEDASSIST",
              sending_facility: str = "AI", receiving_app: str = "HIS",
              receiving_facility: str = "HOSP", version: str = "2.5") -> list[str]:
    """MSH 段（MSH-1=|、MSH-2=^~\\& 为固定编码字符）。"""
    return ["MSH", _ENC_CHARS, sending_app, sending_facility, receiving_app, receiving_facility,
            now_ts(), "", msg_type, control_id, "P", version]


def parse_ack(response: str) -> tuple[str, str]:
    """从 ACK/响应报文中取 (MSA 状态码, 文本)；无 MSA 返回 ("", "")。"""
    for seg in (response or "").split("\r"):
        if seg.startswith("MSA|") or seg.startswith("MSA"):
            parts = seg.split("|")
            code = parts[1] if len(parts) > 1 else ""
            text = parts[3] if len(parts) > 3 else (parts[2] if len(parts) > 2 else "")
            return code.strip(), text.strip()
    return "", ""


def send_message(host: str, port: int, message: str, timeout_s: float = 5.0,
                 encoding: str = "utf-8") -> str:
    """发送一条 HL7 报文并返回完整响应（含 MSA 段）。超时/连不上抛 MllpTimeout/MllpError。"""
    if not (host or "").strip():
        raise MllpError("未配置 HL7 MLLP 主机（HL7_MLLP_HOST）")
    payload = MLLP_START + message.encode(encoding, errors="replace") + MLLP_END
    deadline = time.monotonic() + max(0.1, float(timeout_s))
    try:
        with socket.create_connection((host.strip(), int(port)), timeout=max(0.1, float(timeout_s))) as sock:
            sock.sendall(payload)
            buf = b""
            while True:
                left = deadline - time.monotonic()
                if left <= 0:
                    raise MllpTimeout(f"MLLP 等待 ACK 超时（>{timeout_s:g}s）")
                sock.settimeout(min(0.5, left))
                try:
                    chunk = sock.recv(4096)
                except socket.timeout:
                    continue
                if not chunk:
                    break
                buf += chunk
                if MLLP_END in buf:
                    break
    except MllpTimeout:
        raise
    except (OSError, socket.timeout) as e:  # 连不上/被重置
        raise MllpTimeout(f"MLLP 连接不可达：{e}") from e
    start = buf.find(MLLP_START)
    end = buf.find(MLLP_END)
    if start < 0 or end < 0 or end <= start:
        raise MllpError("MLLP 响应缺少帧边界（网关未按 MLLP 返回）")
    return buf[start + 1:end].decode(encoding, errors="replace")
