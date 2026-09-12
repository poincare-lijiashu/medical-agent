"""任务4：应用日志落文件 + 轮转——LOG_TO_FILE 三态开关 + LOG_DIR + RotatingFileHandler（5MB×5）。

- 开关矩阵（本批次变更）：默认（空）**全环境 on**（开发也落文件）；LOG_TO_FILE=off
  显式关闭（保留关通道）；LOG_TO_FILE=on 显式开启（幂等）；
- get_logger 在开关开启时给 logger 附加共享 RotatingFileHandler，关闭时不附加；
- 轮转：写入超限产生 app.log.N 备份。

测试结束 reset_file_logging()，不污染其它测试的 handler 状态。
"""
import logging
import logging.handlers

import pytest

from backend.config import settings
from backend.core import logger as logger_mod


@pytest.fixture(autouse=True)
def _reset_file_logging():
    logger_mod.reset_file_logging()
    yield
    logger_mod.reset_file_logging()


def test_should_log_to_file_env_matrix():
    f = logger_mod.should_log_to_file
    # 默认（空）：全环境 on（本批次变更——开发也落文件，显式 off 才关）
    assert f("", "production") is True
    assert f("", "development") is True
    assert f("", "") is True
    # 显式覆盖优先于默认：off 一票关闭（保留关通道）
    assert f("off", "development") is False
    assert f("off", "production") is False
    # 显式 on 幂等
    assert f("on", "development") is True
    # 大小写/别名宽容
    assert f("ON", "") is True
    assert f("True", "") is True
    assert f("1", "development") is True
    assert f("OFF", "") is False
    assert f("0", "production") is False
    assert f("no", "production") is False


def test_get_logger_console_only_when_disabled(monkeypatch, tmp_path):
    """显式 LOG_TO_FILE=off：不落文件，保持纯控制台（默认 on 的显式关闭通道保留）。"""
    monkeypatch.setattr(settings, "log_to_file", "off")
    monkeypatch.setattr(settings, "app_env", "development")
    name = "t-log-console-only"
    logger_mod.get_logger(name)
    handlers = logging.getLogger(name).handlers
    assert not any(isinstance(h, logging.handlers.RotatingFileHandler) for h in handlers)


def test_get_logger_dev_default_writes_file(monkeypatch, tmp_path):
    """本批次变更：开发环境默认（LOG_TO_FILE 空）也落文件（全环境 on，显式 off 才关）。"""
    monkeypatch.setattr(settings, "log_to_file", "")
    monkeypatch.setattr(settings, "app_env", "development")
    monkeypatch.setattr(settings, "log_dir", str(tmp_path / "logs"))
    name = "t-log-dev-default"
    lg = logger_mod.get_logger(name)
    lg.info("开发环境默认落盘", k=1)
    rots = [h for h in logging.getLogger(name).handlers
            if isinstance(h, logging.handlers.RotatingFileHandler)]
    assert len(rots) == 1, "dev 默认也应附加恰好一个 RotatingFileHandler"
    rots[0].flush()
    assert (tmp_path / "logs" / "app.log").exists()
    assert "开发环境默认落盘" in (tmp_path / "logs" / "app.log").read_text(encoding="utf-8")


def test_get_logger_attaches_rotating_file_handler(monkeypatch, tmp_path):
    """开关开启：logger 附加共享 RotatingFileHandler（5MB×5），文件写入生效。"""
    monkeypatch.setattr(settings, "log_to_file", "on")
    monkeypatch.setattr(settings, "log_dir", str(tmp_path / "logs"))
    name = "t-log-file-on"
    lg = logger_mod.get_logger(name)
    lg.info("文件日志落盘测试", k=1)
    rots = [h for h in logging.getLogger(name).handlers
            if isinstance(h, logging.handlers.RotatingFileHandler)]
    assert len(rots) == 1, "应附加恰好一个 RotatingFileHandler"
    fh = rots[0]
    assert fh.maxBytes == 5 * 1024 * 1024 and fh.backupCount == 5
    fh.flush()
    assert (tmp_path / "logs" / "app.log").exists()
    assert "文件日志落盘测试" in (tmp_path / "logs" / "app.log").read_text(encoding="utf-8")


def test_file_handler_shared_singleton(monkeypatch, tmp_path):
    """多个 logger 共享同一 RotatingFileHandler 实例（轮转只由它执行，防 Windows 竞态）。"""
    monkeypatch.setattr(settings, "log_to_file", "on")
    monkeypatch.setattr(settings, "log_dir", str(tmp_path / "logs"))
    logger_mod.get_logger("t-log-share-a")
    logger_mod.get_logger("t-log-share-b")
    a = [h for h in logging.getLogger("t-log-share-a").handlers
         if isinstance(h, logging.handlers.RotatingFileHandler)]
    b = [h for h in logging.getLogger("t-log-share-b").handlers
         if isinstance(h, logging.handlers.RotatingFileHandler)]
    assert a and b and a[0] is b[0]


def test_file_rotation_creates_backups(monkeypatch, tmp_path):
    """超限轮转：monkeypatch 上限为小值，写入超过阈值产生 app.log.1 等备份。"""
    monkeypatch.setattr(settings, "log_to_file", "on")
    monkeypatch.setattr(settings, "log_dir", str(tmp_path / "logs"))
    monkeypatch.setattr(logger_mod, "_FILE_MAX_BYTES", 600)  # 构造时读取，写小值触发轮转
    lg = logger_mod.get_logger("t-log-rotate")
    for i in range(12):
        lg.info("x" * 200, n=i)
    for h in logging.getLogger("t-log-rotate").handlers:
        if isinstance(h, logging.handlers.RotatingFileHandler):
            h.flush()
    logdir = tmp_path / "logs"
    assert (logdir / "app.log").exists()
    backups = [p for p in logdir.iterdir() if p.name.startswith("app.log.")]
    assert backups, "超限应产生轮转备份文件"


def test_log_dir_unwritable_degrades_to_console(monkeypatch, tmp_path):
    """目录不可写：初始化失败只降级控制台（不抛异常、不阻断业务）。"""
    monkeypatch.setattr(settings, "log_to_file", "on")
    # 把 log_dir 指到一个文件路径上，makedirs 必然失败
    blocker = tmp_path / "not-a-dir"
    blocker.write_text("占位", encoding="utf-8")
    monkeypatch.setattr(settings, "log_dir", str(blocker))
    name = "t-log-degrade"
    lg = logger_mod.get_logger(name)  # 不应抛异常
    lg.info("降级也应可用")
    handlers = logging.getLogger(name).handlers
    assert not any(isinstance(h, logging.handlers.RotatingFileHandler) for h in handlers)
