"""应用日志：控制台 + 落文件（任务4，本批次默认改全环境 on）。

- 控制台：沿用了原有行为——每个 logger 首次创建时挂一个 stdout StreamHandler。
- 多进程（第 72 批）：LOG_FILE_PER_PROCESS=true 时每个进程写 `app-<pid>.log`，避免多实例
  争用同一个 app.log 的轮转；单文件模式下轮转失败（Windows 文件占用）也不再刷屏，
  而是跳过本次轮转、继续写当前文件（见 _SafeRotatingFileHandler）。
- 文件（任务4）：LOG_TO_FILE=on|off 显式开关；空（默认，本批次变更）→ **全环境 on**
  （开发也落文件，便于 admin 日志健康卡排障），显式 LOG_TO_FILE=off 才关闭。
  启用后所有 logger 共享同一个 RotatingFileHandler 单例（5MB × 5 备份，UTF-8，
  delay=True 首写才建文件）：轮转只由这一个 handler 实例执行，避免多 logger 各持
  handler 对同一文件轮转的 Windows 竞态。目录不可写等初始化失败只降级为纯控制台，
  绝不阻断业务。
"""
import logging
import logging.handlers
import os
import sys
import threading
import time

# 任务4：文件轮转参数（模块级常量，测试可 monkeypatch）
_FILE_MAX_BYTES = 5 * 1024 * 1024  # 5MB
_FILE_BACKUP_COUNT = 5             # 保留 5 个备份（app.log.1 ~ app.log.5）
_FILE_NAME = "app.log"
# 第 72 批：多进程轮转失败后的冷却（秒）。失败时跳过本次轮转、继续写当前文件，
# 冷却结束再试——避免每个写满的批次都往 stderr 打一段 logging 错误堆栈。
_ROTATE_RETRY_S = 60.0

_FMT = logging.Formatter('%(asctime)s [%(levelname)s] %(name)s: %(message)s')

_file_lock = threading.Lock()
_file_handler = None   # RotatingFileHandler 单例（所有 logger 共享）
_file_failed = False   # 初始化失败标记：只降级控制台，不反复重试


def should_log_to_file(raw: str, app_env: str) -> bool:
    """任务4：LOG_TO_FILE 三态解析（纯函数，便于测试）。

    - on/true/1/yes → 强制开；off/false/0/no → 强制关（大小写不敏感）；
    - 空（默认）→ 全环境 on（本批次变更：删除「development 默认控制台」分支，
      开发也落文件，便于 admin 日志健康卡排障）；app_env 参数保留兼容既有
      调用/测试签名，不再参与判定。
    """
    v = str(raw or "").strip().lower()
    if v in ("on", "true", "1", "yes"):
        return True
    if v in ("off", "false", "0", "no"):
        return False
    return True  # 默认全环境落文件；显式 LOG_TO_FILE=off 才关闭


def reset_file_logging() -> None:
    """测试隔离：关闭并清空共享文件 handler 单例（下次 get_logger 按当前配置重建）。"""
    global _file_handler, _file_failed
    with _file_lock:
        if _file_handler is not None:
            try:
                _file_handler.close()
            except Exception:  # noqa: BLE001 —— 关闭失败不影响重置
                pass
        _file_handler = None
        _file_failed = False


class _SafeRotatingFileHandler(logging.handlers.RotatingFileHandler):
    """轮转失败不刷屏：多进程/Windows 文件占用时跳过本次轮转并继续写当前文件。

    背景（第 72 批实测）：多个实例共用 logs/app.log 时，`os.replace` 会因另一进程持有
    句柄抛 `PermissionError: [WinError 32]`；stdlib 把它交给 `logging.handleError`，
    每条触发轮转的日志都会在 stderr 打一段完整堆栈（实测 1000+ 行），而真正的日志
    仍在写，只是没人轮转。这里把失败收敛为：跳过本次轮转 → 重开当前文件继续写 →
    冷却 `_ROTATE_RETRY_S` 后再试；只提示一次，不影响业务。
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.rotate_failures = 0
        self._rotate_retry_at = 0.0

    def doRollover(self):  # noqa: N802 —— 覆盖 stdlib 命名
        if self._rotate_retry_at and time.monotonic() < self._rotate_retry_at:
            return  # 冷却期：继续写当前文件（不轮转、不抛错、不刷屏）
        try:
            super().doRollover()
        except OSError as e:
            self.rotate_failures += 1
            self._rotate_retry_at = time.monotonic() + _ROTATE_RETRY_S
            if self.rotate_failures == 1:
                sys.stderr.write(f"[logger] 日志轮转被跳过（多进程/文件占用）：{e}\n")
        else:
            self.rotate_failures = 0
            self._rotate_retry_at = 0.0
        finally:
            # 失败时 stdlib 已关闭 stream；必须重开，否则本记录写不进去
            if self.stream is None:
                try:
                    self.stream = self._open()
                except OSError:
                    pass  # 磁盘/权限级故障：交给 logging 的默认错误处理


def _per_process_enabled() -> bool:
    try:
        from backend.config import settings
        return bool(getattr(settings, "log_file_per_process", False))
    except Exception:  # noqa: BLE001 —— 配置未就绪时保持单文件（旧行为）
        return False


def _log_file_name() -> str:
    """写入文件名：默认 app.log；LOG_FILE_PER_PROCESS=true 时 app-<pid>.log（多实例不抢锁）。"""
    return f"app-{os.getpid()}.log" if _per_process_enabled() else _FILE_NAME


def _per_process_files(log_dir: str) -> list[str]:
    """log_dir 下的 app-<pid>.log 列表（不含 app.log.N 轮转备份；失败返回空）。"""
    out = []
    try:
        for name in os.listdir(log_dir):
            if name.startswith("app-") and name.endswith(".log"):
                p = os.path.join(log_dir, name)
                if os.path.isfile(p):
                    out.append(p)
    except OSError:
        return []
    return out


def resolve_log_path(log_dir: str) -> str:
    """当前应展示的主日志路径（admin 日志健康卡用，第 72 批）。

    - 单文件模式：app.log；不存在但目录里有 app-<pid>.log（混合/滚动期间）→ 最新一份；
    - 多进程模式：最新一份 app-<pid>.log（各进程只写自己的文件）；
    - 都没有 → 返回 app.log 路径，让调用方按"未启用"提示处理。
    """
    base = os.path.join(log_dir, _FILE_NAME)
    candidates = _per_process_files(log_dir)
    if _per_process_enabled():
        if candidates:
            return max(candidates, key=os.path.getmtime)
        return base
    if os.path.isfile(base) or not candidates:
        return base
    return max(candidates, key=os.path.getmtime)


def _get_file_handler():
    """惰性构建共享 RotatingFileHandler（5MB × 5，UTF-8，delay=True）。
    失败只降级控制台（_file_failed 置位，不再重试），绝不因日志问题阻断业务。"""
    global _file_handler, _file_failed
    if _file_handler is not None or _file_failed:
        return _file_handler
    with _file_lock:
        if _file_handler is not None or _file_failed:  # 双检：并发首访只构建一次
            return _file_handler
        try:
            from backend.config import settings
            log_dir = (settings.log_dir or "logs").strip() or "logs"
            os.makedirs(log_dir, exist_ok=True)
            fh = _SafeRotatingFileHandler(
                os.path.join(log_dir, _log_file_name()), maxBytes=_FILE_MAX_BYTES,
                backupCount=_FILE_BACKUP_COUNT, encoding="utf-8", delay=True)
            fh.setFormatter(_FMT)
            _file_handler = fh
        except Exception:  # noqa: BLE001 —— 目录不可写等：仅控制台，不阻断
            _file_failed = True
            return None
    return _file_handler


class _KwLogger:
    """Thin wrapper so calls like logger.info('msg', k=v) work (structlog-style),
    backed by stdlib logging."""

    def __init__(self, lg: logging.Logger):
        self._lg = lg

    @staticmethod
    def _fmt(msg, kw):
        if kw:
            return str(msg) + ' | ' + ' '.join(f'{k}={v}' for k, v in kw.items())
        return str(msg)

    def debug(self, msg, **kw):
        self._lg.debug(self._fmt(msg, kw))

    def info(self, msg, **kw):
        self._lg.info(self._fmt(msg, kw))

    def warning(self, msg, **kw):
        self._lg.warning(self._fmt(msg, kw))

    def error(self, msg, **kw):
        self._lg.error(self._fmt(msg, kw))


def get_logger(name: str) -> _KwLogger:
    lg = logging.getLogger(name)
    if not lg.handlers:
        h = logging.StreamHandler(sys.stdout)
        h.setFormatter(_FMT)
        lg.addHandler(h)
        # 任务4：按配置附加共享文件 handler（本批次默认全环境 on，显式 off 才关）
        try:
            from backend.config import settings
            if should_log_to_file(settings.log_to_file, settings.app_env):
                fh = _get_file_handler()
                if fh is not None:
                    lg.addHandler(fh)
        except Exception:  # noqa: BLE001 — 配置未就绪时保持纯控制台
            pass
        try:
            from backend.config import settings
            level = getattr(logging, (settings.log_level or "INFO").upper(), logging.INFO)
        except Exception:  # noqa: BLE001 — 配置未就绪时回退 INFO
            level = logging.INFO
        lg.setLevel(level)
    return _KwLogger(lg)
