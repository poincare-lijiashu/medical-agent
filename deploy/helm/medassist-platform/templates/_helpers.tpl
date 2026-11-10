{{/*
基础命名与标签。所有模板统一经这些 helper 生成名称/标签，避免手写漂移。
*/}}

{{- define "medassist.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "medassist.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
AI 中台指标抓取总开关（评审 M-21 整改）。
三处必须同一条件：deployment 注入 METRICS_TOKEN、生成 ai-core ServiceMonitor、
（间接）G105 抓取链路的文档假设。此前 deployment 只判断 serviceMonitor.aiCore.enabled，
会出现"令牌注入了、ServiceMonitor 没生成"，纯浪费密钥面。
*/}}
{{- define "medassist.aiCoreMetricsEnabled" -}}
{{- if and .Values.aiCore.enabled .Values.serviceMonitor.enabled .Values.serviceMonitor.aiCore.enabled -}}true{{- end -}}
{{- end -}}

{{/*
Agent 运营告警规则（PrometheusRule）开关（评审 M-15/M-6 整改）。
本地 compose 读 deploy/prometheus/*.rules.yml；K8s Operator 形态必须部署 CRD，
否则 G97/G102 的 7 条规则在集群里**一条都不会生效**。
*/}}
{{- define "medassist.agentRulesEnabled" -}}
{{- if and .Values.serviceMonitor.enabled .Values.serviceMonitor.rules.enabled -}}true{{- end -}}
{{- end -}}

{{/* 组件级资源名：<release>-<chart>-<component> */}}
{{- define "medassist.componentName" -}}
{{- printf "%s-%s" (include "medassist.fullname" .root) .component | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "medassist.selectorLabels" -}}
app.kubernetes.io/name: {{ include "medassist.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "medassist.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" }}
{{ include "medassist.selectorLabels" . }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: medassist-platform
{{- end -}}

{{/* 镜像全名：imageRegistry 为空时直接用 repository（可写完整仓库地址）。
   非 dev 环境禁止 SNAPSHOT：可变 tag + IfNotPresent 会导致"发布成功但集群没换镜像"（M9）。 */}}
{{- define "medassist.image" -}}
{{- $full := "" -}}
{{- if .root.Values.imageRegistry -}}
{{- $full = printf "%s/%s:%s" .root.Values.imageRegistry .image.repository .image.tag -}}
{{- else -}}
{{- $full = printf "%s:%s" .image.repository .image.tag -}}
{{- end -}}
{{- $env := .root.Values.environment | default "dev" -}}
{{- if and (ne $env "dev") (hasSuffix "-SNAPSHOT" (toString .image.tag)) -}}
{{- fail (printf "环境 %s 不允许使用 SNAPSHOT 镜像（%s）：请由 CI 通过 --set 注入不可变 tag" $env $full) -}}
{{- end -}}
{{- $full -}}
{{- end -}}

{{- define "medassist.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "medassist.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
密钥名解析：secrets.create=true 时使用本 Chart 创建的 Secret（<fullname>-<suffix>），
否则引用外部预置 Secret。生产走外部 Secret（External Secrets / Sealed Secrets）。
*/}}
{{- define "medassist.secretName" -}}
{{- if .root.Values.secrets.create -}}
{{- printf "%s-%s" (include "medassist.fullname" .root) .suffix -}}
{{- else -}}
{{- required (printf "external.%s.existingSecret 不能为空（生产必须引用预置 Secret）" .suffix) .external.existingSecret -}}
{{- end -}}
{{- end -}}

{{/*
JVM 与 APM 参数。SkyWalking agent 由 initContainer 从官方 agent 镜像复制到共享卷，
再经 JAVA_TOOL_OPTIONS 挂载——镜像本身不含 agent，同一镜像可在有无 APM 的环境通用。
*/}}
{{- define "medassist.javaToolOptions" -}}
{{- $opts := list "-XX:MaxRAMPercentage=75" "-XX:+ExitOnOutOfMemoryError" "-XX:+UseG1GC" "-Duser.timezone=Asia/Shanghai" "-Dfile.encoding=UTF-8" -}}
{{- if .root.Values.external.skywalking.enabled -}}
{{- $agent := printf "-javaagent:/opt/skywalking-agent/skywalking-agent.jar -Dskywalking.agent.service_name=%s -Dskywalking.collector.backend_service=%s -Dskywalking.agent.sample_n_per_3_secs=%v -Dskywalking.logging.level=%s" (include "medassist.componentName" .) .root.Values.external.skywalking.oapEndpoint .root.Values.external.skywalking.sampleNPer3Secs .root.Values.external.skywalking.agentLogLevel -}}
{{- $opts = append $opts $agent -}}
{{- end -}}
{{- join " " $opts -}}
{{- end -}}

{{/*
Java 服务公共环境变量。
参数：dict "root" $ "component" "auth" "jwt" true "empi" false "flyway" false
- 数据源（PG_*）：用户名/密码取自 Secret
- Nacos 注册：enabled 切换 NACOS_DISCOVERY_ENABLED
- JWT：仅 auth 与 gateway 注入（最小权限：empi 不需要签发/校验令牌）
- EMPI 加密密钥：仅 empi 注入
- 迁移：migration.enabled=true 且 flyway=false 时关闭应用内 Flyway（迁移由 PreSync Hook Job 完成）；
  Hook Job 自身传 flyway=true 保持迁移开启
*/}}
{{- define "medassist.commonEnv" -}}
- name: TZ
  value: {{ .root.Values.global.timezone | quote }}
- name: SPRING_PROFILES_ACTIVE
  value: {{ .root.Values.global.springProfiles | quote }}
- name: JAVA_TOOL_OPTIONS
  value: {{ include "medassist.javaToolOptions" . | quote }}
- name: PG_HOST
  value: {{ .root.Values.external.postgres.host | quote }}
- name: PG_PORT
  value: {{ .root.Values.external.postgres.port | quote }}
- name: PG_DATABASE
  value: {{ .root.Values.external.postgres.database | quote }}
- name: PG_USER
  valueFrom:
    secretKeyRef:
      name: {{ include "medassist.secretName" (dict "root" .root "external" .root.Values.external.postgres "suffix" "postgres") }}
      key: {{ .root.Values.external.postgres.secretKeys.username }}
- name: PG_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "medassist.secretName" (dict "root" .root "external" .root.Values.external.postgres "suffix" "postgres") }}
      key: {{ .root.Values.external.postgres.secretKeys.password }}
# 评测 04 遗留：Java 侧 JDBC 连接 TLS（application.yml 里以 sslmode/sslrootcert 占位符消费）。
# 默认 prefer：dev 的 PG 多数没开 TLS，prefer 会回落明文，不阻断本地联调；
# staging/prod 的 values 已设 verify-full（与 Python 侧同口径）。
- name: PG_SSLMODE
  value: {{ .root.Values.external.postgres.sslmode | default "prefer" | quote }}
{{- if .root.Values.external.postgres.sslrootcert }}
- name: PG_SSLROOTCERT
  value: {{ .root.Values.external.postgres.sslrootcert | quote }}
{{- end }}
{{- if .root.Values.external.nacos.enabled }}
- name: NACOS_DISCOVERY_ENABLED
  value: "true"
- name: NACOS_SERVER_ADDR
  value: {{ .root.Values.external.nacos.serverAddr | quote }}
- name: NACOS_NAMESPACE
  value: {{ .root.Values.external.nacos.namespace | default "public" | quote }}
- name: NACOS_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ include "medassist.secretName" (dict "root" .root "external" .root.Values.external.nacos "suffix" "nacos") }}
      key: {{ .root.Values.external.nacos.secretKeys.username }}
- name: NACOS_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "medassist.secretName" (dict "root" .root "external" .root.Values.external.nacos "suffix" "nacos") }}
      key: {{ .root.Values.external.nacos.secretKeys.password }}
{{- else }}
- name: NACOS_DISCOVERY_ENABLED
  value: "false"
{{- end }}
{{- if .jwt }}
- name: JWT_ISSUER
  value: {{ .root.Values.external.jwt.issuer | quote }}
- name: JWT_TTL
  value: {{ .root.Values.external.jwt.ttl | quote }}
- name: JWT_REFRESH_TTL
  value: {{ .root.Values.external.jwt.refreshTtl | default "P7D" | quote }}
- name: JWT_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ include "medassist.secretName" (dict "root" .root "external" .root.Values.external.jwt "suffix" "jwt") }}
      key: {{ .root.Values.external.jwt.secretKey }}
{{- end }}
# 服务间内部接口令牌：全平台唯一变量名（一处轮换、全部滚动重启即可）。
# 各服务的 <SVC>_INTERNAL_TOKEN 仅作兼容回落，新部署只需维护 INTERNAL_API_TOKEN。
- name: INTERNAL_API_TOKEN
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.external.internalApi.existingSecret }}
      key: {{ .root.Values.external.internalApi.secretKey }}
# ---- 服务间签名（MF-07 阶段一 v1 / 阶段二 v2）----
# 注意 Spring 的宽松绑定规则：环境变量名 = 属性名大写、点换下划线、**连字符直接删除**，
# 因此 medassist.internal-auth.keys-json 对应 MEDASSIST_INTERNALAUTH_KEYSJSON
# （写成 MEDASSIST_INTERNAL_AUTH_KEYS_JSON 不会生效，mode 会保持默认 off）。
#
# 密钥面按角色裁剪（R5-M3）：
#   网关     → keys-json（hmac）/ gateway-private-key（ed25519）
#   业务服务 → keys-json（v1 兼容验签）+ gateway-public-key（v2 验签）+ 按需的调用方密钥
{{- $ia := .root.Values.internalAuth | default dict }}
{{- $hmac := .root.Values.external.internalHmac | default dict }}
{{- $hmacSecret := $hmac.existingSecret | default "medassist-internal-hmac" }}
{{- $signing := toString ($ia.gatewaySigning | default "hmac") }}
{{- $isGateway := eq .component "gateway" }}
{{- $acceptEd25519 := or $ia.acceptEd25519 (eq $signing "ed25519") }}
{{- $acceptHmacV1 := ne (toString $ia.acceptHmacV1) "false" }}
- name: MEDASSIST_INTERNALAUTH_MODE
  value: {{ $ia.mode | default "log" | quote }}
- name: MEDASSIST_INTERNALAUTH_CLOCKSKEWSECONDS
  value: {{ $ia.clockSkewSeconds | default 60 | quote }}
- name: MEDASSIST_INTERNALAUTH_NONCETTLSECONDS
  value: {{ $ia.nonceTtlSeconds | default 180 | quote }}
# P0-S5：多副本 nonce 去重。jdbc = 各服务自有 schema 的 internal_nonce 表；
# 网关（WebFlux）不装配 servlet 过滤器，无需该变量。
{{- if and (not $isGateway) (eq (toString ($ia.nonceStore | default "memory")) "jdbc") }}
- name: MEDASSIST_INTERNALAUTH_NONCESTORE
  value: "jdbc"
{{- end }}
- name: MEDASSIST_INTERNALAUTH_INTERNALPATHSSIGNATUREREQUIRED
  value: {{ $ia.internalPathsSignatureRequired | default false | quote }}
# P0-8：请求体摘要纳入调用方签名（v1i2）。灰度顺序：服务端先部署（false，兼容 v1i）→
# 调用方全部升级 → 再把服务端与调用方一起置 true（此时 v1i 请求 403）。
- name: MEDASSIST_INTERNALAUTH_BODYHASHREQUIRED
  value: {{ $ia.bodyHashRequired | default false | quote }}
- name: MEDASSIST_INTERNALAUTH_BODYHASHMAXBYTES
  value: {{ $ia.bodyHashMaxBytes | default 1048576 | quote }}
{{- if eq .component "auth" }}
# 网关→auth 的撤销名单同步（/internal/revocations）只带 X-Internal-Token：
# 网关是 WebFlux 应用，不装配 servlet 侧的调用方签名器（v1i 为 HMAC），无法签该请求；
# 该端点只返回已撤销 jti（无 PHI），由 AuthController fail-closed 校验令牌 +
# NetworkPolicy 限制为仅网关可达。故在 auth 上单独豁免签名（P0-9 收口的前置条件）。
# 注意：exempt-paths 一旦显式配置会**整体替换**服务端默认值，因此这里必须列全。
- name: MEDASSIST_INTERNALAUTH_EXEMPTPATHS
  value: {{ $ia.authExemptPaths | default "/actuator/health/**,/actuator/info,/actuator/prometheus,/internal/revocations" | quote }}
{{- end }}
{{- if and $isGateway (eq $signing "ed25519") }}
- name: MEDASSIST_INTERNALAUTH_GATEWAYSIGNING
  value: "ed25519"
- name: MEDASSIST_INTERNALAUTH_GATEWAYKEYID
  value: {{ $ia.gatewayKeyId | default "gw-v2" | quote }}
# 私钥只挂到网关：业务服务即便被攻破也拿不到签发能力
- name: MEDASSIST_INTERNALAUTH_GATEWAYPRIVATEKEY
  valueFrom:
    secretKeyRef:
      name: {{ $hmacSecret }}
      key: {{ $hmac.gatewayPrivateKeySecretKey | default "gateway-private-key" }}
{{- end }}
{{- if or (and $isGateway (eq $signing "hmac")) (and (not $isGateway) $acceptHmacV1) }}
- name: MEDASSIST_INTERNALAUTH_KEYSJSON
  valueFrom:
    secretKeyRef:
      name: {{ $hmacSecret }}
      key: {{ $hmac.secretKey | default "keys-json" }}
{{- end }}
{{- if and (not $isGateway) $acceptEd25519 }}
# 业务服务只持公钥：验证网关 v2 签名
- name: MEDASSIST_INTERNALAUTH_GATEWAYPUBLICKEY
  valueFrom:
    secretKeyRef:
      name: {{ $hmacSecret }}
      key: {{ $hmac.gatewayPublicKeySecretKey | default "gateway-public-key" }}
{{- end }}
{{- if $ia.mountCallerKeys }}
{{- range $caller := (index ($ia.callerKeys | default dict) .component | default list) }}
- name: MEDASSIST_INTERNALAUTH_CALLERKEYS_{{ upper $caller }}
  valueFrom:
    secretKeyRef:
      name: {{ $hmacSecret }}
      key: {{ $caller }}
{{- end }}
{{- end }}
{{- if .empi }}
- name: EMPI_FIELD_KEY
  valueFrom:
    secretKeyRef:
      name: {{ include "medassist.secretName" (dict "root" .root "external" .root.Values.external.empi "suffix" "empi") }}
      key: {{ .root.Values.external.empi.secretKeys.fieldKey }}
- name: EMPI_HASH_SALT
  valueFrom:
    secretKeyRef:
      name: {{ include "medassist.secretName" (dict "root" .root "external" .root.Values.external.empi "suffix" "empi") }}
      key: {{ .root.Values.external.empi.secretKeys.hashSalt }}
{{- end }}
{{- if and .root.Values.migration.enabled (not .flyway) }}
- name: SPRING_FLYWAY_ENABLED
  value: "false"
{{- end }}
{{- if .flyway }}
# 迁移 Job 显式下发 baseline 策略：prod/staging 必须 false（schema 漂移要让迁移失败，
# 而不是被静默 baseline）。values-prod 里的 clinical.env 不会渲染到 Job，故在此统一注入。
- name: FLYWAY_BASELINE_ON_MIGRATE
  value: {{ .root.Values.migration.baselineOnMigrate | default false | quote }}
{{- end }}
{{- end -}}

{{/* SkyWalking agent 初始化容器：复制 agent，并把服务端框架插件从 optional-plugins 激活。
   实测（2026-10-30）：Spring Cloud Gateway 与 Spring MVC 的服务端插件默认不在 plugins/ 下，
   不激活则只有实例心跳、没有链路（gateway 只出 outbound webclient 插件）。 */}}
{{- define "medassist.skywalkingInit" -}}
{{- if .root.Values.external.skywalking.enabled }}
- name: skywalking-agent
  image: {{ .root.Values.external.skywalking.agentImage | quote }}
  imagePullPolicy: IfNotPresent
  # agent 镜像默认以 root 运行，仅复制文件；显式覆盖 Pod 级 runAsNonRoot
  securityContext:
    runAsNonRoot: false
    runAsUser: 0
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop: ["ALL"]
  command:
    - sh
    - -c
    - >-
      cp -a /skywalking/agent/. /opt/skywalking-agent/ &&
      test -f /opt/skywalking-agent/skywalking-agent.jar &&
      cd /opt/skywalking-agent/optional-plugins &&
      {{- range .root.Values.external.skywalking.optionalPlugins }}
      for f in {{ . }}; do [ -f "$f" ] && cp "$f" /opt/skywalking-agent/plugins/ || true; done &&
      {{- end }}
      true
  volumeMounts:
    - name: skywalking-agent
      mountPath: /opt/skywalking-agent
{{- end }}
{{- end -}}

{{/* SkyWalking agent 共享卷（未启用时为空） */}}
{{- define "medassist.skywalkingVolume" -}}
{{- if .Values.external.skywalking.enabled }}
- name: skywalking-agent
  emptyDir: {}
{{- end }}
{{- end -}}

{{/* 通用临时目录卷：容器根文件系统只读，JVM/Tomcat 需要 /tmp 可写 */}}
{{- define "medassist.tmpVolume" -}}
- name: tmp
  emptyDir: {}
{{- end -}}

{{/* 工作负载类型：rollout.enabled=true 且组件在 rollout.components 内时用 Argo Rollouts 的 Rollout */}}
{{- define "medassist.workloadKind" -}}
{{- if and .root.Values.rollout.enabled (has .component .root.Values.rollout.components) -}}
Rollout
{{- else -}}
Deployment
{{- end -}}
{{- end -}}

{{- define "medassist.workloadApiVersion" -}}
{{- if eq (include "medassist.workloadKind" .) "Rollout" -}}
argoproj.io/v1alpha1
{{- else -}}
apps/v1
{{- end -}}
{{- end -}}

{{/*
  Canary 策略（仅 Rollout 使用）。
  不做流量切分（未引入 Istio/Nginx canary 注解），而是按副本数逐级放量：
  新版本先起 1 个副本 → 观察 → 人工确认 → 全量。

  与 HPA 冲突处理（M10）：组件开启 autoscaling 时不能出现 setCanaryScale，
  否则 HPA 与灰度固定副本会互相拉扯；此时自动改用 rollout.hpaSteps（只保留 pause 闸门）。
*/}}
{{- define "medassist.canaryStrategy" -}}
{{- $svc := index .root.Values .component -}}
{{- $steps := .root.Values.rollout.steps -}}
{{- if and $svc $svc.autoscaling $svc.autoscaling.enabled -}}
{{- $steps = .root.Values.rollout.hpaSteps -}}
{{- end -}}
strategy:
  canary:
    {{- if .root.Values.rollout.analysis.enabled }}
    analysis:
      templates:
        - templateName: {{ include "medassist.componentName" . }}-success-rate
    {{- end }}
    steps:
      {{- toYaml $steps | nindent 6 }}
{{- end -}}

{{/* Service 名（Ingress 后端引用） */}}
{{- define "medassist.serviceNameFor" -}}
{{- $map := dict "gateway" "gateway" "auth" "auth" "empi" "empi" "aiCore" "ai-core" -}}
{{- include "medassist.componentName" (dict "root" .root "component" (get $map .service)) -}}
{{- end -}}

{{/*
Pod 调度打散（SHOULD-FIX #16）。
- 组件显式配置 affinity 时完全以显式配置为准（便于特殊机房/专用节点）；
- 未配置时给"同组件尽量不同节点"的软反亲和 + 拓扑打散。
为什么不用 requiredDuringScheduling：小集群/单节点环境会被卡成 Pending；
preferred + ScheduleAnyway 保证"能调度优先，其次才打散"。
*/}}
{{- define "medassist.podScheduling" -}}
{{- $cfg := index .root.Values .component -}}
{{- if $cfg.affinity }}
affinity:
{{ toYaml $cfg.affinity | nindent 2 }}
{{- else }}
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          topologyKey: kubernetes.io/hostname
          labelSelector:
            matchLabels:
              app.kubernetes.io/name: {{ include "medassist.name" .root }}
              app.kubernetes.io/instance: {{ .root.Release.Name }}
              app.kubernetes.io/component: {{ .component }}
{{- end }}
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway
    labelSelector:
      matchLabels:
        app.kubernetes.io/name: {{ include "medassist.name" .root }}
        app.kubernetes.io/instance: {{ .root.Release.Name }}
        app.kubernetes.io/component: {{ .component }}
{{- end -}}
