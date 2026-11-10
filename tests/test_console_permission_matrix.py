"""权限点矩阵一致性回归（第 224 批）。

前端的按钮开关（`hasPermission('a:b:c')`）与导航可见性（`navigation.ts` 的
`permissions: [...]`）用的是一串**手写字符串**：拼错一个字母不会编译报错，
只会让按钮永远不出现（或永远出现），线上极难发现。本批把前端用到的权限点、
前端用到的角色名，与后端权威来源做机器比对：

- 权限点权威来源：`platform/**` Java 字面量 + `platform/auth/**/db/migration/**` 的种子 SQL；
- 角色权威来源：种子 SQL 里 `('ROLE', 'a:b:c')` 形式的授权行。

同时做两条反向检查，防止权限点“写了但没人能拿到”：
- Java 里 `Permissions.require(...)` 用到的权限点必须已被种子 SQL 授权给至少一个角色，
  否则该接口对所有人都 403（除手工 SQL 补授）；
- `Permissions.require` 的权限参数必须能静态解析（字面量或本类常量），
  出现拼接/变量传递会导致本锁漏检，直接报错而不是静默跳过。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
FRONTEND_SRC = REPO / "frontend-console" / "src"
PLATFORM = REPO / "platform"

# 权限点形状：域:资源:动作，允许连字符与下划线分段（如 inpatient:discharge-med:read）。
PERM_CODE = re.compile(r"^[a-z][a-z0-9_-]*(?::[a-z][a-z0-9_-]*){1,3}$")
PERM_LITERAL = re.compile(r'"([a-z][a-z0-9_-]*(?::[a-z][a-z0-9_-]*){1,4})"')
SQL_PERM = re.compile(r"'([a-z][a-z0-9_-]*(?::[a-z][a-z0-9_-]*){1,4})'")
SQL_ROLE_GRANT = re.compile(
    r"\('([A-Z][A-Z0-9_]+)'\s*,\s*'[a-z][a-z0-9_-]*(?::[a-z][a-z0-9_-]*){1,4}'\)"
)

FRONTEND_HAS_PERM = re.compile(r"hasPermission\(\s*'([^']+)'")
FRONTEND_NAV_PERM = re.compile(r"permissions:\s*\[([^\]]*)\]")
FRONTEND_HAS_ROLE = re.compile(r"hasRole\(\s*(\[[^\]]*\]|'[^']+')")

JAVA_REQUIRE = re.compile(r"Permissions\.require\(([^;]*?)\);", re.S)
JAVA_CONST = re.compile(r"private\s+static\s+final\s+String\s+(\w+)\s*=\s*\"([^\"]+)\"")


def _frontend_sources() -> list[Path]:
    return [p for p in FRONTEND_SRC.rglob("*") if p.suffix in {".vue", ".ts"}]


def _frontend_permissions() -> dict[str, set[str]]:
    """{权限点: 出处集合}，出处仅用于报错定位。"""
    out: dict[str, set[str]] = {}
    for path in _frontend_sources():
        text = path.read_text(encoding="utf-8")
        rel = str(path.relative_to(REPO)).replace("\\", "/")
        codes = FRONTEND_HAS_PERM.findall(text)
        for group in FRONTEND_NAV_PERM.findall(text):
            codes.extend(re.findall(r"'([^']+)'", group))
        for code in codes:
            out.setdefault(code, set()).add(rel)
    return out


def _backend_catalog() -> set[str]:
    catalog: set[str] = set()
    for path in PLATFORM.glob("*/src/main/java/**/*.java"):
        catalog.update(PERM_LITERAL.findall(path.read_text(encoding="utf-8", errors="ignore")))
    for path in PLATFORM.rglob("*.sql"):
        catalog.update(SQL_PERM.findall(path.read_text(encoding="utf-8", errors="ignore")))
    return catalog


def _seeded_roles() -> set[str]:
    roles: set[str] = set()
    for path in PLATFORM.rglob("*.sql"):
        roles.update(SQL_ROLE_GRANT.findall(path.read_text(encoding="utf-8", errors="ignore")))
    return roles


def _java_enforced_permissions() -> tuple[set[str], list[str]]:
    """返回 (静态解析出的权限点, 无法静态解析的调用位置)。"""
    used: set[str] = set()
    unresolved: list[str] = []
    for path in PLATFORM.glob("*/src/main/java/**/*.java"):
        text = path.read_text(encoding="utf-8", errors="ignore")
        consts = dict(JAVA_CONST.findall(text))
        for args in JAVA_REQUIRE.findall(text):
            parts = [p.strip() for p in args.split(",", 1)]
            arg = parts[1].strip() if len(parts) > 1 else ""
            if arg.startswith('"') and arg.endswith('"'):
                used.add(arg.strip('"'))
            elif arg in consts:
                used.add(consts[arg])
            else:
                rel = str(path.relative_to(REPO)).replace("\\", "/")
                unresolved.append(f"{rel}: Permissions.require(..., {arg or '<空>'})")
    return used, unresolved


def test_frontend_permission_codes_declared():
    declared = _backend_catalog()
    frontend = _frontend_permissions()
    assert len(declared) >= 100, f"后端权限点目录只解析到 {len(declared)} 条，解析器可能失效"
    assert len(frontend) >= 100, f"前端权限点只解析到 {len(frontend)} 条，解析器可能失效"
    unknown: list[str] = []
    for code, places in sorted(frontend.items()):
        if code not in declared:
            unknown.append(f"{code} ← {', '.join(sorted(places))}")
    assert not unknown, "前端引用了后端不存在的权限点：\n" + "\n".join(unknown)


def test_permission_code_format():
    bad = [c for c in _frontend_permissions() if not PERM_CODE.match(c)]
    assert not bad, f"权限点命名不符合 域:资源:动作（小写、连字符）：{sorted(bad)}"


def test_frontend_roles_exist_in_role_seed():
    seeded = _seeded_roles()
    assert len(seeded) >= 8, f"角色种子只解析到 {len(seeded)} 个，解析器可能失效"
    used: set[str] = set()
    for path in _frontend_sources():
        for group in FRONTEND_HAS_ROLE.findall(path.read_text(encoding="utf-8")):
            used.update(re.findall(r"'([^']+)'", group))
    assert used, "未解析到任何前端角色判断，解析器可能失效"
    missing = sorted(used - seeded)
    assert not missing, f"前端引用了未在角色种子中授权的角色：{missing}"


def test_java_enforced_permissions_are_seeded():
    used, _ = _java_enforced_permissions()
    seeded = _backend_catalog()
    assert len(used) >= 100, f"只解析到 {len(used)} 个 Java 权限点，解析器可能失效"
    unreachable = sorted(used - seeded)
    assert not unreachable, (
        "以下权限点在后端代码中校验、但种子 SQL 未授权给任何角色（接口对所有人 403）：\n"
        + "\n".join(unreachable)
    )


def test_java_require_calls_are_statically_resolvable():
    _, unresolved = _java_enforced_permissions()
    assert not unresolved, (
        "以下 Permissions.require 的权限参数无法静态解析（拼接/变量），本锁会漏检：\n"
        + "\n".join(unresolved)
    )
