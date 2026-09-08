# MedAssist 部署指南

> 运行时零外部依赖：不依赖任何开发期技能/插件/MCP。只需：代码 + 模型权重 + 配置 + Docker（或本地 conda 环境）。

## 方式一：Docker（推荐交付）

```bash
# 1. 准备目录（四样东西放同级）
#    backend/  frontend/  docker-compose.yml  Dockerfile  requirements.txt
#    backend/models/   ← 模型权重 4.3GB（BGE-M3 + reranker，单独拷贝，不进镜像）
# 2. 配置：复制 .env.example → .env.local 填 QWEN_API_KEY 等；生产必设：
#    AUTH_JWT_SECRET=<openssl rand -hex 32>  APP_ENV=production  AUTH_SEED_DEMO=false
# 3. 一键起（含 Milvus/PG/Attu 基础设施 + app）：
docker compose --profile full up -d --build
#    访问 http://<主机>:8001（默认未注入演示账号，用预先建好的账号登录）
```

要点：
- 模型权重以卷挂载（`./backend/models:/app/backend/models`），不打进镜像；换机器只需拷权重目录
- 容器内非 root 运行，带 HEALTHCHECK（/healthz）
- 基础设施端口已绑 127.0.0.1（仅本机可达）；PG/MinIO 凭据经 `MEDAGENT_PG_PASSWORD` / `MEDAGENT_MINIO_PASSWORD` 注入

## 方式二：本地直跑（开发/演示）

```bash
conda create -n medagent python=3.11 -y && conda activate medagent
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
# docker compose up -d etcd minio milvus postgres attu   ← 基础设施
python -m uvicorn backend.main:app --host 127.0.0.1 --port 8001
```

## 模型配置（医院自选供应商）

管理员登录 → 数据面板 → **模型配置**：
- 分「对话模型 / 视觉模型」两组独立配置
- 支持 **OpenAI Chat Completions 兼容**（通义/DeepSeek/智谱/Kimi/OpenAI…）与 **Anthropic Messages** 两种格式
- 填 完整URL + 模型ID + 展示名 + API Key → **连通性测试**（真实请求，显示延迟/错误）→ 保存 → 激活即切换（缓存自动重建）
- 未配置任何供应商时回落 `.env.local` 内置 Qwen 配置（开箱即用）
- 密钥存 `data/llm_providers.json`（不入 git；生产建议改用环境变量/secret 管理，见 OPERATIONS.md）

## 生产前必查（清单）

1. `APP_ENV=production` + 高熵 `AUTH_JWT_SECRET`（默认值拒启）+ `AUTH_ENABLED=true`
2. `AUTH_SEED_DEMO=false`（不注入演示账号），用 `scripts` 或接口预建真实账号
3. TLS 由反向代理终结；审计/审核数据落 PG（`pg_store` 已双写）
4. 演示语料（PubMed seed）替换为院方授权指南库
5. 自动质控通过/驳回策略开关，需院方合规确认后开启

## 高并发部署

### uvicorn workers（经验公式）

```bash
# workers = CPU 核数 × 2 + 1（经验公式，IO 密集型服务的通用起点）
python -m uvicorn backend.main:app --host 0.0.0.0 --port 8001 --workers $(( $(nproc) * 2 + 1 ))
```

- 说明：本服务以 IO 等待为主（LLM 网关调用 + 向量检索），CPU 密集部分（嵌入/rerank）已有独立线程/设备线程池，公式起点通常够用；实测压测后再微调
- **SSE 连接上限**：`/literature/stream` 是长连接，每个 worker 的并发连接受 `--limit-concurrency`（uvicorn，默认无限）与系统文件描述符（`ulimit -n`）约束。经验值：单 worker 可稳扛数百条 SSE；**workers 数按"生成并发"规划而不是按"在线用户数"**——SSE 只在生成期占用 worker 事件循环，空闲连接不消耗 CPU。如在线用户大，优先加 worker/实例数，并把 `ulimit -n` 调到 ≥65535
- workers > 1 时进程内内存缓存（审计 deque、LLM provider 缓存）各进程独立，属预期行为；权威数据在 JSONL/PG

### nginx 反向代理（SSE 必配项）

```nginx
upstream medassist {
    server 127.0.0.1:8001;   # 多实例：逐行追加 8002/8003…，默认轮询
    keepalive 64;            # 复用后端连接，降低握手开销
}

server {
    listen 443 ssl;
    server_name medassist.hospital.local;

    location / {
        proxy_pass http://medassist;
        proxy_http_version 1.1;
        proxy_set_header Connection "";        # 配合 keepalive
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        # SSE 关键三项：
        proxy_buffering off;                   # 不缓冲，逐事件推送（否则流式卡成一次性输出）
        proxy_read_timeout 300s;               # 长答案/慢生成不被 60s 默认值掐断
        proxy_cache off;
        chunked_transfer_encoding on;
    }
}
```

> `X-Accel-Buffering: no` 响应头已由后端下发，nginx 默认尊重它；显式写 `proxy_buffering off` 是双保险。

### docker compose 横向扩容

```bash
# 单机多实例（注意：app 端口映射需去掉固定 8001:8001 的写法，或改用独立 compose 扩多容器+前置 nginx）
docker compose --profile full up -d --scale app=3
```

- 多实例共享 Milvus/PG/MinIO 基础设施，无需额外协调；审计 JSONL 各实例本地落盘 + PG 双写镜像，跨实例审计以 PG 为准
- 前置 nginx upstream 指向各实例；健康检查用 `/healthz`（容器 HEALTHCHECK 同源）

### API 容灾说明

- **单厂商 key 并发不够**：向模型厂商提交扩容工单提升 RPM/TPM 限额（通义/DeepSeek 等均支持工单或套餐升级）；本系统侧无 key 池轮换设计，不要自行多 key 轮询（易触发厂商风控）
- **跨厂商容灾**（健康探测 + 自动切换）已列入 backlog：规划在模型配置层增加探活与故障自动切换到备用 provider；当前版本故障时走既有三层兜底（体面降级 + 人工复核标记），不会裸 500
