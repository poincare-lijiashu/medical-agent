import logging
import sys


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
        h.setFormatter(logging.Formatter('%(asctime)s [%(levelname)s] %(name)s: %(message)s'))
        lg.addHandler(h)
        try:
            from backend.config import settings
            level = getattr(logging, (settings.log_level or "INFO").upper(), logging.INFO)
        except Exception:  # noqa: BLE001 — 配置未就绪时回退 INFO
            level = logging.INFO
        lg.setLevel(level)
    return _KwLogger(lg)
