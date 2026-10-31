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
