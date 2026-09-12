"""HIS 适配器抽象基类与空实现（integration 层的「端口」）。

HisAdapter 定义医院信息系统（HIS/EMR）对接的最小接口面——核心业务（api 层）只依赖
本抽象与 backend.integration.registry.get_adapter() 工厂，不感知任何厂商实现
（依赖倒置：高层定义接口，低层实现并注册）。新增厂商适配器步骤见 docs/archive/INTEGRATION.md。

对接前置条件（接口惯例占位，落地以院方接口规范为准）：
- 网络可达：院内 HIS 视图库 / WebService / REST 网关，账号经院方开通（最小只读权限）；
- 标识映射：patient_id 需约定类型（门诊号 / 住院号 / 患者主索引 EMPI），record_id 同理；
- 推送幂等：push_qc_result 以 record_id 幂等（重复推送不得产生重复缺陷单）；
- 安全合规：凭据经环境变量 / secret 注入（禁止硬编码入库）；临床数据出院界需院方审批。
"""
from __future__ import annotations

from abc import ABC, abstractmethod


class HisAdapter(ABC):
    """医院 HIS/EMR 适配器抽象基类（integration 层唯一对接端口）。"""

    @abstractmethod
    def fetch_patient(self, patient_id: str) -> dict | None:
        """按患者标识拉取患者摘要（姓名/性别/年龄/过敏史等，字段映射由适配器完成）。

        预期语义：patient_id 为双方约定的患者主索引；查无此人返回 None（不抛业务异常）；
        网络/权限故障由适配器自行重试后抛异常，由调用方全兜底（审计后吞掉，不影响主流程）。
        对接前置条件：院方开通只读查询账号，明确 patient_id 类型与返回字段映射。
        """

    @abstractmethod
    def push_qc_result(self, record_id: str, result: dict) -> bool:
        """把质控结论推送回 HIS/病案系统（record_id=病历标识，result=结论 payload）。

        预期语义：幂等推送；成功返回 True；适配器侧明确拒收/跳过返回 False（不抛异常）；
        网络/权限异常直接抛出——调用方全兜底并审计 his.push_failed，绝不影响质控主流程。
        对接前置条件：院方提供回写接口规范（触发时机 / 字段映射 / 重试与幂等约定）。
        """

    @abstractmethod
    def fetch_orders(self, patient_id: str) -> list:
        """按患者标识拉取医嘱/处方清单（供用药核查与质控关联）。

        预期语义：无医嘱或查无此人均返回 []（空列表不抛异常）；故障同上抛出由调用方兜底。
        对接前置条件：院方明确医嘱视图范围（在院 / 历史）与出界脱敏要求。
        """


class NullAdapter(HisAdapter):
    """空实现（settings.his_adapter="none"，默认）：未对接 HIS 时的优雅降级。

    全部方法安全空转（查询返回空值、推送返回 False）——调用方据此静默跳过，
    核心业务无需任何「是否已对接」的判断分支（零感知）。
    """

    def fetch_patient(self, patient_id: str) -> dict | None:
        return None

    def push_qc_result(self, record_id: str, result: dict) -> bool:
        return False

    def fetch_orders(self, patient_id: str) -> list:
        return []
