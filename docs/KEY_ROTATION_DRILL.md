# W3.5 密钥轮换演练记录（2026-10-30）

> 演练环境：本地全栈 docker（medagent_pg / medagent_milvus）+ FastAPI 后端（127.0.0.1:8001，
> W3.1 安全形态：httpOnly cookie）。复跑入口：`python scripts/drill_key_rotation.py llm|db`。
> 纪律：演练全程使用**假 api_key 与一次性专用 role**，绝不触碰真实凭据与 medagent 主 role；
> 演练后环境清理（演练 provider 删除、演练 role 删除）由脚本自动完成并断言。

## 演练结论（三类凭据全覆盖）

| 凭据 | 演练方式 | 结果 | 零中断语义 |
|---|---|---|---|
| JWT secret | 双 secret 过渡窗口（DEPLOY.md 操作章节）+ `tests/test_jwt_rotation.py` 零踢出 5 项 | ✅ 已演练（2026-10-30，提交 28ba311） | 旧令牌自然过期前仍可验签，在线用户不踢出 |
| LLM api_key | 管理 API 全流程（本日演练，见下） | ✅ PASS（8 步全过） | 激活切换即时生效（`_reset_llm_cache`），无需重启 |
| DB 密码 | 专用 role 四象限连接验证（本日演练，见下） | ✅ PASS（A-E 全过） | 运行中实例连接池零中断；重启/新实例须用新 DSN |

## 一、LLM api_key 轮换演练（drill llm）

步骤与观察：

1. **登录**：cookie 会话建立——响应体无 access_token（W3.1 安全形态：`AUTH_TOKEN_IN_BODY=false`
   下 body 恒空，httpOnly cookie `medassist_at/rt` 下发），演练脚本以 CookieJar 携带会话。
   顺带验证了"页面 JS 无法读取令牌"的设计。
2. **记录当前 active**（`p-eca795bb`，deepseek-v4-flash；另有 glm-5.3-flash provider）。
3. **新增演练 provider**（假 key `sk-drill-FAKE-…`，base_url/model 沿用现网值）→ 返回新 id。
4. **假 key 连通测试**：`POST /admin/llm/test` → `ok=false`，厂商返回 401 且**key 已打码**
   （`****RILL is invalid`）——错误处理路径可用、无完整 key 回显。
5. **激活演练 provider**：`/config` 的 `chat_model` 即时反映新 provider 的 model_id——
   切换不重启（`_reset_llm_cache` 清 LLMFactory 实例缓存）。
6. **激活回原 provider**：`/config` 回原值，零中断回切。
7. **删除演练 provider** → 8. **终态核对**：providers 列表还原、active 不变。

SOP（生产）：管理端新增 provider（新 key）→ 连通测试 ok → 激活 → 观察业务 → 删旧。
回滚路径：重新激活旧 provider 即可（元数据未删时秒级回切）。

## 二、DB 密码轮换演练（drill db）

设计：专用一次性 role `drill_rot_w35` 在**独立测试库 medagent_test**（127.0.0.1:5433）上
演练，超户操作经 `docker exec psql`——生产 medagent role 与服务库全程零接触。

演练矩阵与结果：

| 步骤 | 操作 | 预期 | 结果 |
|---|---|---|---|
| A | CREATE ROLE（旧密码）→ 新连接 | 成功 | ✅ |
| B | `ALTER ROLE … PASSWORD` → **同一连接**继续 `SELECT 1` | 成功 | ✅ **运行中长连接零中断** |
| C | 新连接用旧密码 | 拒绝 | ✅ |
| D | 新连接用新密码 | 成功 | ✅ |
| E | REVOKE + DROP ROLE + 确认删除 | 清理干净 | ✅ |

**结论与 SOP**：
- `ALTER USER … PASSWORD` **不影响已建立的连接**（asyncpg 池长连接语义）——运行中服务
  在密码轮换瞬间不中断；
- 但**重启/新实例/池重连必须用新密码**——轮换 SOP：
  1. `ALTER USER medagent WITH PASSWORD '<新值>'`（旧连接不受影响，服务继续可用）；
  2. `.env` 更新 `PG_PASSWORD`/`DATABASE_URL`（新值）；
  3. **滚动重启**实例（一次一个），重启后实例用新密码建池；
  4. 全部实例重启完成前**不得回收旧密码有效性**（PG 无双密码窗口——这是与 JWT/LLM 轮换
     的关键差异，靠滚动重启窗口保证连续性）。
- 回滚路径：轮换后发现问题 → ALTER 回旧密码 + .env 回滚 + 再滚动重启。

## 三、遗留提示

- 上述演练覆盖机制与流程；生产执行时把假凭据换成真实新凭据即可，步骤不变。
- DB 轮换的"双密码窗口缺失"是 PostgreSQL 固有限制：多实例滚动重启期间新旧 DSN 并存，
  依赖第 4 步的顺序纪律。
- JWT secret 轮换的完整操作与生产校验（previous 弱密钥拒绝）见 docs/DEPLOY.md 同名章节。
