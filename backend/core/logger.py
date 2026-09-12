"""应用日志：控制台 + 落文件（任务4，本批次默认改全环境 on）。

- 控制台：沿用了原有行为——每个 logger 首次创建时挂一个 stdout StreamHandler。
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

# 任务4：文件轮转参数（模块级常量，测试可 monkeypatch）
_FILE_MAX_BYTES = 5 * 1024 * 1024  # 5MB
_FILE_BACKUP_COUNT = 5             # 保留 5 个备份（app.log.1 ~ app.log.5）
_FILE_NAME = "app.log"

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
            fh = logging.handlers.RotatingFileHandler(
                os.path.join(log_dir, _FILE_NAME), maxBytes=_FILE_MAX_BYTES,
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
