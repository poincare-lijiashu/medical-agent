"""演示用 HIS 适配器（内存假数据）——展示适配器如何落地，绝无真实外呼。

⚠️ 仅供集成开发参考（演示/联调/测试 + 厂商适配器代码模板）：
生产环境严禁使用（his_adapter 保持默认 "none"，对接真实 HIS 时注册真实厂商适配器）。
内存假数据（张三/李四等）非真实患者，严禁流入生产数据链路。

用途：settings.his_adapter="demo" 时本地演示 / 联调 / 测试 qc→HIS 推送链路与审计语义
（his.pushed），同时作为 docs/INTEGRATION.md「新增厂商适配器」的代码模板。
数据全部进程内存态（类级存储跨实例共享），重启即失；pushed()/reset() 供测试检查点。
"""
from __future__ import annotations

import threading
from datetime import datetime, timezone

from backend.integration.base import HisAdapter

_PATIENTS: dict[str, dict] = {
    "P001": {"patient_id": "P001", "name": "张三", "sex": "男", "age": 62,
             "dept": "心内科", "allergies": ["青霉素"],
             "diagnosis": "2 型糖尿病；高血压 3 级"},
    "P002": {"patient_id": "P002", "name": "李四", "sex": "女", "age": 45,
             "dept": "内分泌科", "allergies": [], "diagnosis": "甲状腺结节随访"},
}
_ORDERS: dict[str, list[dict]] = {
    "P001": [
        {"order_id": "O-1001", "drug": "二甲双胍 0.5g", "route": "口服",
         "freq": "tid", "status": "在执行"},
        {"order_id": "O-1002", "drug": "硝苯地平控释片 30mg", "route": "口服",
         "freq": "qd", "status": "在执行"},
    ],
    "P002": [],
}


class DemoHisAdapter(HisAdapter):
    """内存假数据实现：线程安全（推送记录上锁），无任何真实网络调用。"""

    _pushed: list[dict] = []
    _lock = threading.Lock()

    # ---- 测试/演示检查点 ----

    @classmethod
    def reset(cls) -> None:
        """清空推送记录（测试隔离用）。"""
        with cls._lock:
            cls._pushed = []

    @classmethod
    def pushed(cls) -> list[dict]:
        """已推送的质控结论快照（新→旧由调用方自行排序；每项含 record_id/result/ts）。"""
        with cls._lock:
            return list(cls._pushed)

    # ---- HisAdapter 接口实现 ----

    def fetch_patient(self, patient_id: str) -> dict | None:
        return _PATIENTS.get(str(patient_id or "").strip())

    def push_qc_result(self, record_id: str, result: dict) -> bool:
        rid = str(record_id or "").strip()
        if not rid:
            return False  # 无病历标识：适配器侧明确拒收（返回 False，不抛异常）
        with self._lock:
            self._pushed.append({"record_id": rid, "result": dict(result or {}),
                                 "ts": datetime.now(timezone.utc).isoformat(),
                                 "adapter": type(self).__name__})
        return True

    def fetch_orders(self, patient_id: str) -> list:
        return list(_ORDERS.get(str(patient_id or "").strip(), []))
