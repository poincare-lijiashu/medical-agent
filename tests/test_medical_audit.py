from backend.core.medical_audit import PHIRedactor, AuditLog


def test_redacts_phone():
    out = PHIRedactor().redact("Call 13800001234 today")
    assert '13800001234' not in out
    assert '[REDACTED-PHONE]' in out


def test_redacts_email_and_idcard():
    out = PHIRedactor().redact('张三 510105199001011234 zhang@example.com')
    assert '510105199001011234' not in out
    assert 'zhang@example.com' not in out


def test_redacts_chinese_name_contextual():
    r = PHIRedactor()
    # 标签锚定
    assert '王建国' not in r.redact("姓名：王建国，男，45 岁")
    # 患者+人名+性别形态特征
    assert '李淑芬' not in r.redact("患者李淑芬，女，62 岁，主诉头晕。")
    # 不会误伤：孤立两字词（症状/普通词）保持原样
    keep = r.redact("患者胃疼两天，建议胃镜检查。")
    assert "胃疼" in keep and "胃镜" in keep


def test_redacts_chinese_address():
    r = PHIRedactor()
    out1 = r.redact("家庭住址：北京市朝阳区建国路88号院3栋502室")
    assert "建国路" not in out1 and "502" not in out1
    out2 = r.redact("患者来自上海市人民路10号附近社区")
    assert "人民路" not in out2


def test_audit_log_is_append_only(tmp_path):
    # 用隔离临时文件：AuditLog 启动会从 JSONL 尾部回填，测试不应依赖真实历史
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    log.record('a', 'b', {'k': 'v'})
    log.record('a', 'c', {'k2': 'v2'})
    assert len(log.entries) == 2
    assert not hasattr(log, 'delete')
    assert not hasattr(log, 'update')


# ---- A3 审计签名显式化 ----

def _fresh_log(tmp_path):
    return AuditLog(path=str(tmp_path / "audit.jsonl"))


def test_write_explicit_kwargs(tmp_path):
    log = _fresh_log(tmp_path)
    log.write(event_type="x", action="y", actor="z", payload={})
    e = log.entries[-1]
    assert e["event_type"] == "x"
    assert e["actor"] == "z"
    assert e["payload"].get("action") == "y"


def test_write_legacy_three_positional_compat(tmp_path):
    """旧式 3-str write("mod","act","user") 仍归一为 event=mod/action=act/actor=user。"""
    log = _fresh_log(tmp_path)
    log.write("mod", "act", "user")
    e = log.entries[-1]
    assert e["event_type"] == "mod"
    assert e["actor"] == "user"
    assert e["payload"].get("action") == "act"


def test_write_legacy_dict_payload_compat(tmp_path):
    """旧 (event_type, action, payload) 形态（dict 落到 actor 位）：归一回 payload，actor=system。"""
    log = _fresh_log(tmp_path)
    log.write("literature", "query", {"q": "二甲双胍"})
    e = log.entries[-1]
    assert e["event_type"] == "literature"
    assert e["actor"] == "system"
    assert e["payload"].get("action") == "query"
    assert e["payload"].get("q") == "二甲双胍"


def test_write_legacy_two_str_username_compat(tmp_path):
    """旧 2-str (event_type, actor)：第二参像用户名（含数字）→ 归一为 actor。"""
    log = _fresh_log(tmp_path)
    log.write("literature", "doctor01")
    e = log.entries[-1]
    assert e["event_type"] == "literature"
    assert e["actor"] == "doctor01"
    assert e["payload"].get("action", "") == ""


def test_write_two_str_action_shape_normalized(tmp_path):
    """2-str 但第二参像动作词（不含数字）→ 归一为 action、actor=system（修复旧 actor="query" 错位）。"""
    log = _fresh_log(tmp_path)
    log.write("literature", "query")
    e = log.entries[-1]
    assert e["event_type"] == "literature"
    assert e["actor"] == "system"
    assert e["payload"].get("action") == "query"


def test_write_suspicious_swapped_args_warns(tmp_path, caplog):
    """防御：首参像 actor（含数字用户名）而次参像 action 文案（含空格/非用户名形态）→ 记 warning 不阻断。

    这类调用是历史错位记录（actor="query"/"scaffold" 等）的典型来源，宽容兼容同时告警便于排查。"""
    import logging
    log = _fresh_log(tmp_path)
    with caplog.at_level(logging.WARNING, logger="backend.core.medical_audit"):
        log.write("doctor01", "查询了三份病历记录")  # 疑似 (actor, action文案) 反向旧调用
    e = log.entries[-1]
    assert e["event_type"] == "doctor01"  # 宽容兼容：仍落盘不抛错
    assert "suspicious_args" in caplog.text  # 已记 warning


def test_write_normal_calls_do_not_warn(tmp_path, caplog):
    """正常形态（模块名/动作业/用户名位）不得误报 warning。"""
    import logging
    log = _fresh_log(tmp_path)
    with caplog.at_level(logging.WARNING, logger="backend.core.medical_audit"):
        log.write("literature", "query", "doctor01", {"q": "二甲双胍"})
        log.write(event_type="drug", action="answered", actor="doctor01", payload={})
        log.write("literature", "doctor01")  # 旧 2-str (event_type, actor)
    assert "suspicious_args" not in caplog.text
