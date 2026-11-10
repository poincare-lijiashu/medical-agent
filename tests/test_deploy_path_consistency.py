"""上线口径一致性（第 213 批）：唯一前端 + 根路径托管。

背景：第 211/212 批把控制台迁到根路径并删除旧前端。URL 结构变了以后，
最容易漏的是部署清单、图表与文档里残留的 `/console`——它们不会让单测变红，
却会让上线后的反代/书签/运维手册指向不存在的路径。

本文件锁住：
1. 运行时清单（Dockerfile / compose / Helm / CI）不得把 `/console` 当应用路径
   （Nacos 的 `/nacos/v1/console/...` 是第三方接口，白名单排除）；
2. 前端构建与注释必须是根路径口径；
3. 部署拓扑图与主文档必须写“唯一前端”，设计/迁移文档必须标注历史存档；
4. 旧前端目录已删除。
"""
from __future__ import annotations

import os
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

RUNTIME_FILES = [
    REPO / "Dockerfile",
    REPO / "docker-compose.yml",
    REPO / "docker-compose.prod.yml",
    REPO / "deploy/helm/medassist-platform/templates/ingress.yaml",
    REPO / "deploy/helm/medassist-platform/values.yaml",
    REPO / "deploy/helm/medassist-platform/values-prod.yaml",
    REPO / "deploy/helm/medassist-platform/templates/deployment-ai-core.yaml",
    REPO / ".github/workflows/ci.yml",
]

_CONSOLE_PATH = re.compile(r"(?<!nacos/v1)/console(?:/|[\"'\\s]|$)")


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_runtime_manifests_have_no_console_prefix():
    offenders = []
    for path in RUNTIME_FILES:
        if not path.is_file():
            continue
        for i, line in enumerate(_read(path).splitlines(), 1):
            if _CONSOLE_PATH.search(line):
                offenders.append(f"{path.relative_to(REPO)}:{i}:{line.strip()}")
    assert not offenders, "运行时清单不得再把 /console 当应用路径：\n" + "\n".join(offenders)


def test_frontend_build_is_root():
    vite = _read(REPO / "frontend-console/vite.config.ts")
    assert "base: '/'" in vite
    assert "/console/" not in vite.split("export default", 1)[0], "构建注释不得再声称子路径托管"
    router = _read(REPO / "frontend-console/src/router/index.ts")
    assert "根路径托管" in router and "/console/orders" not in router


def test_deploy_docs_show_single_frontend():
    deploy = _read(REPO / "deploy/README.md")
    assert "唯一前端" in deploy
    assert "/console 控制台" not in deploy
    readme = _read(REPO / "README.md")
    assert "frontend-console/dist" in readme


def test_historical_docs_marked_archive():
    design = _read(REPO / "docs/DESIGN-SYSTEM.md")
    assert "唯一前端" in design and "第 212 批删除" in design
    guide = _read(REPO / "docs/FRONTEND-SKILL-GUIDE.md")
    assert "唯一前端" in guide
    parity = _read(REPO / "docs/qa/vue_parity_matrix.md")
    assert "历史存档" in parity


def test_legacy_frontend_is_gone():
    assert not (REPO / "frontend-vue").exists(), "旧前端目录必须已删除"
    assert "frontend-vue" not in _read(REPO / "Dockerfile")
