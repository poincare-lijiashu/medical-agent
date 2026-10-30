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

{{/* 镜像全名：imageRegistry 为空时直接用 repository（可写完整仓库地址） */}}
{{- define "medassist.image" -}}
{{- if .root.Values.imageRegistry -}}
{{- printf "%s/%s:%s" .root.Values.imageRegistry .image.repository .image.tag -}}
{{- else -}}
{{- printf "%s:%s" .image.repository .image.tag -}}
{{- end -}}
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
{{- end -}}

{{/* SkyWalking agent 初始化容器：从官方 agent 镜像复制到 emptyDir（含 plugins 配置） */}}
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
    - cp -a /skywalking/agent/. /opt/skywalking-agent/ && test -f /opt/skywalking-agent/skywalking-agent.jar
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

{{/* Service 名（Ingress 后端引用） */}}
{{- define "medassist.serviceNameFor" -}}
{{- $map := dict "gateway" "gateway" "auth" "auth" "empi" "empi" "aiCore" "ai-core" -}}
{{- include "medassist.componentName" (dict "root" .root "component" (get $map .service)) -}}
{{- end -}}
