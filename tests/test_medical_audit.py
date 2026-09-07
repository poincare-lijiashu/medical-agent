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
