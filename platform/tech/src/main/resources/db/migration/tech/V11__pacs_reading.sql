-- ============================================================================
-- V11：影像阅片与 DICOMweb 调阅（G5）
--
-- 补齐"检查做完之后的下一步"：
--   1. pacs_series       —— PACS QIDO-RS 返回的序列元数据缓存。PACS 断连时阅片台
--                           仍能展示"这个检查有哪些序列"，不至于整页空白；
--   2. pacs_reading_task —— 阅片领取/释放/完成。同一检查同时只允许一位医师持有
--                           （部分唯一索引），释放后保留历史，可再次领取；
--   3. pacs_access_log   —— 谁在何时调阅了哪个检查/序列/影像。追加式表 + 库级
--                           触发器，满足"影像调阅可追溯"的合规要求。
--
-- 边界：本服务不存 DICOM 像素，影像流由 DICOMweb（WADO-RS）实时回源；
-- 这里只缓存元数据（序列/影像数量），避免把 PACS 变成 HIS 的存储负担。
-- ============================================================================

-- ---------------------------------------------------------------- 序列缓存
CREATE TABLE IF NOT EXISTS tech_svc.pacs_series (
    id                  uuid PRIMARY KEY,
    study_id            uuid NOT NULL REFERENCES tech_svc.pacs_study (id),
    series_instance_uid text NOT NULL,
    series_number       int,
    modality            text NOT NULL,
    body_part           text,
    description         text,
    instance_count      int NOT NULL DEFAULT 0 CHECK (instance_count >= 0),
    synced_at           timestamptz NOT NULL DEFAULT now(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_pacs_series_uid UNIQUE (series_instance_uid)
);

CREATE INDEX IF NOT EXISTS ix_pacs_series_study
    ON tech_svc.pacs_series (study_id, series_number);

COMMENT ON TABLE tech_svc.pacs_series IS
    'DICOM 序列元数据缓存（来源 PACS QIDO-RS；影像像素不入库）';

-- ---------------------------------------------------------------- 阅片任务
CREATE TABLE IF NOT EXISTS tech_svc.pacs_reading_task (
    id           uuid PRIMARY KEY,
    study_id     uuid NOT NULL REFERENCES tech_svc.pacs_study (id),
    assignee     text NOT NULL,
    status       text NOT NULL DEFAULT 'CLAIMED'
                 CHECK (status IN ('CLAIMED', 'RELEASED', 'COMPLETED')),
    claimed_at   timestamptz NOT NULL DEFAULT now(),
    released_at  timestamptz,
    completed_at timestamptz,
    note         text,
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pacs_reading_released CHECK (
        (status = 'RELEASED' AND released_at IS NOT NULL) OR status <> 'RELEASED'
    ),
    CONSTRAINT ck_pacs_reading_completed CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL) OR status <> 'COMPLETED'
    )
);

-- 同一检查同时只允许一位医师持有阅片任务；释放/完成后可再次领取（历史保留）
CREATE UNIQUE INDEX IF NOT EXISTS ux_pacs_reading_active
    ON tech_svc.pacs_reading_task (study_id)
    WHERE status = 'CLAIMED';

CREATE INDEX IF NOT EXISTS ix_pacs_reading_assignee
    ON tech_svc.pacs_reading_task (assignee, status, claimed_at DESC);

COMMENT ON TABLE tech_svc.pacs_reading_task IS
    '影像阅片任务（领取/释放/完成；同一检查同时仅一位医师持有）';

-- ---------------------------------------------------------------- 调阅审计
CREATE TABLE IF NOT EXISTS tech_svc.pacs_access_log (
    id                  uuid PRIMARY KEY,
    study_id            uuid NOT NULL REFERENCES tech_svc.pacs_study (id),
    study_instance_uid  text NOT NULL,
    accession_number    text NOT NULL,
    actor               text NOT NULL,
    action              text NOT NULL
                        CHECK (action IN ('VIEW_STUDY', 'VIEW_SERIES', 'FETCH_IMAGE')),
    series_instance_uid text,
    sop_instance_uid    text,
    detail              text,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_pacs_access_study
    ON tech_svc.pacs_access_log (study_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_pacs_access_actor
    ON tech_svc.pacs_access_log (actor, created_at DESC);

DROP TRIGGER IF EXISTS trg_pacs_access_append_only ON tech_svc.pacs_access_log;
CREATE TRIGGER trg_pacs_access_append_only
    BEFORE UPDATE OR DELETE ON tech_svc.pacs_access_log
    FOR EACH ROW EXECUTE FUNCTION tech_svc.reject_audit_mutation();

COMMENT ON TABLE tech_svc.pacs_access_log IS
    '影像调阅审计（只允许 INSERT：谁在何时调阅了哪个检查/序列/影像）';
