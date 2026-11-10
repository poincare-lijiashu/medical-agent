"""可访问性与交互一致性（第 215 批）。

商业系统的键盘/读屏底线（本文件锁死）：

1. 壳层：跳过导航链接指向 `#main`；导航有 aria-label 与当前页 `aria-current`；
2. 用户菜单：`aria-haspopup/expanded`，Down 打开，上下循环，Esc 关闭并归还焦点；
3. 对话框：`role=dialog` + `aria-modal` + 可读标题 + 焦点陷阱 + Esc；
4. 命令面板：同一套对话框语义 + 上下/回车/Esc 键盘操作；
5. 操作反馈：区域与关闭按钮文案走 i18n；错误/警告用 `role=alert`，其余 `status`；
6. 图标默认装饰性（无 label 时 `aria-hidden`），不应被读屏逐个念出。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
SHELL = SRC / "layouts/AppShell.vue"
MODAL = SRC / "components/ui/AppModal.vue"
PALETTE = SRC / "components/ui/CommandPalette.vue"
TOAST = SRC / "components/ui/ToastHost.vue"
ICON = SRC / "components/ui/AppIcon.vue"
LOAD_ERROR = SRC / "components/ui/LoadError.vue"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_shell_skip_link_and_nav_semantics():
    src = _read(SHELL)
    assert 'class="skip-link" href="#main"' in src
    assert 'id="main"' in src
    assert ":aria-label=\"t('shell.mainNav')\"" in src
    assert ":aria-label=\"t('shell.funcNav')\"" in src
    assert ":aria-current=\"route.name === item.name ? 'page' : undefined\"" in src


def test_user_menu_is_keyboard_operable():
    src = _read(SHELL)
    assert 'aria-haspopup="menu"' in src
    assert ":aria-expanded=\"userMenuOpen ? 'true' : 'false'\"" in src
    assert 'role="menu"' in src and 'role="menuitem"' in src
    for key in ("'ArrowDown'", "'ArrowUp'", "'Home'", "'End'", "'Escape'", "'Tab'"):
        assert f"e.key === {key}" in src, f"用户菜单缺键盘分支 {key}"
    assert "userBtn.value?.focus()" in src, "Esc 关闭后必须把焦点归还触发按钮"
    assert "@keydown.down.prevent=\"openUserMenu\"" in src, "Down 应打开菜单并聚焦第一项"


def test_modal_dialog_semantics_and_focus_trap():
    src = _read(MODAL)
    assert 'role="dialog"' in src
    assert 'aria-modal="true"' in src
    assert ':aria-label="title"' in src
    assert "@keydown.esc.stop.prevent" in src, "Esc 必须关闭对话框"
    assert "onTab" in src and "e.preventDefault()" in src, "必须有 Tab 焦点陷阱"
    assert "previousFocus" in src and ".focus()" in src, "关闭后要归还焦点"
    assert "lockScroll" in src and "unlockScroll" in src


def test_command_palette_semantics_and_keys():
    src = _read(PALETTE)
    assert 'role="dialog"' in src and 'aria-modal="true"' in src
    assert ':aria-label="t(\'palette.dialog\')"' in src
    for binding in ("@keydown.down.prevent", "@keydown.up.prevent", "@keydown.enter.prevent", "@keydown.esc.prevent"):
        assert binding in src, f"命令面板缺键盘绑定 {binding}"


def test_toast_is_localized_and_announced():
    src = _read(TOAST)
    assert "aria-live=\"polite\"" in src
    assert ":aria-label=\"t('toast.region')\"" in src
    assert ":aria-label=\"t('toast.close')\"" in src
    assert "操作反馈" not in src and "关闭提示" not in src, "壳层文案必须走 i18n"
    assert "item.kind === 'error' || item.kind === 'warn' ? 'alert' : 'status'" in src


def test_icons_default_decorative_and_error_state_is_alert():
    icon = _read(ICON)
    assert ":aria-hidden=\"label ? undefined : 'true'\"" in icon
    assert ":role=\"label ? 'img' : undefined\"" in icon
    err = _read(LOAD_ERROR)
    assert 'role="alert"' in err, "加载失败必须被读屏播报"
