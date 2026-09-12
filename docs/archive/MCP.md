# MedAssist MCP Server 使用说明

`scripts/mcp_server.py` 是一个 **stdio MCP Server**（Model Context Protocol），把 MedAssist 的医疗辅助能力暴露给任意 MCP 客户端（Claude Desktop、其他 AI 助手等）。单文件、**零第三方依赖**（仅 Python 标准库），通过 HTTP 调用 MedAssist 后端 API。

> ⚠️ 定位提醒：MedAssist 是辅助决策工具，**非医疗器械**。所有输出须经执业医师/药师复核。

## 协议面

- 传输：stdio（逐行 JSON-RPC 2.0），`protocolVersion: 2024-11-05`
- 方法：`initialize`、`notifications/initialized`（通知）、`tools/list`、`tools/call`
- 工具执行失败（含上游 401/429/网络错误）以 `isError=true` 的工具结果返回，LLM 可见并可自我纠正；未知工具等协议层错误返回 JSON-RPC error（-32601/-32602）
- 日志全部走 stderr，stdout 仅输出协议消息

## 安全边界（批2 决策，重要）

- **MCP 调用不写 MedAssist 复核队列**：答案正文/置信度/来源照常返回，但**不产生复核单**（`review_id` 为空）——外部 AI 客户端批量调用不会污染院内审核流。免责声明写在各工具 docstring/description：**结果由外部 AI 客户端消费自担，接入方须自行安排执业医师/药师复核**。
- **审计留痕照常**：每个工具调用的上游请求恒带 `X-MedAssist-Channel: mcp` 与 `X-MedAssist-Tool: <工具名>` 两个头；后端审计事件 payload 自动带 `channel="mcp"` 与 `tool="<工具名>"` 字段——与 web 前端流量区分、可回溯到具体工具（普通 HTTP 请求 payload 不含这两个字段）。
- `review_resolve`（签发/驳回）是显式人工动作，照常受双控约束（审核人 ≠ 提交人）；建议为 MCP 接入创建专用账号（角色按需），便于审计区分与随时吊销。

## 工具清单（12 个）

### 生成类（AI 作答；均不入复核队列）

| 工具 | 入参 | 上游端点 | 返回 |
|---|---|---|---|
| `literature_ask` | `question`（必填） | POST `/api/v1/medical/literature/ask` | 循证答案正文 + 置信度/需人工复核/来源摘要 |
| `literature_query` | `question`（必填）、`session_id`（可选 ≤64 字符，同会话多轮记忆，可理解指代） | POST `/api/v1/medical/literature/ask` | 同上（session_id 透传服务端会话记忆） |
| `drug_check` | `question`（必填，含药名） | POST `/api/v1/medical/drug/ask` | 相互作用/禁忌核查正文 + 元信息摘要 |
| `imaging_describe` | `question`（必填）、`images_b64`（可选 ≤6 张，兼容旧名 `images`；base64/data URL，单张 ≤6MB） | POST `/api/v1/medical/imaging/ask` | 结构化初步所见 + 元信息摘要 |
| `case_summarize` | 同 `imaging_describe` | POST `/api/v1/medical/case/ask` | 结构化病例摘要 + 鉴别提示 |
| `mdt_consult` | `case_text`（必填） | POST `/api/v1/medical/mdt/consult` | 三专科意见/分歧/紧急度（headline/urgency 前置）+ 元信息摘要 |
| `qc_parse` | `record`（必填 ≤20 项，键 ≤40/值 ≤2000 字符）、`labs`（可选 ≤30 项） | POST `/api/v1/medical/qc/record` | 质控报告 + 结构化缺陷计数（`qc_defects` 明细）；医师签名/科室绑定 MCP 服务账号 |
| `consult_create` | `question`（必填 ≥8 字，过短被 prefilter 拒绝）、`images_b64`（可选 ≤6 张） | POST `/api/v1/medical/consults` | 会诊单号/组队科室与理由/AI 分科初步意见（**真实流转**：分发通知目标科室在职医生，不回显图片） |

### 检索类（无 LLM 生成）

| 工具 | 入参 | 上游端点 | 返回 |
|---|---|---|---|
| `kb_search` | `query`（必填 ≤2000 字符）、`top_k`（可选 1~10，默认 3） | POST `/api/v1/medical/kb/search` | **JSON 文本**：`{query, top_k, total, chunks:[{content, source, score}]}`——混合检索+精排的命中片段，供外部 AI 自行推理做 RAG；不产生 AI 结论 |

### 审核中心类（只读/显式人工动作）

| 工具 | 入参 | 上游端点 | 返回 |
|---|---|---|---|
| `review_pending` | 无 | GET `/api/v1/medical/review/pending` | 待双人核对列表（单号/提交人/风险原因/答案摘要） |
| `review_status` | `review_id`（可选 ≤64 字符） | GET `/api/v1/medical/review/status` | 不带 → 三态计数；带 → 单条状态元数据（**剥离临床内容**）；未找到 → 错误文本 |
| `review_resolve` | `rid`、`decision`（`approved`/`rejected`）、`note`（可选） | POST `/api/v1/medical/review/{rid}/resolve` | 处理结果；双控：审核人不能是提交人本人 |

文本类工具输出格式：`answer`（或 MDT 的 `report`）正文 + 元信息摘要（置信度 / 需人工复核 / 来源 / 复核单号——MCP 调用无复核单号）。

## 启动

前置：MedAssist 后端已运行（默认 `http://127.0.0.1:8001`，见 README「快速开始」）。

```powershell
python scripts\mcp_server.py
```

直接运行时进程会在 stdin EOF 退出——正常用法是**由 MCP 客户端作为子进程拉起**（见下文配置示例）。

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MEDASSIST_URL` | `http://127.0.0.1:8001` | MedAssist 服务地址 |
| `MEDASSIST_TOKEN` | （空） | Bearer 令牌（向后兼容）；未设置或无效时工具调用返回 401 提示 |
| `MCP_TOKEN` | （空） | MCP 场景专用令牌：**设置时优先于** `MEDASSIST_TOKEN` 携带，便于与其它用途令牌分离管理 |

### 如何获取令牌

1. **个人令牌**：登录 MedAssist（前端或 `POST /api/v1/auth/login`，body `{"username","password"}`），响应中的 `access_token` 即为令牌（有过期时间，过期需重新登录，或用 `refresh_token` 调 `/api/v1/auth/refresh` 续签）。
2. **专用服务账号（推荐）**：用 admin 账号 `POST /api/v1/admin/users` 创建独立账号（如 `svc_mcp`，角色 `doctor`；使用 `qc_parse` 需设置科室），仅用于 MCP 接入，便于审计区分与随时吊销。

```powershell
# 示例：登录换取令牌
curl.exe -s -X POST http://127.0.0.1:8001/api/v1/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"doctor01\",\"password\":\"Med@2026\"}"
```

## Claude Desktop 配置示例

编辑 `%APPDATA%\Claude\claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "medassist": {
      "command": "python",
      "args": [
        "D:\\workspace_AI\\workspace_traecode\\xiangmu_traecode\\medical_agent\\scripts\\mcp_server.py"
      ],
      "env": {
        "MEDASSIST_URL": "http://127.0.0.1:8001",
        "MCP_TOKEN": "<access_token>"
      }
    }
  }
}
```

重启 Claude Desktop 后即可对话使用，例如：「用 literature_query 查一下高血压 3 级的诊断标准」「用 kb_search 检索血脂管理要点给我原文」「用 qc_parse 质控这份病历」。

其他支持 stdio MCP 的客户端同理：`command` 为 Python 解释器，`args` 为本脚本路径，`env` 注入上述环境变量。

## 错误行为

| 场景 | 工具返回（isError=true 文本） |
|---|---|
| HTTP 401 | 「认证失败：请设置 MEDASSIST_TOKEN（…）」 |
| HTTP 429 | 「请求过于频繁（HTTP 429 限流），请稍后再试」 |
| 网络不通/超时 | 「无法连接 MedAssist 服务（地址）：…请确认后端已启动…」（HTTP 超时 120s，LLM 问答较慢属正常） |
| 参数校验失败 | 明确指出问题（decision 枚举、图片 >6 张、单张 >6MB、question 过长、record >20 项等），**不发上游请求** |

## 测试

```powershell
python -m pytest tests\test_mcp_server.py tests\test_mcp_backend.py -v
```

测试不起 stdio 子进程、不发真实 HTTP：通过 importlib 从路径加载模块，monkeypatch 上游调用函数，覆盖工具→端点映射、校验边界、错误分支与协议面；后端侧覆盖 channel/tool 审计标记与「MCP 不写审核队列」语义。

## 安全提示

- 令牌经环境变量注入，不要写进代码或提交到仓库。
- 所有请求继承 MedAssist 后端的 PHI 脱敏、审计与限流；MCP 端不做二次存储。
- 建议为 MCP 接入创建专用账号并在不用时吊销。
- MCP 结果不入复核队列：接入方须自行建立人工复核环节后再用于临床。
