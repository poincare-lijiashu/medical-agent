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
