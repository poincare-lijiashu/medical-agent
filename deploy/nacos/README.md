# Nacos 生产化运行手册（2.5.1）

> Nacos 是内部组件，**不可暴露公网**；本手册覆盖院区级生产形态：
> 外部 MySQL 持久化 + 强制鉴权 + 管理员密码一次性初始化。

## 1. 为什么必须做这三件事

| 项 | 单机联调形态（基础 compose） | 生产形态（本手册） |
|---|---|---|
| 存储 | 内嵌 derby（容器删除即丢元数据） | 外部 MySQL（可备份、可主从、可审计） |
| 鉴权 | 关闭 | 强制开启，服务端间身份校验 |
| 管理员 | 老版本默认 `nacos/nacos` | 2.4+ 无默认密码，需 API 初始化 |

## 2. 前置条件

- Docker / Docker Compose
- 三份随机密钥（不要把示例值用于生产）：

```powershell
# 令牌签名密钥：Base64(≥32 字节)
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes)

# 服务端身份标识
openssl rand -hex 16   # 若无 openssl，可用 PowerShell 生成随机十六进制
```

## 3. 启动

```powershell
$env:MYSQL_ROOT_PASSWORD = '<强口令>'
$env:NACOS_DB_PASSWORD  = '<强口令>'
$env:NACOS_ADMIN_PASSWORD = '<强口令>'
$env:NACOS_AUTH_TOKEN = '<上一步生成的 Base64>'
$env:NACOS_AUTH_IDENTITY_KEY = '<随机串>'
$env:NACOS_AUTH_IDENTITY_VALUE = '<随机串>'

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d mysql nacos
```

- MySQL 首次初始化时自动执行 `deploy/nacos/mysql-schema.sql`（官方 2.5.1 纯 DDL，无默认账号）
- Nacos 就绪后执行一次性初始化：

```powershell
docker compose -f docker-compose.yml -f docker-compose.prod.yml run --rm nacos-init
# 输出 "初始化成功" 或 "已初始化或无需初始化（幂等跳过）"
```

等效手工命令（排障用）：

```powershell
curl.exe -X POST "http://127.0.0.1:8848/nacos/v1/auth/users/admin" -d "password=<管理员口令>"
```

## 4. 验证

```powershell
# 1) 健康
curl.exe -s http://127.0.0.1:8848/nacos/v1/console/health/readiness

# 2) 管理员登录（拿到 accessToken）
$token = (Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8848/nacos/v1/auth/login `
  -Body @{username='nacos'; password='<管理员口令>'}).accessToken
"token 长度 = $($token.Length)"

# 3) 带 token 查询服务列表（无 token 应返回 403）
curl.exe -s "http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20&accessToken=$token"
```

## 5. 客户端配置

### Java（Spring Cloud Alibaba）

Chart 已注入：`NACOS_DISCOVERY_ENABLED=true`、`NACOS_SERVER_ADDR`、`NACOS_NAMESPACE`，
账号密码来自 Secret `medassist-nacos`。本机直跑示例：

```powershell
$env:NACOS_DISCOVERY_ENABLED='true'
$env:NACOS_SERVER_ADDR='127.0.0.1:8848'
$env:NACOS_USERNAME='nacos'
$env:NACOS_PASSWORD='<管理员口令>'
java -jar platform\auth\target\platform-auth-0.1.0-SNAPSHOT.jar
```

### Python AI 中台

```powershell
$env:NACOS_ENABLED='true'
$env:NACOS_SERVER_ADDRESSES='127.0.0.1:8848'
$env:NACOS_USERNAME='nacos'
$env:NACOS_PASSWORD='<管理员口令>'
$env:NACOS_IP='<本机对内 IP>'   # 容器/多网卡必须显式指定
```

## 6. 运维

### 备份

```powershell
docker exec medagent_mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction nacos_config' > nacos_config_$(Get-Date -Format yyyyMMddHHmm).sql
```

### 恢复

```powershell
Get-Content nacos_config_20261030.sql | docker exec -i medagent_mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" nacos_config'
```

### 密钥轮换（NACOS_AUTH_TOKEN）

`NACOS_AUTH_TOKEN` 用于签发/校验控制台 accessToken；更换后**所有客户端需要重启**：

1. 低峰期更新环境变量 → `docker compose ... up -d nacos`
2. 重启 gateway / auth / empi / AI 中台（读取新凭据）
3. 验证 §4 的登录与实例注册

### 高可用（多节点）

单机 Nacos 不满足生产 SLA。集群形态要求：

1. 3 节点 Nacos（`MODE=cluster`，`NACOS_SERVERS=ip1:8848,ip2:8848,ip3:8848`）
2. 共享外部 MySQL（推荐主从 + 定期备份）
3. 前置四层负载均衡/软负载（VIP），客户端 `server-addr` 填 VIP 或全部节点
4. 节点间 `NACOS_AUTH_IDENTITY_KEY/VALUE` 与 `NACOS_AUTH_TOKEN` 必须完全一致

> 集群化后本 Chart 无需改动：只消费 `external.nacos.serverAddr`（填 VIP）。

## 7. 常见故障

| 现象 | 原因 | 处理 |
|---|---|---|
| 客户端 `ErrCode:403, user not found!` | 2.4+ 未初始化管理员密码 | 执行 §3 的 `nacos-init` 或手工 admin API |
| 启动报 `NACOS_AUTH_TOKEN` 无效 | Token 不是 Base64 或解码后 <32 字节 | 重新生成（§2） |
| 服务注册成功但重启后丢失 | 仍在使用 derby | 确认 `SPRING_DATASOURCE_PLATFORM=mysql` 且 MySQL 连接正常 |
| 控制台登录后很快掉线 | accessToken 过期（默认 18000s） | 调整 `NACOS_AUTH_TOKEN_EXPIRE_SECONDS` 或客户端自动重登 |
| MySQL 连接报时区/字符集错误 | 缺少 `serverTimezone`/`characterEncoding` | 已在 `MYSQL_SERVICE_DB_PARAM` 固化，勿删改 |
