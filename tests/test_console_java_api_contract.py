"""Java（/api/v2）接口契约回归（第 223 批）。

`src/api/index.ts` 的业务接口全部走 Java 网关（BASE=/api/v2）。本批用静态解析
Spring 控制器的 `@RequestMapping` + `@XxxMapping` 组合路径，再按网关路由规则
（`Path=/api/v2/{service}/**` + `StripPrefix=N`）还原出客户端真实路径，
逐条校验前端调用“路径存在且方法受支持”。

为什么静态解析而不是 OpenAPI：Java 侧未强制导出 OpenAPI；测试进程是 Python，
无法内省 Spring 容器。静态解析覆盖本院当前统一写法（字面量路径），已足够拦截
“改名/挪路径/换方法”这类高频漂移。

网关两种前缀写法（见 platform/gateway/src/main/resources/application.yml）：
- `StripPrefix=2`：只剥掉 `api/v2`，服务内路径自带 `/{service}` 前缀
  （clinical/pharmacy/material/tech/emr/insurance/kb）；
- `StripPrefix=3`：额外剥掉服务名，控制器里没有 `/{service}` 前缀
  （auth/empi/audit），契约路径需要按网关补回服务段。

匹配规则：
- 类级前缀 + 方法级路径拼接，再按网关剥离层数补回服务段；
- 前端 `${...}` 与后端 `{...}` 均视为任意一段；
- 只比较路径段，不比较查询参数与请求体；
- 不做反向校验（后端可能有给其它客户端、或网关不对外开放的只读/内部接口）。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
API_INDEX = REPO / "frontend-console" / "src" / "api" / "index.ts"
PLATFORM = REPO / "platform"
GATEWAY_YML = PLATFORM / "gateway" / "src" / "main" / "resources" / "application.yml"

# 网关服务名与承载它的 Maven 模块不一致的例外（审计汇聚由 auth 服务提供）。
SERVICE_OWNER = {"audit": "auth"}

CLASS_MAPPING = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?\{?\s*"([^"]+)"')
METHOD_MAPPING = re.compile(
    r'@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]+)"'
)
# 无参注解 / 只有 consumes|produces 的注解：路径即类级前缀
METHOD_MAPPING_BARE = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b(?!\s*\(\s*(?:value\s*=\s*)?\")")
HTTP = {"Get": "GET", "Post": "POST", "Put": "PUT", "Delete": "DELETE", "Patch": "PATCH"}

# 前端调用：兼容单引号与模板字符串（路径参数大量使用 `${id}`）。
# 泛型段不允许出现括号/分号/引号，避免跨调用误匹配。
FRONTEND_CALL = re.compile(
    r"\b(get|post|put|upload|download|request)(?:<[^();'\"]{0,300}>)?\(\s*[`']([^`']+)[`']"
)
# request() 的方法由调用方 options 决定，静态无法判定 → None 表示不校验方法。
HELPER_METHOD: dict[str, str | None] = {
    "get": "GET",
    "post": "POST",
    "put": "PUT",
    "upload": "POST",
    "download": "GET",
    "request": None,
}


def _gateway_strip() -> dict[str, int]:
    """解析网关路由：{服务名: StripPrefix 层数}。"""
    text = GATEWAY_YML.read_text(encoding="utf-8")
    out: dict[str, int] = {}
    for block in re.split(r"\n\s*-\s+id:\s*", "\n" + text)[1:]:
        path = re.search(r"Path=/api/v2/([A-Za-z0-9_-]+)/\*\*", block)
        strip = re.search(r"StripPrefix=(\d+)", block)
        if path and strip:
            out[path.group(1)] = int(strip.group(1))
    return out


def _service_prefixes(strip: dict[str, int], module: str) -> list[str]:
    """该模块控制器对外的服务前缀；空串表示控制器自身已带 `/{service}`。"""
    prefixes = [
        "" if layers == 2 else f"/{service}"
        for service, layers in strip.items()
        if SERVICE_OWNER.get(service, service) == module
    ]
    return prefixes or [""]


def _java_sources() -> list[tuple[str, Path]]:
    """(模块名, 源文件)：模块名用于匹配网关服务前缀。"""
    out: list[tuple[str, Path]] = []
    for path in PLATFORM.glob("*/src/main/java/**/*.java"):
        text = str(path).replace("\\", "/")
        if "/src/test/" in text:
            continue
        module = path.relative_to(PLATFORM).parts[0]
        out.append((module, path))
    return out


def _java_routes() -> list[tuple[str, set[str]]]:
    strip = _gateway_strip()
    routes: list[tuple[str, set[str]]] = []
    for module, path in _java_sources():
        text = path.read_text(encoding="utf-8", errors="ignore")
        if "@RestController" not in text:
            continue
        cls = CLASS_MAPPING.search(text)
        prefix = cls.group(1) if cls else ""
        pairs: list[tuple[str, str]] = list(METHOD_MAPPING.findall(text))
        for verb in METHOD_MAPPING_BARE.findall(text):
            pairs.append((verb, ""))
        for svc_prefix in _service_prefixes(strip, module):
            for verb, sub in pairs:
                full = "/" + "/".join(
                    s.strip("/") for s in (svc_prefix, prefix, sub) if s.strip("/")
                )
                routes.append((full, {HTTP[verb]}))
    return routes


def _frontend_calls() -> list[tuple[str, str]]:
    src = API_INDEX.read_text(encoding="utf-8")
    return sorted(set(FRONTEND_CALL.findall(src)))


def _segments(path: str) -> list[str]:
    return [s for s in path.strip("/").split("/") if s]


def _is_dynamic(seg: str) -> bool:
    return "${" in seg or (seg.startswith("{") and seg.endswith("}"))


def _matches(front: str, back: str) -> bool:
    fs, bs = _segments(front), _segments(back)
    if len(fs) != len(bs):
        return False
    return all(f == b or _is_dynamic(f) or _is_dynamic(b) for f, b in zip(fs, bs))


def test_java_route_parser_finds_enough_routes():
    routes = _java_routes()
    assert len(routes) >= 500, f"只解析到 {len(routes)} 条 Java 路由，解析器可能失效"
    calls = _frontend_calls()
    assert len(calls) >= 350, f"只解析到 {len(calls)} 个前端调用，解析器可能失效"


def test_gateway_prefix_rules_are_parsed():
    strip = _gateway_strip()
    assert strip.get("auth") == 3, "网关 auth 路由应为 StripPrefix=3"
    assert strip.get("empi") == 3, "网关 empi 路由应为 StripPrefix=3"
    assert strip.get("clinical") == 2, "网关 clinical 路由应为 StripPrefix=2"


def test_frontend_java_paths_exist():
    routes = _java_routes()
    missing: list[str] = []
    for helper, path in _frontend_calls():
        if not any(_matches(path, r) for r, _ in routes):
            missing.append(f"{helper} {path}")
    assert not missing, "以下 /api/v2 调用在 Java 控制器中找不到对应路径：\n" + "\n".join(missing)


def test_frontend_java_methods_match():
    routes = _java_routes()
    problems: list[str] = []
    for helper, path in _frontend_calls():
        expected = HELPER_METHOD[helper]
        if expected is None:
            continue
        methods = {m for r, ms in routes if _matches(path, r) for m in ms}
        if not methods:
            continue  # 路径缺失已由上一个用例报告
        if expected not in methods:
            problems.append(f"{helper} {path} → 期望 {expected}，控制器仅支持 {sorted(methods)}")
    assert not problems, "调用方法与 Java 控制器不一致：\n" + "\n".join(problems)


def test_frontend_java_calls_have_no_gateway_prefix():
    bad = [p for _, p in _frontend_calls() if p.startswith(("/api/", "/console", "/app"))]
    assert not bad, f"api/index.ts 不应自带网关前缀（BASE 已是 /api/v2）：{sorted(set(bad))}"
