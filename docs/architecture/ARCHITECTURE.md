# MedAssist 铭医 · 系统架构

> 本文是**上传 GitHub 用的架构说明**，所有图都能被 GitHub 原生渲染（Mermaid）。
> 数字取自 2026-11-14 仓库实测：17 个 Maven 模块（16 个可部署服务）、913 个 Java 端点、
> 15 个业务 schema / 334 张表、312 个 Flyway 迁移、2,140 项 Python 测试通过。

---

## 1. 系统上下文

```mermaid
flowchart LR
    subgraph Actors["使用者"]
        DOC["医生 / 护士"]
        PHARM["药师"]
        TECH["医技 / 病案 / 医保"]
        OPS["院方信息科"]
        EXT["第三方系统<br/>HIS · LIS · PACS · 体检 · 科研"]
    end

    subgraph Platform["MedAssist 铭医"]
        CONSOLE["Vue3 临床控制台"]
        AICORE["AI 中台<br/>FastAPI · RAG · Agent 治理"]
        GATEWAY["统一网关<br/>JWT · 租户 · 限流 · 熔断"]
        SVC["16 个 Java 领域服务"]
        DATA["PostgreSQL · Redis · Milvus<br/>MinIO · Orthanc(DICOM)"]
    end

    DOC --> CONSOLE
    PHARM --> CONSOLE
    TECH --> CONSOLE
    OPS --> CONSOLE
    CONSOLE -->|"同源 /api/v2"| AICORE
    CONSOLE -->|"/api/v1"| AICORE
    AICORE -->|"反向代理 /api/v2"| GATEWAY
    EXT -->|"FHIR R4 · HL7 v2 · OAuth2"| GATEWAY
    GATEWAY --> SVC
    SVC --> DATA
    AICORE --> DATA
```

**设计取舍**：控制台与 AI 中台同源（8001），`/api/v2` 由中台反代到网关——
浏览器只面对一个源，避免 CORS、Cookie 跨域与"两个入口两套登录"。

---

## 2. 服务地图（端口即边界）

```mermaid
flowchart TB
    subgraph Edge["入口层"]
        GW["gateway :8080<br/>路由 / 鉴权 / 限流 / 熔断"]
    end

    subgraph Domain["领域服务（每服务独立 schema）"]
        AUTH["auth :8081<br/>用户·角色·令牌·租户·OAuth2"]
        EMPI["empi :8082<br/>患者主索引·合并"]
        CLI["clinical :8083<br/>挂号·就诊·医嘱·收费·住院·手术·输血"]
        PHA["pharmacy :8084<br/>药品·库存·审方·发药"]
        TECH["tech :8085<br/>申请·标本·报告·危急值·PACS"]
        EMR["emr :8086<br/>病历·质控·签名·归档"]
        INS["insurance :8087<br/>目录·结算·清单"]
        KB["kb :8088<br/>知识库·检索·问答"]
        MAT["material :8089<br/>耗材·高值追溯"]
        FHIR["fhir :8090<br/>FHIR R4 · IHE XDS"]
        MDM["masterdata :8091<br/>主数据·映射·指标·质量"]
        MIG["migration :8092<br/>迁移模板·预检·回滚"]
        OPS["ops :8093<br/>工单·SLA·值班·SLO·上线指挥台"]
        REL["release :8094<br/>发布·审批·证据包"]
        LIC["license :8095<br/>授权签发·校验"]
    end

    GW --> AUTH & EMPI & CLI & PHA & TECH & EMR & INS & KB & MAT & FHIR & MDM & MIG & OPS & REL & LIC

    AUTH -.->|"令牌校验 / 撤销名单"| GW
    OPS -.->|"告警 hook 自动开单"| AM["Alertmanager"]
    FHIR -.->|"DICOMweb / DIMSE"| ORT["Orthanc"]
```

**为什么按域拆 16 个服务而不是一个大单体**：每个域有独立的 Flyway 历史
（`clinical_svc` / `emr_svc` / … 共 15 个 schema），迁移互不阻塞；
故障域也被隔离（药房发药慢不会拖垮挂号）。

---

## 3. 临床主链路（医嘱 → 收费 → 执行 → 回写）

```mermaid
sequenceDiagram
    autonumber
    participant D as 医生（控制台）
    participant C as clinical
    participant P as pharmacy
    participant T as tech
    participant N as 护士站
    participant DB as PostgreSQL

    D->>C: 创建就诊 + 开立医嘱（幂等键）
    C->>DB: visit_order(PLACED) + 审计
    C-->>D: 医嘱号

    D->>C: 收费（四眼/权限校验）
    C->>DB: visit_order → CHARGED + charge
    C->>P: 处方/发药请求（内部令牌）
    P->>DB: 审方 → 发药 → 库存扣减

    N->>C: 执行医嘱（扫描腕带/药品）
    C->>DB: nursing_execution + nursing_mobile_task
    Note over C,DB: 执行状态与任务同事务写入，<br/>避免"执行了但任务没销"
    C-->>N: 执行结果 + 审计

    T->>T: 报告审核 → 危急值
    T->>DB: critical_value(PENDING)
    T-->>D: 危急值提醒（通知人/时间必填）
```

> 已知边界（诚实标注）：医嘱状态机目前是 `PLACED → CHARGED → CANCELLED`，
> 执行态落在护理执行表，尚未回写为 `EXECUTED`；这是下一阶段的改造项，
> 不在架构图里"假装已实现"。

---

## 4. AI 问答链路（证据优先 + 拒答）

```mermaid
sequenceDiagram
    autonumber
    participant U as 医生
    participant API as AI 中台 /api/v1
    participant G as 护栏（PHI/注入/越权）
    participant R as 检索（Milvus + PG 关键词）
    participant L as LLM（仅国产/本地）
    participant A as 审计 + 治理

    U->>API: 提问（患者上下文可选）
    API->>G: 入站脱敏 + 注入检测 + 权限
    G-->>API: 脱敏文本（PHI 掩码）
    API->>R: 混合检索（向量 + 关键词 + 重排）
    R-->>API: Top-K 片段 + 来源编号
    API->>L: 提示装配（技能卡 + 证据边界 + 拒答要求）
    L-->>API: 答案 + 引用编号
    API->>G: 出站校验（引用存在性 / 无证据则改写为拒答）
    API->>A: 写审计（模型版本 / 提示词版本 / 引用）
    API-->>U: 答案 + 引用 + needsHumanReview
```

**三个硬约束**：
1. 模型提供方按域名分级（`local / domestic / foreign / unknown`），生产环境境外端点**拒绝启动**；
2. 治理库 ACTIVE 模型必须与运行时一致，不一致生产环境**拒绝启动**；
3. 无证据不给结论：引用缺失时输出"资料不足"而不是编造。

---

## 5. 数据架构

```mermaid
flowchart LR
    subgraph Write["写入路径"]
        API["服务 API"]
        TX["事务 + 乐观锁 version"]
        AUD["追加式审计<br/>触发器拒绝 UPDATE/DELETE"]
    end

    subgraph Store["存储"]
        PG[("PostgreSQL<br/>15 schema / 334 表")]
        RD[("Redis<br/>会话·限流·缓存")]
        MV[("Milvus<br/>向量检索")]
        MO[("MinIO<br/>对象/附件")]
        OR[("Orthanc<br/>DICOM")]
    end

    subgraph Read["读取路径"]
        Q["显式 SQL（JdbcTemplate）"]
        FH["FHIR R4 只读投影"]
        BI["报表快照 / SLO"]
    end

    API --> TX --> PG
    API --> AUD
    API --> RD
    API --> MV
    API --> MO
    API --> OR
    PG --> Q --> API
    PG --> FH --> API
    PG --> BI
```

**治理约定**
- 迁移只前进：`V{n}__desc.sql`，CI 有 `migration_safety_lint` 拦截"非空列无默认值"等破坏性变更；
- 审计表只追加，数据库触发器兜底（不依赖应用自觉）；
- 主数据有版本链（`md_item_version`）与四眼审批，外部标准映射走待确认队列。

---

## 6. 部署拓扑

```mermaid
flowchart TB
    subgraph Local["本地演示（1 台机器）"]
        L1["run_platform_demo.ps1"] --> L2["16 个 Java jar（仅 127.0.0.1）"]
        L1 --> L3["AI 中台 :8001"]
        L2 --> L4["docker compose：PG/Redis/Milvus/MinIO/Nacos/Orthanc"]
    end

    subgraph Hospital["单院私有化（Helm）"]
        H1["Ingress + TLS"] --> H2["gateway Deployment"]
        H2 --> H3["15 个领域 Deployment"]
        H3 --> H4["PG 主备 / Redis / Milvus / MinIO / Orthanc"]
        H5["Prometheus + Grafana + Alertmanager + Loki + SkyWalking"]
    end

    subgraph Multi["多院（Cell 隔离）"]
        M1["租户入口路由<br/>TENANT_HOSTS"] --> M2["Cell A 命名空间"]
        M1 --> M3["Cell B 命名空间"]
        M2 --> M4["独立 PG / 存储 / 密钥"]
        M3 --> M5["独立 PG / 存储 / 密钥"]
    end
```

- 私有化交付：`deploy/helm/medassist-platform`（50 个模板，三套 values）；
- 本地交付：`docker-compose.yml` + `scripts/run_platform_demo.ps1`（一条命令拉起全部服务）；
- 生产启动守卫：开发密钥/默认口令/境外模型/许可未绑定 → **拒绝启动**（fail-closed）。

---

## 7. 安全纵深

```mermaid
flowchart TB
    U["请求"] --> T1["租户解析<br/>Host → tenant"]
    T1 --> T2["JWT 校验<br/>iss / exp / 撤销名单"]
    T2 --> T3["身份头重写<br/>X-Actor / X-Permissions"]
    T3 --> T4["入口限流 / 熔断<br/>按路径 QPS"]
    T4 --> T5["服务内权限点<br/>Permissions.require"]
    T5 --> T6["数据范围<br/>院区 / 科室 / 本人"]
    T6 --> T7["字段级加密<br/>证件号 / 手机号 / 住址"]
    T7 --> T8["追加式审计<br/>操作人 / 来源 IP / traceId"]
```

> 已知边界：患者姓名/生日/过敏史目前是明文列（证件号/手机号/住址已 AES-GCM），
> 字段级加密补齐已列入整改清单，未在本文中宣称完成。

---

## 8. 质量与交付

```mermaid
flowchart LR
    DEV["提交"] --> PRE["pre-push 门禁<br/>分支/冲突/密钥/ruff"]
    PRE --> CI["GitHub Actions<br/>11 个 job"]
    CI --> P1["pytest + 覆盖率"]
    CI --> P2["Java 多模块测试"]
    CI --> P3["前端 typecheck + build"]
    CI --> P4["迁移安全 lint"]
    CI --> P5["Helm 三环境渲染"]
    CI --> P6["E2E 浏览器冒烟"]
    P1 & P2 & P3 & P4 & P5 & P6 --> GATE{"required checks"}
    GATE -->|"全绿"| MERGE["合并 main"]
```

| 指标 | 数值 |
|---|---:|
| Java 模块 / 可部署服务 | 17 / 16 |
| Java 端点 | 913 |
| Java 测试文件 / 测试行 | 272 / 46,036 |
| Python 测试（非 e2e，最近一次全量） | 2,140 通过 + 1 跳过 |
| 数据库 schema / 表 | 15 / 334 |
| Flyway 迁移脚本 | 312 |
| Helm 模板 | 50 |
| 前端 | Vue 3.5 + TS 5.7 + Vite 6.3，自建设计系统（无 UI 框架依赖） |

---

## 9. 文档地图

| 想知道什么 | 看哪里 |
|---|---|
| 部署与交付 | `docs/DEPLOY.md`、`deploy/README.md`、`docs/PROVISIONING-RUNBOOK.md` |
| 运维与值班 | `docs/OPERATIONS.md`、`docs/SLO-20261111.md` |
| 接口与集成 | `platform/README.md`、`docs/INTEGRATION.md`、`docs/openplatform/API.md` |
| AI 治理 | `docs/ai/MODEL-CARD.md`、`docs/eval/2026-11-13/06-ai-governance.md` |
| 第三方评测 | `docs/eval/2026-11-13/SUMMARY.md`（总分 65.9/100，含未整改清单） |
| 上传 GitHub | `docs/ci/GITHUB-UPLOAD-PLAN.md`、`docs/ci/BRANCH-PROTECTION.md` |
