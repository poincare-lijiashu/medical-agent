# 轮 A/B 执行计划（2026-09-11 拍板待执行）

## 轮 A · 三小项（预计 1 轮子代理）

### A1 内置库删除修复（根因已定位）
- **根因**：`kb_ingest.delete_doc` 对 BUILTIN_TAGS 直接 raise ValueError；kb_delete 端点未捕获 → FastAPI 纯文本 500 → 前端 JSON 解析报 "Unexpected token 'I'"。
- **修复**：
  1. `delete_doc` 移除内置 raise（kb_delete 端点本就 require_role("admin")，admin 可删）；
  2. 内置删除记审计 `kb.builtin_removed`（tag/名称，与普通 kb.delete 区分）；
  3. 端点加 try/except 兜底 → 任何异常返回 JSONResponse({"detail"})，永不裸文本；
  4. 前端删除确认弹窗对内置库文案改「内置库可删除，重跑种子脚本可恢复」。
- **测试**：admin 删内置库 200+计数+审计；非 admin 403（依赖已有）；异常路径返回 JSON。

### A2 图片统一规格 ≤10 张/单张 ≤6MB 自动压缩（全入口）
- **前端** `compressImage(file)`：Canvas 长边 ≤2000px + JPEG 质量 0.9→0.5 递减至 ≤6MB；imaging 上传入口接入；计数上限 6→10。
- **后端** `img_utils.normalize_data_url()`：PIL 长边缩放+质量递减重编码；**压缩替代拒绝**（压缩后仍 >6MB 才 422）；AskReq images 上限 10；imaging/case 入口接入（MDT 轮 B 接入同一函数）。
- **测试**：压缩单测（大图→≤6MB、小图原样）；10 张过/11 张 422；伪大图压缩后放行。

### A3 数据加固
- `scripts/backup.py`：pg_dump（容器 exec）+ tar data/（排除可重建的 kb/pubmed_v2_cache.json，注明）+ 保留最近 7 份轮转 data/backups/。
- `runtime_flags` 入 PG：pg_store 加表（key/value jsonb）+ 双写（JSON 兜底保留），接口不变。
- DEPLOY.md「数据归属表」：数据/真源/镜像/备份/恢复五列（9 个 PG 双写真源 + Milvus + JSON-only 清单）。
- **测试**：runtime_flags PG 往返（FakeConn 模式）；backup 脚本轮转逻辑轻测。

## 轮 B · 两中项（预计 1-2 轮子代理）

### B1 MDT 传图（双语义）
- **真人看图**：consults create 接受 images（≤10，走 normalize）存会诊单；参与者（会诊收件箱/详情）渲染缩略图+点击放大——**参与者直接看到原图**。
- **AI 看图**：mdt.py 有图时各专科 brief 自动切 VL 模型（get_vl_llm，image_url+病例文本同 prompt），意见引用影像所见；无图维持文本 LLM。
- 审计 images_count；图片校验复用 A2 统一函数。
- **测试**：带图会诊→VL 被调用（mock 断言）+无图→文本 LLM；consults 存图；参与者 GET 返回 images；≤10+压缩链路。

### B2 Redis 可选双后端 + 三级部署（"撞日"部分）
- **Store 抽象**：core/stores.py 定义接口（限流计数/会话存取/撤销时间戳）——`MemoryStore` 默认零依赖；`RedisStore`（REDIS_URL 非空时启用，redis 异步客户端 try-import）。接入三处：security_rate / chat_memory / deps._revoked_before。
- **降级纪律**：RedisStore 每操作 try/except 失败回落内存（可用性优先，审计 warn redis.degraded）。
- config：`redis_url: str = ""`（空=现状零改变）；requirements.txt redis 注释行（可选）。
- docker-compose：redis 服务注释块（默认不启）。
- DEPLOY.md **三级部署指南**：科室级单机（现状）→ 院区级多实例+REDIS_URL+多 worker → 集团级（MQ 削峰+自建 vLLM 推理池+多机房——**留接口不实现**：LLM provider 抽象即切换点，文档写激活步骤）。
- **测试**：MemoryStore 行为锁；REDIS_URL 配置切换选择 RedisStore（mock 客户端）；Redis 异常降级内存；既有测试零回归（默认内存路径不变）。

## 顺序与验收
- 轮 A → 轮 B 串行；每轮独立提交（A: fix(kb)+img+backup；B: feat(mdt-img)+feat(redis)）。
- 轮 A 结束重启服务验证删除内置库；轮 B 结束浏览器实测会诊传图（doctor01 发起带图会诊→neike01 收件箱看图）。
- 每轮 pytest 全绿基线 606 起步。
