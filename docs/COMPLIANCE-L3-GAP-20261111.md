# 等保三级（GB/T 22239—2019）差距整改包

> 版本：2026-11-11（第 267 批）
> 适用对象：院内私有化部署 / 集团云部署的 MedAssist 平台
> 结论性质：**技术自评与整改清单**，不替代第三方测评机构结论；物理环境、宿主机、网络设备、WAF/IDS、SIEM、密评等院方或云平台责任项按“继承/待提供”标注。

## 一、总体结论

平台在**身份鉴别、访问控制、安全审计、数据完整性、备份与传输安全**上已具备可验证的技术底座；
距离等保三级测评的主要差距集中在**双因素认证、供应链漏洞治理、集中审计/SIEM、商用密码（密评）、
制度与演练文档**五类，均已在第五章给出优先级与落点。

### 本批判定分布

| 结论 | 数量 | 含义 |
|---|---|---|
| 已满足 PASS | 17 | 代码/配置/真实库可验证 |
| 部分满足 PARTIAL | 24 | 技术底座已有，但缺院方设施、演练或证据 |
| 未满足 GAP | 13 | 本期无实现，需排期或院方补齐 |
| 继承/不适用 INHERITED | 10 | 物理环境、宿主机 EDR、网络设备等非本系统责任 |
| 合计 | 64 | 覆盖等保三级技术与管理主要控制项 |

> 判定以当前 `main` 分支、`docs/compliance/evidence/L3-EVIDENCE-20261111.md` 的自动证据包为准。
> 复测时请在生产网络（HTTPS 入口、生产库）重新执行 `scripts/compliance/l3_evidence.ps1`。

## 二、安全物理环境（继承项）

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-01 | 机房出入控制、防火/防水/防雷/温湿度 | 院内 IDC 或云可用区提供 | INHERITED | 院方物理环境测评报告（待提供） | 测评前收集机房资质与巡检记录 |
| L3-02 | 设备与介质管理、报废销毁 | 院方 IT 资产管理制度 | INHERITED | 院方资产台账/介质销毁记录 | 将本平台服务器与备份介质纳入院方台账 |
| L3-03 | 电力/网络冗余 | 院方双路供电、双上联 | INHERITED | 院方基础设施说明 | 生产部署确认跨机架/跨可用区 |

## 三、安全通信网络

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-04 | 网络架构划分与区域隔离 | Helm `NetworkPolicy` 限制仅网关可达业务服务；网关统一入口 | PARTIAL | `deploy/helm/medassist-platform/templates/networkpolicy.yaml` | 院方网络拓扑中把网关放 DMZ、业务与数据库放内网安全域；提供 VLAN/防火墙策略截图 |
| L3-05 | 通信传输完整性 | 生产 Ingress 强制 TLS；网关对全部响应注入安全头；病历关键数据有 CA 签名 + RFC 3161 时间戳 | PASS | `values-prod.yaml`（ssl-redirect/tls）、`SecurityHeadersFilter`、§262 CA 签名 | 测评时提供 TLS 证书链与签名验签样例 |
| L3-06 | 通信传输保密性 | 外部链路 TLS；内部服务间为内网明文（由 NetworkPolicy 隔离） | PARTIAL | `AuthGlobalFilter`、`NetworkPolicy` | 若测评要求内网加密，启用服务网格 mTLS（Istio/Linkerd）或内部 TLS |
| L3-07 | 带宽与优先级保障 | 网关 Sentinel 按路由限流；无 QoS | PARTIAL | `platform/gateway/.../SentinelGatewayConfig`、`application.yml` | 院方网络侧配置关键业务 QoS；应用侧保留限流证据 |
| L3-08 | 网络设备访问控制 | 院方网络设备 AAA | INHERITED | 院方网络设备配置基线 | 测评前提供网络设备账号/审计策略 |
| L3-09 | 时钟同步 | 依赖宿主机 NTP；审计时间使用数据库/应用时钟 | INHERITED | 院方 NTP 配置 | 生产确认所有节点同一 NTP 源，偏差 ≤ 1s |

## 四、安全区域边界

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-10 | 边界访问控制 | 网关白名单/受保护前缀/拒绝路径；内部端点经网关统一 404 | PASS | `GatewayAuthProperties`、`AuthGlobalFilter` | 保持 `/internal/**`、`/docs/**` 拒绝规则 |
| L3-11 | 入侵防范（入口） | 请求体大小限制、JSON 解析限制、参数校验、限流；无 WAF/IPS | PARTIAL | `JsonBodyLimitFilter`、`RouteRateLimitFilter` | 生产前置院方 WAF/云 WAF，开启 OWASP CRS；把 WAF 策略纳入测评证据 |
| L3-12 | 恶意代码防范 | 宿主机 EDR/防病毒由院方提供 | INHERITED | 院方 EDR 覆盖证明 | 容器镜像基线扫描与运行时防护另行补齐（见 L3-35） |
| L3-13 | 入侵检测与防御 | 无 IDS/IPS；仅有应用指标与告警 | GAP | `deploy/observability/alerts/medassist-alerts.yaml` | 接入院方 SOC/IDS 或云安全中心；至少对网关 4xx/5xx、异常登录、审计写入失败告警 |
| L3-14 | 边界完整性检查 | 无终端准入/NAC | INHERITED | 院方 NAC 策略 | 终端准入由院方统一实施 |
| L3-15 | 安全审计（边界） | 网关记录鉴权失败原因、traceId；审计落到各服务 `change_log` | PASS | `AuthGlobalFilter`、`auth_svc.change_log` | 将网关访问日志接入集中日志平台 |
| L3-16 | 数据防泄漏 | PHI 权限点 `phi:read`、列表掩码、最小必要；无 DLP | PARTIAL | `PhiMask`、`DataScope`、各服务掩码测试 | 生产前置 DLP 或院方数据防泄漏策略；导出接口纳入审批 |
| L3-17 | 可信接入 | 无 802.1x/NAC | INHERITED | 院方网络准入 | 由院方统一实现 |

## 五、安全计算环境

### 5.1 身份鉴别

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-18 | 唯一身份标识 | 用户名大小写不敏感唯一；账号 UUID；EMPI 患者主索引 | PASS | `auth_svc.app_user` 唯一索引、EMPI | 保持账号与人员一一对应 |
| L3-19 | 密码复杂度与弱口令 | ≥8 位、字母+数字、不得与用户名相同、弱口令黑名单、单字符重复拒绝 | PASS | `UserService.validatePassword` | 等保测评如要求 8 位以上+大小写/特殊字符，可按院方制度提高到 10 位 |
| L3-20 | 登录失败处理 | 账号/IP 双维度限速，5 次/5 分钟；失败登录审计 | PASS | `LoginThrottle`、`auth_svc.change_log` | 增加“连续失败锁定 N 分钟”策略并与院方确认阈值 |
| L3-21 | 会话超时与注销 | 访问令牌 30 分钟；刷新令牌 7 天轮换；登出/改密/停用走撤销名单，网关 15s 内生效 | PASS | `JwtTokenService`、`TokenRevocationCache` | 测评时演示登出后旧令牌 401 |
| L3-22 | 远程管理防窃听 | 远程运维经 VPN/堡垒机 + TLS | PARTIAL | 院方运维规范 | 提供堡垒机审计截图；禁止直接暴露 SSH/数据库端口 |
| L3-23 | 双因素认证（MFA） | 仅口令 + 可选 OIDC SSO；无 TOTP/短信/硬件密钥 | GAP | 无 | P0：为 ADMIN/QC/财务等特权账号增加 TOTP 或 OIDC MFA；优先复用现有 SSO |
| L3-24 | 密码过期与历史 | 无强制过期/历史密码校验 | PARTIAL | `UserService` | 按院方制度增加 `password_changed_at` 与历史哈希；避免过度强制导致临床抵触 |

### 5.2 访问控制

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-25 | 最小权限与 RBAC | 角色-权限点模型；接口逐点校验；新增角色零代码 | PASS | `Permissions`、`auth_svc.role_permission` | 定期做权限复核（季度） |
| L3-26 | 职责分离 | 病历三级签名不得同人连签；输血双人核对；护理指标提交/审核分离 | PASS | `EmrSignatureService`、`TransfusionService`、`NursingIndicatorService` | 测评时抽样演示“自审被拒” |
| L3-27 | 默认拒绝 | 受保护前缀必须带令牌；业务接口无权限点直接 403 | PASS | `AuthGlobalFilter`、`Permissions.require` | 保持新增接口必须声明权限点 |
| L3-28 | 数据范围（院区/科室） | 网关注入院区/科室声明，下游 fail-closed；多院区隔离测试 | PASS | `DataScopeResolver`、`DataScope`、campus 冒烟脚本 | 测评时演示跨院区不可见 |
| L3-29 | 敏感数据标记 | PHI 权限点、掩码、打印留痕；无统一数据分级标签 | PARTIAL | `phi:read`、`PhiMask`、打印留痕 | 建立数据分类分级清单（见 L3-57） |
| L3-30 | 特权账号管理 | ADMIN 为超级角色；无独立“安全管理员/审计管理员”三权分立 | PARTIAL | `auth_svc.role` | 等保三级常要求系统管理员/安全管理员/审计管理员分离；建议新增 `SECURITY_ADMIN`、`AUDIT_ADMIN` 并限制 ADMIN 日常使用 |

### 5.3 安全审计

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-31 | 审计覆盖 | 登录/登出/失败、授权变更、临床、病历、收费、医保、护理等均有 `change_log` | PASS | 10 个 `*_svc` 审计表；审计查询 API | 测评前导出各域审计样例 |
| L3-32 | 审计记录内容 | 操作人、时间、对象、动作、详情 JSON；网关 traceId 串联 | PASS | `AuditRepository`、各服务 `logChange` | 保持不得记录明文口令/令牌 |
| L3-33 | 审计记录保护 | 36 个追加式触发器拒绝 UPDATE/DELETE；证据脚本可验证 | PASS | `docs/compliance/evidence/L3-EVIDENCE-20261111.md` | 数据库账号不得拥有 DROP/TRUNCATE 权限；由 DBA 复核 |
| L3-34 | 审计保留 ≥6 个月 | 无自动删除；环境运行不足 6 个月时证据显示“0 天” | PARTIAL | `V86__audit_retention_index.sql`、证据包 L3-06 | 生产运行 6 个月后复测；建立按月归档/容量告警，禁止提前清理 |
| L3-35 | 审计分析 | 审计检索页、Action 汇总；无 SIEM 关联分析 | PARTIAL | `AuditController`、Prometheus 告警 | 接入院方 SIEM/日志平台；至少配置“审计写入失败/登录暴力破解/越权 403 激增”告警 |

### 5.4 入侵防范与可信验证

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-36 | 最小服务与端口 | 生产仅网关暴露；内部服务 ClusterIP；Actuator 仅健康/指标 | PASS | `NetworkPolicy`、`application.yml` | 生产禁止直接暴露 8081-8090；开发环境 `::` 监听需在部署时收敛 |
| L3-37 | 漏洞发现与修复 | 有 CI 测试/覆盖率/红队门禁；无依赖 CVE 扫描、无镜像扫描、无 SBOM、无镜像签名 | GAP | `.github/workflows/ci.yml`、评测报告 05/06 | P0：CI 增加 `pip-audit`/`npm audit`/Trivy（高危阻断）、Syft SBOM、Cosign 签名；镜像固定 digest |
| L3-38 | 入侵行为检测 | 应用指标/告警；无 HIDS/容器运行时检测 | PARTIAL | `medassist-alerts.yaml`、Grafana | 接入云安全中心/院方 HIDS；开启容器逃逸与异常进程告警 |
| L3-39 | 恶意代码防范 | 宿主机 EDR | INHERITED | 院方 EDR | 容器镜像基线扫描另行补齐 |
| L3-40 | 可信验证 | 无安全启动/镜像签名验证 | GAP | 无 | 与 L3-37 合并：镜像签名 + 准入控制器验签（Cosign + Kyverno/Gatekeeper） |

### 5.5 数据完整性与保密性

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-41 | 数据完整性 | 病历修订哈希、CA 签名、RFC 3161 时间戳；关键证据表追加式 | PASS | §262 CA 签名、`emr_svc.record_signature`、`ca_timestamp` | 测评时提供签名验签与时间戳校验样例 |
| L3-42 | 数据保密性（传输） | 外部 TLS + HSTS；认证响应 `no-store` | PASS | `SecurityHeadersFilter`、`values-prod.yaml` | HTTPS 入口复验 HSTS |
| L3-43 | 数据保密性（存储） | 依赖磁盘/云盘加密；无字段级加密 | PARTIAL | 院方存储加密证明 | 确认 PG 数据盘、备份盘加密；高敏字段（证件号）可评估应用层加密 |
| L3-44 | 数据备份与恢复 | 仓储镜像 + bundle 备份 + 加密归档；无定期恢复演练记录 | PARTIAL | `scripts/backup_repo.ps1`、`scripts/backup.py`、Helm CronJob | 建立季度恢复演练并留存 RTO/RPO 记录 |
| L3-45 | 剩余信息保护 | 令牌短 TTL + 撤销；无内存清零证明 | PARTIAL | `JwtTokenService`、`TokenRevocationCache` | 测评时说明 JVM 内存管理边界；敏感文件下载使用一次性 URL |
| L3-46 | 个人信息保护 | 最小必要、PHI 权限、掩码、审计；缺隐私政策/DSAR 流程文档 | PARTIAL | `phi:read`、`PhiMask`、§266 患者号最小化 | 补齐隐私政策、患者查阅/更正/删除流程与影响评估（PIA） |

## 六、安全管理中心

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-47 | 集中管控 | Prometheus + Grafana + Alertmanager；审计分散在各域库 | PARTIAL | `deploy/observability/**` | 接入院方统一监控/SIEM；审计日志集中采集 |
| L3-48 | 安全事件监测与告警 | 已有业务/接口告警规则 | PARTIAL | `medassist-alerts.yaml` | 增加安全类告警：异常登录、越权、审计失败、证书到期 |
| L3-49 | 审计集中存储 | 各服务 `change_log`，未集中 | PARTIAL | 10 个审计表 | 日志平台集中存储 ≥6 个月，保留原始 traceId |
| L3-50 | 安全运维管理 | 有 Runbook/部署文档/变更审计 | PARTIAL | `docs/DEPLOY.md`、`AGENT-RUNTIME` | 建立正式变更单、双人复核与回滚记录 |
| L3-51 | 时钟同步 | 依赖宿主机 NTP | INHERITED | 院方 NTP | 纳入测评证据 |

## 七、安全管理制度、机构、人员、建设与运维

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-52 | 安全管理制度 | 工程文档齐全，缺院方正式制度 | GAP | `docs/**` | 由院方信息科牵头发布：账号、权限、审计、备份、应急、供应链制度 |
| L3-53 | 安全管理机构 | 无岗位职责/授权文件 | GAP | 无 | 明确系统管理员、安全管理员、审计管理员职责与授权书 |
| L3-54 | 人员安全 | 无背景审查/保密协议/离岗流程文档 | GAP | 无 | 纳入院方人事与信息安全流程 |
| L3-55 | 系统建设管理 | CI、代码评审、迁移 lint、全量测试；缺 SBOM/依赖扫描 | PARTIAL | `.github/workflows/ci.yml`、`scripts/migration_safety_lint.ps1` | 与 L3-37 合并补齐供应链门禁 |
| L3-56 | 系统运维管理 | 有备份、监控、变更记录；缺正式运维手册签署 | PARTIAL | `docs/DEPLOY.md`、`deploy/**` | 发布《运维手册》《应急预案》并演练 |
| L3-57 | 应急预案与演练 | 有灾备/备份基础；无演练记录 | GAP | 备份脚本 | 制定 RTO/RPO，完成一次数据库恢复与应用切换演练 |
| L3-58 | 供应链管理 | 依赖版本较固定；无 SBOM/签名/供应商安全评估 | GAP | `requirements.txt`、Maven | 输出 SBOM、镜像签名、第三方组件清单与漏洞处置流程 |
| L3-59 | 安全培训 | 无培训记录 | GAP | 无 | 院方组织等保/隐私/口令安全培训并留档 |

## 八、商用密码与数据安全专项

| 编号 | 控制项 | 现状 | 结论 | 证据 | 整改建议 |
|---|---|---|---|---|---|
| L3-60 | 商用密码应用（密评） | 使用 HMAC-SHA256/标准 TLS/CA；未使用 SM2/SM3/SM4 | GAP | `JwtTokenService`、CA 服务 | 密评前明确密码应用方案；与院方/密评机构确认是否要求国密改造；CA 签名可抽象为密码提供方 |
| L3-61 | 密钥管理 | JWT/内部令牌经环境变量/Secret 注入；无 KMS/HSM | PARTIAL | `.env.example`、Helm Secret | 生产接入 KMS/HSM 或院方密钥管理系统，定期轮换 |
| L3-62 | 数据分类分级 | 有 PHI 权限与掩码，无正式分级清单 | GAP | `phi:read` | 输出数据分类分级表（患者身份/诊疗/费用/科研）并映射权限 |
| L3-63 | 个人信息影响评估（PIA） | 无 | GAP | 无 | 上线前完成 PIA，覆盖患者知情、最小必要、跨境（如有） |
| L3-64 | 多租户/多院区隔离 | 院区/科室数据范围已隔离；无租户级隔离 | PARTIAL | `DataScope`、campus 冒烟 | 集团多院区部署时补充租户边界测试 |

## 九、本批已落地的技术整改

| 整改 | 落点 | 验证 |
|---|---|---|
| 统一安全响应头（CSP/X-Frame-Options/nosniff/Referrer-Policy/Permissions-Policy） | `platform/gateway/.../filter/SecurityHeadersFilter` | `SecurityHeadersFilterTest` 4 条；真实网关 401 响应已带全部头 |
| HSTS（仅 HTTPS 输出，支持 Ingress 终止 TLS） | 同上 + `medassist.gateway.security-headers` | X-Forwarded-Proto=https 单测；生产 HTTPS 复验 |
| 认证响应禁止缓存 | 同上（`/api/v2/auth/**` → `no-store`） | 真实登录响应 `Cache-Control: no-store` |
| 审计保留期查询索引 | `auth V86__audit_retention_index.sql` | 真实库 Flyway v86；证据脚本可查最早/最晚审计时间 |
| 自动化等保技术证据包 | `scripts/compliance/l3_evidence.ps1` | 生成 `docs/compliance/evidence/L3-EVIDENCE-20261111.md`：10 PASS / 3 WARN / 0 FAIL |
| 差距清单与整改优先级 | 本文件 | 64 项逐条结论与证据 |

## 十、优先整改路线（P0 → P2）

| 优先级 | 事项 | 关联控制项 | 完成标志 |
|---|---|---|---|
| P0 | 特权账号 MFA（TOTP 或 OIDC MFA） | L3-23 | ADMIN/QC/财务登录需第二因子；失败审计 |
| P0 | 供应链门禁：依赖 CVE 扫描 + 镜像扫描 + SBOM + 镜像签名（Trivy/Syft/Cosign） | L3-37/L3-40/L3-58 | CI 阻断高危；发布附 SBOM 与签名 digest |
| P0 | 集中审计/SIEM 接入 + 安全告警 | L3-13/L3-47/L3-49 | 登录暴力破解、越权 403、审计失败可告警 |
| P1 | 三权分立（系统/安全/审计管理员）与 ADMIN 日常账号分离 | L3-30/L3-53 | 角色与授权书落地，审计复核 |
| P1 | 恢复演练与 RTO/RPO 记录 | L3-44/L3-57 | 完成一次真实恢复演练并留档 |
| P1 | 数据分类分级 + PIA + 隐私政策/DSAR | L3-29/L3-62/L3-63/L3-46 | 文档发布并完成一次评估 |
| P1 | WAF/IDS 或云安全中心接入 | L3-11/L3-13/L3-38 | 入口攻击与异常行为可检测 |
| P2 | 密码应用方案与密评/GM 改造评估 | L3-60/L3-61 | 密评机构出具方案意见 |
| P2 | 内网 mTLS（如测评要求） | L3-06 | 服务间加密并可验证证书 |
| P2 | 制度、培训、供应链评估文档包 | L3-52~L3-59 | 院方发文、培训签到、供应商评估表 |

## 十一、证据索引

| 证据 | 路径 |
|---|---|
| 自动技术证据包（本批生成） | `docs/compliance/evidence/L3-EVIDENCE-20261111.md` |
| 网关安全头/鉴权 | `platform/gateway/src/main/java/com/medassist/gateway/**` |
| 身份与权限 | `platform/auth/src/main/java/com/medassist/auth/**`、`platform/common/.../security/**` |
| 审计追加式迁移 | `platform/auth/.../V34__append_only_audit.sql`、各域 `*__append_only_*.sql` |
| 审计保留期索引 | `platform/auth/.../V86__audit_retention_index.sql` |
| CA 签名与时间戳 | `docs/AGENT-RUNTIME-20261105.md` §262 |
| 备份与恢复 | `scripts/backup.py`、`scripts/backup_repo.ps1`、`deploy/helm/.../templates/backup-*.yaml` |
| 监控与告警 | `deploy/observability/**` |
| 网络隔离 | `deploy/helm/.../templates/networkpolicy.yaml` |
| 生产 TLS | `deploy/helm/.../values-prod.yaml`（ssl-redirect/tls） |
| 等保测评基线 | GB/T 22239—2019《信息安全技术 网络安全等级保护基本要求》 |

## 十二、使用说明

1. 测评前在生产网络执行：
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\compliance\l3_evidence.ps1 `
     -GatewayBase https://<生产域名> -PgContainer <生产库容器或改为直连参数> `
     -BackupDir <生产备份目录> -Output docs\compliance\evidence\L3-EVIDENCE-PROD.md -Strict
   ```
2. `-Strict` 下存在 FAIL 时脚本退出码为 1，可直接作为测评前门禁。
3. 物理环境、宿主机 EDR、WAF/IDS、SIEM、密评、制度与演练属于院方或第三方责任，
   本脚本只能证明“本系统技术控制项”，不能替代第三方测评报告。
