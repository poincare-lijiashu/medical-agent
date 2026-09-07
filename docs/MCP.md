# MedAssist MCP Server 使用说明

`scripts/mcp_server.py` 是一个 **stdio MCP Server**（Model Context Protocol），把 MedAssist 的医疗辅助能力暴露给任意 MCP 客户端（Claude Desktop、其他 AI 助手等）。单文件、**零第三方依赖**（仅 Python 标准库），通过 HTTP 调用 MedAssist 后端 API。

> ⚠️ 定位提醒：MedAssist 是辅助决策工具，**非医疗器械**。所有输出须经执业医师/药师复核。

## 协议面

- 传输：stdio（逐行 JSON-RPC 2.0），`protocolVersion: 2024-11-05`
- 方法：`initialize`、`notifications/initialized`（通知）、`tools/list`、`tools/call`
- 工具执行失败（含上游 401/429/网络错误）以 `isError=true` 的工具结果返回，LLM 可见并可自我纠正；未知工具等协议层错误返回 JSON-RPC error（-32601/-32602）
- 日志全部走 stderr，stdout 仅输出协议消息

## 工具清单

| 工具 | 上游端点 | 说明 |
|---|---|---|
| `literature_ask(question)` | POST `/api/v1/medical/literature/ask` | 文献循证问答（PubMed+本地知识库，带引用与置信度） |
| `drug_check(question)` | POST `/api/v1/medical/drug/ask` | 药物相互作用/禁忌核查 |
| `imaging_describe(question, images[≤6])` | POST `/api/v1/medical/imaging/ask` | 影像辅助阅片（base64/data URL，≤6 张，单张 ≤6MB） |
| `case_summarize(question, images)` | POST `/api/v1/medical/case/ask` | 多模态病例总结与鉴别提示 |
| `mdt_consult(case_text)` | POST `/api/v1/medical/mdt/consult` | MDT 三专科会诊（意见/分歧/紧急度） |
| `review_pending()` | GET `/api/v1/medical/review/pending` | 查看高危「双人核对」待办队列 |
| `review_resolve(rid, decision, note)` | POST `/api/v1/medical/review/{rid}/resolve` | 签发/驳回（decision 仅 `approved`/`rejected`；双控：审核人不能是提交人） |

文本输出格式：`answer`（或 MDT 的 `report`）正文 + 元信息摘要（置信度 / 需人工复核 / 来源 / 复核单号），便于 LLM 客户端直接引用。

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
| `MEDASSIST_TOKEN` | （空） | Bearer 令牌；未设置或无效时工具调用返回 401 提示 |

### 如何获取 MEDASSIST_TOKEN

1. **个人令牌**：登录 MedAssist（前端或 `POST /api/v1/auth/login`，body `{"username","password"}`），响应中的 `access_token` 即为令牌（有过期时间，过期需重新登录，或用 `refresh_token` 调 `/api/v1/auth/refresh` 续签）。
2. **专用服务账号（推荐）**：用 admin 账号 `POST /api/v1/admin/users` 创建独立账号（如 `svc_mcp`，角色 `doctor`），仅用于 MCP 接入，便于审计区分与随时吊销。

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
      "command": "D:\\software_work\\conda_envs\\edu_agent\\python.exe",
      "args": [
        "D:\\workspace_AI\\workspace_traecode\\xiangmu_traecode\\medical_agent\\scripts\\mcp_server.py"
      ],
      "env": {
        "MEDASSIST_URL": "http://127.0.0.1:8001",
        "MEDASSIST_TOKEN": "<access_token>"
      }
    }
  }
}
```

重启 Claude Desktop 后即可对话使用，例如：「用 literature_ask 查一下高血压 3 级的诊断标准」「用 review_pending 看看待核对队列」。

其他支持 stdio MCP 的客户端同理：`command` 为 Python 解释器，`args` 为本脚本路径，`env` 注入上述两个环境变量。

## 错误行为

| 场景 | 工具返回（isError=true 文本） |
|---|---|
| HTTP 401 | 「认证失败：请设置 MEDASSIST_TOKEN（…）」 |
| HTTP 429 | 「请求过于频繁（HTTP 429 限流），请稍后再试」 |
| 网络不通/超时 | 「无法连接 MedAssist 服务（地址）：…请确认后端已启动…」（HTTP 超时 120s，LLM 问答较慢属正常） |
| 参数校验失败 | 明确指出问题（如 decision 枚举、图片 >6 张、question 过长） |

## 测试

```powershell
python -m pytest tests\test_mcp_server.py -v
```

测试不起 stdio 子进程、不发真实 HTTP：通过 importlib 从路径加载模块，monkeypatch 上游调用函数，覆盖工具→端点映射、错误分支与协议面。

## 安全提示

- 令牌经环境变量注入，不要写进代码或提交到仓库。
- 所有请求继承 MedAssist 后端的 PHI 脱敏、审计与限流；MCP 端不做二次存储。
- 建议为 MCP 接入创建专用账号并在不用时吊销。
