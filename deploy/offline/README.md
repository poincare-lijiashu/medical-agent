# 离网（air-gapped）交付手册

> 目标：**院内网络没有任何外网出口、也没有代理**，整套系统仍能完成安装、启动、升级。
> 本文所有步骤都不要求部署主机访问互联网；联网只发生在"准备阶段"的一台搬运机上。

---

## 0. 一句话流程

```
[联网机] 构建镜像 → pull-and-save → 拷贝介质
        ↓（U 盘 / 内网文件中转 / 跳板机）
[院内主机] load-and-push → 推到 Harbor → helm install → 导入模型权重 → 验收
```

---

## 1. 准备阶段（可联网的一台机器）

### 1.1 构建业务镜像

```bash
# 业务镜像统一以 medassist/<service>:<tag> 构建（与本地开发一致），推送时再由
# load-and-push.sh 改名为 {院内Harbor}/<service>:<tag>，与 Chart 的
# imageRegistry + repository(platform-*) 拼接结果一致。
docker build --build-arg SERVICE=gateway   -t medassist/platform-gateway:1.0.0  -f platform/Dockerfile platform
docker build --build-arg SERVICE=auth      -t medassist/platform-auth:1.0.0     -f platform/Dockerfile platform
docker build --build-arg SERVICE=empi      -t medassist/platform-empi:1.0.0     -f platform/Dockerfile platform
docker build --build-arg SERVICE=clinical  -t medassist/platform-clinical:1.0.0 -f platform/Dockerfile platform
docker build --build-arg SERVICE=pharmacy  -t medassist/platform-pharmacy:1.0.0 -f platform/Dockerfile platform
docker build --build-arg SERVICE=tech      -t medassist/platform-tech:1.0.0     -f platform/Dockerfile platform
docker build --build-arg SERVICE=emr       -t medassist/platform-emr:1.0.0      -f platform/Dockerfile platform
docker build --build-arg SERVICE=insurance -t medassist/platform-insurance:1.0.0 -f platform/Dockerfile platform
docker build -t medassist/ai-core:1.0.0 .
```

> **gateway 镜像必须包含当前代码**：网关路由配置（`/api/v2/clinical/**` 使用 StripPrefix=2）
> 在 2026-10-31 修复过，旧镜像会导致临床接口经网关全量 404。构建前先 `git log --oneline -5` 确认
> 包含 `fix(gateway)` 提交，CI 用 `GatewayRouteConfigTest` 兜底。

构建阶段需要 Maven 与 PyPI；**运行阶段不需要**（多阶段构建，运行镜像只含 JRE / venv）。

构建机默认走国内镜像（不需要科学上网）：`Dockerfile` 内 apt 默认 `mirrors.aliyun.com`、
pip 默认清华源；有内网仓库时用 `--build-arg APT_MIRROR=... --build-arg PIP_INDEX_URL=...` 覆盖。

```bash
docker build \
  --build-arg APT_MIRROR=mirrors.hospital.local \
  --build-arg PIP_INDEX_URL=http://pypi.hospital.local/simple \
  -t harbor.hospital.local/medassist/ai-core:1.0.0 .
```

### 1.2 打包 Chart
```bash
helm package deploy/helm/medassist-platform -d offline-bundle/
# 产物：medassist-platform-0.1.1.tgz
```

> ai-core 镜像较大（含 torch/transformers，数 GB 级），构建机预留 ≥15GB 磁盘与 ≥8GB 内存；
> 构建产物不要求与开发机一致，只要 `images.txt` 的 tag 与 Chart 一致即可。

### 1.3 拉取并保存全部镜像

```powershell
.\deploy\offline\pull-and-save.ps1 -OutDir .\offline-bundle
# 生成 *.tar + MANIFEST.txt（含 digest 与大小）
```

`images.txt` 逐行列出镜像；**业务镜像 tag 必须与 Chart values 一致**，第三方镜像已固定版本，禁止 `latest`。

### 1.4 预置向量模型权重（BGE-M3）

系统默认 `EMBED_ALLOW_DOWNLOAD=false`：**运行时不会去 HuggingFace 下载权重**，缺失则知识库检索报错。

```bash
# 在联网机下载权重目录（约 2.2 GB），随介质一起拷贝
huggingface-cli download BAAI/bge-m3 --local-dir bge-m3
```

院内落盘位置对应 Helm 的 models PVC（默认挂载 `/app/backend/models`），容器内设置
`BGE_M3_PATH=/app/backend/models/bge-m3`。

### 1.5 介质清单（缺一不可）

| 文件 | 说明 |
|---|---|
| `*.tar` | 全部镜像（含 MANIFEST 校验） |
| `medassist-platform-<ver>.tgz` | Helm Chart |
| `bge-m3/` | 向量模型权重目录 |
| `deploy/offline/` | 本文与两个脚本 |
| `nacos/mysql-schema.sql` | Nacos 持久化建表（若院方未托管 MySQL） |
| **Secret 清单与生成脚本** | `medassist-postgres/nacos/jwt/empi/internal/internal-hmac/ai-core`；`internal-hmac` 含 Ed25519 网关密钥对 + 5 个调用方 key（+ 过渡期 keys-json），见 §2.2 |

---

## 2. 部署阶段（院内，无外网）

### 2.1 推镜像到院内 Harbor

```bash
./load-and-push.sh harbor.hospital.local/medassist ./offline-bundle
```

脚本会把第三方镜像也推到同一 Harbor（`harbor.hospital.local/postgres:15-alpine` 等），
保证集群拉镜像时**没有任何外网请求**。

### 2.2 预置 Secret（必须早于 helm upgrade）

`values-prod.yaml` 走 `internalAuth.mode=enforce`，网关与全部业务服务都会读取
`medassist-internal-hmac`；该 Secret 缺失时**所有业务 Pod 与 7 个迁移 Job 会卡在
`CreateContainerConfigError`**，`helm upgrade` 表现为"发布成功但起不来"。

安装前按 `deploy/README.md` §2 的 Secret 表**先创建全部 Secret**，其中：

```bash
# 服务间内部接口令牌（第一因子）
kubectl -n medassist-prod create secret generic medassist-internal \
  --from-literal=token='<≥32 字符随机串>'

# 服务间签名密钥（第二因子）。阶段二（Ed25519）需要下列 7 个键，缺任一个都会 CreateContainerConfigError：
#   keys-json            v1 HMAC 密钥集（过渡期；acceptHmacV1=false 后业务服务不再挂载，仅网关不再使用）
#   gateway-private-key  Ed25519 私钥 PKCS#8 base64 —— 只挂网关
#   gateway-public-key   Ed25519 公钥 X.509 base64  —— 只挂业务服务
#   clinical/emr/insurance/pharmacy/tech  每调用方 HMAC 密钥（base64 32B）
# 生成：openssl rand -base64 32（逐把）；Ed25519 密钥对见 deploy/README.md §4.2.1（无 openssl 时用 JDK 单文件）
kubectl -n medassist-prod create secret generic medassist-internal-hmac \
  --from-literal=keys-json='{"active":"gw-2026-11","keys":{"gw-2026-11":"<base64 32B>"}}' \
  --from-literal=gateway-private-key='<Ed25519 PKCS#8 base64>' \
  --from-literal=gateway-public-key='<Ed25519 X.509 base64>' \
  --from-literal=clinical='<base64 32B>' \
  --from-literal=emr='<base64 32B>' \
  --from-literal=insurance='<base64 32B>' \
  --from-literal=pharmacy='<base64 32B>' \
  --from-literal=tech='<base64 32B>'
```

> 校验：`kubectl -n medassist-prod get secret medassist-internal-hmac -o json | ConvertFrom-Json` 的
> `data` 应包含上表 7 个键；`keys-json` 解出来必须让 `active` 出现在 `keys` 里，否则网关拒绝启动。
> 从阶段一（纯 HMAC）升级时，先按 `deploy/README.md` §4.2.1 的三步切换，不要直接跳到终态。

### 2.3 安装

```bash
helm upgrade --install medassist ./medassist-platform-0.1.1.tgz \
  -n medassist-prod --create-namespace \
  -f values-prod.yaml \
  --set gateway.image.tag=1.0.0 --set auth.image.tag=1.0.0 \
  --set empi.image.tag=1.0.0 --set clinical.image.tag=1.0.0 --set pharmacy.image.tag=1.0.0 \
  --set tech.image.tag=1.0.0 --set emr.image.tag=1.0.0 --set insurance.image.tag=1.0.0 \
  --set aiCore.image.tag=1.0.0 \
  --set gateway.env[0].name=GATEWAY_TRUSTED_PROXIES --set gateway.env[0].value=<集群内网段>
```

> `imageRegistry` 已在 `values-prod.yaml` 固定为 `harbor.hospital.local/medassist`，**不需要**再 `--set`
> （重复叠加会拼成 `.../medassist/medassist` 三层，镜像必然拉不到）。
> **clinical / pharmacy / tech / emr / insurance 五个后续服务的 tag 必须显式给出**：
> values 默认 `0.1.0-SNAPSHOT`，production 环境会直接渲染失败。

> **SkyWalking agent 也必须指向院内 Harbor**（否则纯内网集群 initContainer 会 ImagePullBackOff）：
> `--set external.skywalking.agentImage=harbor.hospital.local/apache/skywalking-java-agent:9.8.0-java21`
> 该镜像由 `load-and-push.sh` 一并推送到同一 Harbor。

> `environment: production` 下若 image tag 仍是 `-SNAPSHOT`，渲染会直接失败（防止"发布成功但跑旧快照"）。

### 2.3 AI 中台离网配置（重要）

| 变量 | 院内取值 | 说明 |
|---|---|---|
| `QWEN_BASE_URL` | `http://vllm.hospital.local/v1` | 指向院内 vLLM / Ollama 网关；**不要用公网 dashscope** |
| `QWEN_API_KEY` | 内部网关口令（可留空） | 内网网关通常不校验 |
| `LLM_PRIVATE_HOSTS_ALLOWLIST` | `vllm.hospital.local` | 私网模型地址必须显式白名单（防 SSRF 防线不失效） |
| `PUBMED_ENABLED` | `false` | 纯内网必须关闭，检索不再尝试访问 NCBI |
| `EMBED_ALLOW_DOWNLOAD` | `false`（默认） | 只加载本地权重 |
| `BGE_M3_PATH` | `/app/backend/models/bge-m3` | 上一步预置的权重目录 |

启动日志会打印 `medical_agent.network_deps`，列出当前仍指向公网的依赖；
`APP_ENV=production` 且存在公网 LLM 地址时输出 WARN，便于巡检发现漏改。

### 2.4 验收（全部离线完成）

```bash
kubectl -n medassist-prod get pods            # 全部 Running/Ready
kubectl -n medassist-prod describe pod <pod> | grep -i image   # 镜像必须来自院内 Harbor
kubectl -n medassist-prod get events --sort-by=.lastTimestamp | grep -i "pull"  # 不应出现外网拉取失败
```

再用网关走一遍：登录 → 患者查询 → 知识库检索 → 文献问答（离线时应走"无文献证据"降级，不报错、不卡顿）。

---

## 3. 常见问题

**Q：镜像拉不到，报 `no such host`？**
A：`imageRegistry` 没指到院内 Harbor，或第三方镜像没按 `load-and-push.sh` 推到同一 registry。

**Q：AI 问答超时 30s？**
A：`QWEN_BASE_URL` 还是公网地址。院内必须指向内部推理网关，并把主机加入
`LLM_PRIVATE_HOSTS_ALLOWLIST`。

**Q：知识库检索报"权重未就绪"？**
A：没有预置 BGE-M3。把权重放到 models PVC，并确认 `BGE_M3_PATH` 目录存在；
本轮起系统**不会**自动联网下载（这是刻意的：无外网时静默下载会把启动拖成几分钟超时）。

**Q：文献回答里没有 PubMed 引用？**
A：`PUBMED_ENABLED=false` 的正常表现；文献能力的检索证据来自院内知识库与指南语料。

---

## 4. 与"开发机"的边界

本仓库**不假设**任何开发机路径或端口：

- 代码/配置中无 `D:\...`、`C:\Users\...` 等绝对路径（脚本说明已改为 `python scripts/xxx.py`）；
- 服务之间的地址全部走环境变量（`PG_HOST`、`MILVUS_URI`、`NACOS_SERVER_ADDR` …），
  Chart 用 Service DNS 注入，不依赖 `127.0.0.1`；
- 交付物是"镜像 tar + Chart + 模型权重"，与构建机解耦；构建机下线不影响院内运行。

### 4.1 网络层兜底（可选但推荐）

配置开关之外，还可用 NetworkPolicy 从网络层"焊死"公网出站：

```bash
# 先在 staging 打开（验证 DNS / PG / Redis / Milvus / Nacos / OAP / 院内 LLM 全通）
helm upgrade medassist deploy/helm/medassist-platform -f values-staging.yaml \
  --set networkPolicy.egress.enabled=true

# 验证通过后再上生产；集群网段不是 RFC1918 时改写 networkPolicy.egress.allowedCidrs
```

开启后 ai-core 只能访问集群 DNS 与 `allowedCidrs` 内的私网地址，公网目标（HuggingFace/PubMed）
被直接拒绝。**不要跳过 staging 验证**——白名单与实际网段不符时表现为"服务名解析失败/连接超时"，
比配置错误更难排查。
