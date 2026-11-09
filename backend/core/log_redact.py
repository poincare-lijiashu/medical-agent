"""日志出口脱敏（评测 04 P1-3）：`/admin/logs` 返回前掩码凭证与直接标识符。

为什么在**读取侧**做：日志文件本身要保留完整信息供运维排障（本地磁盘、仅运维可读），
但"把日志通过 HTTP 发给浏览器"是另一条通道——口令、令牌、API Key、身份证、手机号
不应进入响应体、浏览器内存与前端 DevTools。

规则（按顺序执行，幂等；保留键名/上下文，只替换值，仍能定位问题）：
1. 键值对：password/passwd/pwd/token/secret/api_key/apikey/authorization/cookie 等；
2. `Authorization: Bearer <token>`；
3. JWT（`eyJ... . ... . ...`）；
4. 常见厂商 Key（`sk-...` / `ak-...` / `pk-...`）；
5. 中国大陆身份证（18 位：保留前 6 位地区码与后 4 位，掩码出生日期段）；
6. 中国大陆手机号（11 位：保留前 3 后 4）。
"""
from __future__ import annotations

import re

MASK = "***"

# 键值对：`password=xxx`、`"token": "xxx"`、`api_key: xxx` 等
# `(?!bearer|basic)`：避免把 `Authorization: Bearer <token>` 的方案词当值吃掉（先由 _BEARER 处理）
_KV = re.compile(
    r"(?i)\b(password|passwd|pwd|token|secret|api[_-]?key|apikey|authorization|cookie"
    r"|access_token|refresh_token|client_secret)(\"?\s*[:=]\s*\"?)(?!bearer\b|basic\b)"
    r"([^\s,;\"'&}\]]+)")

_BEARER = re.compile(r"(?i)\b(bearer\s+)[A-Za-z0-9._\-+/=]{8,}")
_JWT = re.compile(r"\beyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\b")
_VENDOR_KEY = re.compile(r"\b(sk|ak|pk)-[A-Za-z0-9]{8,}\b")
_ID_CARD = re.compile(r"(?<!\d)(\d{6})(\d{8})(\d{3}[\dXx])(?!\d)")
_PHONE = re.compile(r"(?<!\d)(1[3-9]\d)\d{4}(\d{4})(?!\d)")


def redact_line(line: str) -> str:
    """单行脱敏（幂等：对已脱敏文本再跑一遍结果不变）。"""
    if not line:
        return line
    out = _BEARER.sub(lambda m: f"{m.group(1)}{MASK}", line)
    out = _KV.sub(lambda m: f"{m.group(1)}{m.group(2)}{MASK}", out)
    out = _JWT.sub(MASK, out)
    out = _VENDOR_KEY.sub(lambda m: f"{m.group(1)}-{MASK}", out)
    out = _ID_CARD.sub(lambda m: f"{m.group(1)}********{m.group(3)}", out)
    out = _PHONE.sub(lambda m: f"{m.group(1)}****{m.group(2)}", out)
    return out


def redact_lines(lines) -> list[str]:
    """批量脱敏（保持行序与行数，前端行号不变）。"""
    return [redact_line(x) for x in lines]
