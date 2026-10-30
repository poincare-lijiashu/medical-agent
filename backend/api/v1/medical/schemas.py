"""医疗 API 共享 schema（W4.3 router 拆分）：AskReq/AskResp 与公共常量。

多 agent 共用的请求/响应模型集中于此（literature/drug/imaging/case/consults 复用）；
域内专属 schema 留在各域模块（prescriptions/qc/kb/admin…）。
"""
from __future__ import annotations

from pydantic import BaseModel, Field, field_validator

MAX_IMAGES = 10  # 轮 A2：图片上限 6→10（与 medical_imaging.describe_images 上限一致）

# 审计 payload 中问题文本的截断长度（外部审查 L2 常量化）：各端点 prefilter/offtopic/
# query/stream 等 q 字段统一取前 AUDIT_Q_SNIPPET 字符——此前 literature prefilter/
# stream 主审计/offtopic_reject 分别为 60/200/60 的硬编码混用，现全部统一走本常量。
AUDIT_Q_SNIPPET = 200


class AskReq(BaseModel):
    question: str = Field(max_length=20000)
    image: str | None = Field(default=None, max_length=8_000_000)
    # 轮 A2：张数上限收口到 _cap_images 校验器（Field max_length 会先于校验器拦截，
    # 中文 422 文案拿不到）；校验器先查张数再逐张压缩，超大列表无压缩开销
    images: list[str] | None = Field(default=None)  # 多图（优先于 image），单张 ≤6MB（超限自动压缩）
    # 任务3 会话记忆：前端每个对话会话固定一个 uuid（「新建对话」时更换）；
    # 可选——评估脚本（run_eval）与旧客户端不携带 → 无记忆、行为与旧版一致。
    session_id: str | None = Field(default=None, max_length=64)

    @field_validator("images")
    @classmethod
    def _cap_images(cls, v: list[str] | None) -> list[str] | None:
        # 轮 A2「压缩替代拒绝」：入口统一 normalize（长边>2000 缩放 + JPEG 质量递减
        # 重编码至 ≤6MB），压缩后仍 >6MB 才 422；非图/损坏原样返回，交由下游既有丢弃逻辑。
        if v is not None:
            if len(v) > MAX_IMAGES:
                raise ValueError(f"影像图片最多 {MAX_IMAGES} 张，当前 {len(v)} 张")
            from backend.core.img_utils import enforce_data_url, normalize_data_url
            out = []
            for s in v:
                enforce_data_url(s)  # SSRF 收口（终评 F1）：非 data:image/ → 422（拒绝而非改写）
                s2 = normalize_data_url(s)
                if len(s2) > 8_000_000:
                    raise ValueError("单张影像压缩后仍超过 6MB，请压缩后上传")
                out.append(s2)
            return out
        return v


class AskResp(BaseModel):
    answer: str
    confidence: float
    needs_human_review: bool
    sources: list = []
    review_id: str | None = None
    status: str = "ok"
    evidence: list = []
    # agentic 检索改写轨迹 [{attempt,action,old_query,new_query,reason}]：
    # 仅文献 agent 非空（refine 节点逐轮累积），其余 agent 恒为 []（前端据此选择性渲染）
    refine_trace: list = []
    # 任务1：质控结构化缺陷 [{category,level,issue,field,track}]（category 已归一六枚举），
    # 仅 /qc/record 填充，供前端按维度分组渲染与快速过滤；其它 agent 恒为 []
    qc_defects: list = []
