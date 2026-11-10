"""评测「HIS 深度集成」第三步：HL7 v2 MLLP 客户端与适配器。

两层：
1. MLLP 协议（真 TCP 回环服务器）：帧边界 `\\x0b … \\x1c\\x0d`、ACK 解析、超时、无帧报错；
2. 适配器（mock `send_message`）：QBP^Q22 患者映射、AE→None、QRY^R02 医嘱解析、
   ORU^R01 回写 AA/AE/无 ACK、缺配置响亮失败、registry 注册。
"""
from __future__ import annotations

import socket
import threading
import time

import pytest

from backend.config import settings
from backend.integration import mllp
from backend.integration.hl7v2_adapter import (Hl7v2HisAdapter, build_qc_oru,
                                               hl7_date_to_iso, parse_orders,
                                               parse_patient)
from backend.integration.registry import get_adapter


def _ack(code: str, text: str = "") -> str:
    return f"MSH|^~\\&|HIS|HOSP|MEDASSIST|AI|20261110120000||ACK^Q22^ACK|1|P|2.5\rMSA|{code}|1|{text}\r"


@pytest.fixture()
def mllp_server():
    """本地 TCP 服务器：读一帧 → 回一帧 ACK；记录收到的报文。"""
    received: dict = {}
    state = {"reply": _ack("AA"), "raw": None, "delay": 0.0}

    def serve(conn):
        buf = b""
        while mllp.MLLP_END not in buf:
            chunk = conn.recv(4096)
            if not chunk:
                break
            buf += chunk
        start, end = buf.find(mllp.MLLP_START), buf.find(mllp.MLLP_END)
        received["text"] = buf[start + 1:end].decode("utf-8") if start >= 0 else ""
        if state["delay"]:
            time.sleep(state["delay"])
        payload = state["raw"] if state["raw"] is not None else (
            mllp.MLLP_START + state["reply"].encode() + mllp.MLLP_END)
        try:
            conn.sendall(payload)
        except OSError:
            pass
        conn.close()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(5)
    port = srv.getsockname()[1]

    def loop():
        while True:
            try:
                conn, _ = srv.accept()
            except OSError:
                return
            threading.Thread(target=serve, args=(conn,), daemon=True).start()

    threading.Thread(target=loop, daemon=True).start()
    yield port, received, state
    srv.close()


def test_mllp_framing_and_ack(mllp_server):
    port, received, _ = mllp_server
    msg = mllp.build_message([mllp.build_msh("QBP^Q22^QBP_Q21", "C1")])
    resp = mllp.send_message("127.0.0.1", port, msg, timeout_s=3)
    assert received["text"].startswith("MSH|^~\\&|MEDASSIST|AI|")
    assert mllp.parse_ack(resp) == ("AA", "")


def test_mllp_timeout_when_no_reply(mllp_server):
    port, _received, state = mllp_server
    state["delay"] = 1.5
    with pytest.raises(mllp.MllpTimeout):
        mllp.send_message("127.0.0.1", port, "MSH|^~\\&\r", timeout_s=0.3)


def test_mllp_rejects_unframed_response(mllp_server):
    port, _received, state = mllp_server
    state["raw"] = b"MSA|AA|1\r"
    with pytest.raises(mllp.MllpError):
        mllp.send_message("127.0.0.1", port, "MSH|^~\\&\r", timeout_s=2)


def test_parse_ack_variants():
    assert mllp.parse_ack("MSA|AA|1|ok\r") == ("AA", "ok")
    assert mllp.parse_ack("MSA|AE|1|字段非法\r")[0] == "AE"
    assert mllp.parse_ack("PID|1\r") == ("", "")


def test_missing_host_fails_loudly(monkeypatch):
    monkeypatch.setattr(settings, "hl7_mllp_host", "")
    with pytest.raises(ValueError) as ei:
        Hl7v2HisAdapter()
    assert "HL7_MLLP_HOST" in str(ei.value)


# ---------- 适配器（mock 传输层，专注映射与 ACK 语义） ----------

def _adapter() -> Hl7v2HisAdapter:
    return Hl7v2HisAdapter(host="127.0.0.1", port=2575, timeout_s=1)


def test_fetch_patient_maps_pid(monkeypatch):
    rsp = "MSH|^~\\&|HIS|HOSP|AI|AI|20261110||RSP^K22^RSP_K21|1|P|2.5\r" \
          "MSA|AA|1\rPID|1||P001||李^四||19800305|F\r"
    monkeypatch.setattr("backend.integration.mllp.send_message", lambda *a, **k: rsp)
    p = _adapter().fetch_patient("P001")
    assert p["patient_id"] == "P001" and p["name"] == "李四"
    assert p["sex"] == "女" and p["birth_date"] == "1980-03-05" and p["age"] is not None


def test_fetch_patient_ack_reject_is_none(monkeypatch):
    monkeypatch.setattr("backend.integration.mllp.send_message", lambda *a, **k: _ack("AE", "no such"))
    assert _adapter().fetch_patient("P404") is None


def test_fetch_orders_parses_orc_obr(monkeypatch):
    rsp = "MSH|^~\\&|HIS|HOSP|AI|AI|20261110||RSP^R02^RSP_R02|1|P|2.5\r" \
          "MSA|AA|1\rORC|NW|O1|||IP\rOBR|1|O1||ASP^阿司匹林肠溶片|||20261109\r" \
          "ORC|NW|O2|||CM\rOBR|2|O2||CTX^头孢曲松|||\r"
    monkeypatch.setattr("backend.integration.mllp.send_message", lambda *a, **k: rsp)
    orders = _adapter().fetch_orders("P001")
    assert [o["order_id"] for o in orders] == ["O1", "O2"]
    assert orders[0]["name"] == "阿司匹林肠溶片" and orders[0]["authored_at"] == "2026-11-09"
    assert orders[1]["status"] == "CM"


def test_push_qc_result_three_states(monkeypatch):
    monkeypatch.setattr("backend.integration.mllp.send_message", lambda *a, **k: _ack("AA"))
    assert _adapter().push_qc_result("rev-1", {"status": "approved"}) is True

    monkeypatch.setattr("backend.integration.mllp.send_message", lambda *a, **k: _ack("AR", "reject"))
    assert _adapter().push_qc_result("rev-1", {"status": "approved"}) is False

    monkeypatch.setattr("backend.integration.mllp.send_message", lambda *a, **k: "MSH|^~\\&\r")
    with pytest.raises(mllp.MllpError):
        _adapter().push_qc_result("rev-1", {"status": "approved"})


def test_build_qc_oru_escapes_and_carries_json():
    msg = build_qc_oru("rev|9", {"status": "rejected", "note": "a|b"}, "C9")
    assert "ORU^R01^ORU_R01" in msg
    assert "OBR|1|rev\\F\\9||QCRESULT^质控结论" in msg
    assert '"note": "a\\F\\b"' in msg  # JSON 里的 | 也转义，避免破坏段结构


def test_hl7_date_and_order_helpers():
    assert hl7_date_to_iso("19800305") == "1980-03-05"
    assert hl7_date_to_iso("bad") == ""
    assert parse_orders("ORC|NW|O9|||IP\rOBR|9|O9||X^Y\r")[0]["name"] == "Y"
    assert parse_patient(["PID", "1", "", "P2", "", "王^五", "", "", "M"])["sex"] == "男"


def test_registry_exposes_hl7v2(monkeypatch):
    monkeypatch.setattr(settings, "his_adapter", "hl7v2")
    monkeypatch.setattr(settings, "hl7_mllp_host", "127.0.0.1")
    assert isinstance(get_adapter(), Hl7v2HisAdapter)

