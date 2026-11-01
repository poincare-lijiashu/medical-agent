-- ============================================================================
-- 临床域 V16：手术麻醉第二批（手术间 / 排台 / 麻醉记录与术中生命体征 / 术中护理）
--
-- 第一批已交付"手术事实 + 编码"（V15）。本批补齐真正上线要用的三件事：
--   1. operating_room + surgery_schedule：手术间与排台（接台顺序、时间冲突、取消留原因）
--   2. anesthesia_record + anesthesia_vital：麻醉记录单与术中生命体征（签署后不可改）
--   3. intraop_nursing：术中护理记录（洗手/巡回、体位、止血带、标本数）
--
-- 并发与一致性：
--   * 同一手术间的时间段不得重叠。应用层用 pg_advisory_xact_lock(roomId) 串行化同房间的排台，
--     数据库再用唯一索引挡住"同一房间同一开始时间"的最常见冲突；
--     跨进程的并发窗口由 advisory lock 关闭（单键按 room 维度，正是串行化的目标）。
--   * 麻醉/护理记录签署后由触发器冻结：追加生命体征、修改记录一律拒绝（医疗文书不可篡改）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.operating_room (
    id         uuid PRIMARY KEY,
    code       text NOT NULL UNIQUE,
    name       text NOT NULL,
    dept_id    uuid,
    location   text,
    status     text NOT NULL DEFAULT 'ACTIVE'
               CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE')),
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE clinical_svc.operating_room IS
    '手术间：排台的最小资源单位；MAINTENANCE/INACTIVE 不参与新排台';

CREATE TABLE IF NOT EXISTS clinical_svc.surgery_schedule (
    id                uuid PRIMARY KEY,
    surgery_case_id   uuid NOT NULL REFERENCES clinical_svc.surgery_case (id),
    room_id           uuid NOT NULL REFERENCES clinical_svc.operating_room (id),
    planned_start     timestamptz NOT NULL,
    planned_end       timestamptz NOT NULL,
    sequence          int NOT NULL,
    status            text NOT NULL DEFAULT 'SCHEDULED'
                      CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    cancel_reason     text,
    created_by        text NOT NULL,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_schedule_time CHECK (planned_end > planned_start),
    CONSTRAINT ck_schedule_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_schedule_cancel CHECK (
        (status = 'CANCELLED' AND cancel_reason IS NOT NULL) OR status <> 'CANCELLED'
    )
);

-- 一台手术同时只能有一个未取消的排台（取消后可重排）
CREATE UNIQUE INDEX IF NOT EXISTS ux_schedule_case_active
    ON clinical_svc.surgery_schedule (surgery_case_id)
    WHERE status <> 'CANCELLED';

-- 同一房间同一开始时间只能有一台（重叠由服务层 advisory lock + 校验负责）
CREATE UNIQUE INDEX IF NOT EXISTS ux_schedule_room_start
    ON clinical_svc.surgery_schedule (room_id, planned_start)
    WHERE status <> 'CANCELLED';

CREATE INDEX IF NOT EXISTS ix_schedule_room_time
    ON clinical_svc.surgery_schedule (room_id, planned_start);

COMMENT ON TABLE clinical_svc.surgery_schedule IS
    '排台：一台手术一条未取消记录；sequence 是当天该手术间的接台顺序';

CREATE TABLE IF NOT EXISTS clinical_svc.anesthesia_record (
    id              uuid PRIMARY KEY,
    surgery_case_id uuid NOT NULL UNIQUE REFERENCES clinical_svc.surgery_case (id),
    method          text CHECK (method IN ('GENERAL', 'REGIONAL', 'LOCAL', 'SEDATION', 'NONE')),
    asa_grade       text CHECK (asa_grade IN ('I', 'II', 'III', 'IV', 'V', 'VI')),
    anesthesiologist text,
    airway          text CHECK (airway IN ('ETT', 'LMA', 'MASK', 'NONE')),
    started_at      timestamptz,
    ended_at        timestamptz,
    notes           text,
    status          text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SIGNED')),
    signed_by       text,
    signed_at       timestamptz,
    created_by      text NOT NULL,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_anesthesia_time CHECK (
        ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at
    ),
    CONSTRAINT ck_anesthesia_signed CHECK (
        (status = 'SIGNED' AND signed_by IS NOT NULL AND signed_at IS NOT NULL)
        OR status <> 'SIGNED'
    )
);

COMMENT ON TABLE clinical_svc.anesthesia_record IS
    '麻醉记录单：一台手术一份；SIGNED 后由触发器冻结，不可再改';

CREATE TABLE IF NOT EXISTS clinical_svc.anesthesia_vital (
    id          uuid PRIMARY KEY,
    record_id   uuid NOT NULL REFERENCES clinical_svc.anesthesia_record (id) ON DELETE CASCADE,
    recorded_at timestamptz NOT NULL,
    heart_rate  int CHECK (heart_rate BETWEEN 0 AND 300),
    sbp         int CHECK (sbp BETWEEN 0 AND 300),
    dbp         int CHECK (dbp BETWEEN 0 AND 200),
    spo2        int CHECK (spo2 BETWEEN 0 AND 100),
    etco2       numeric(4, 1) CHECK (etco2 IS NULL OR etco2 BETWEEN 0 AND 99.9),
    temperature numeric(3, 1) CHECK (temperature IS NULL OR temperature BETWEEN 25.0 AND 45.0),
    note        text,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (record_id, recorded_at),
    CONSTRAINT ck_vital_bp CHECK (dbp IS NULL OR sbp IS NULL OR sbp >= dbp)
);

CREATE INDEX IF NOT EXISTS ix_anesthesia_vital_record
    ON clinical_svc.anesthesia_vital (record_id, recorded_at);

COMMENT ON TABLE clinical_svc.anesthesia_vital IS
    '术中生命体征：按时间点追加，同一时间点只能一条；麻醉记录签署后禁止追加';

CREATE TABLE IF NOT EXISTS clinical_svc.intraop_nursing (
    id                 uuid PRIMARY KEY,
    surgery_case_id    uuid NOT NULL UNIQUE REFERENCES clinical_svc.surgery_case (id),
    scrub_nurse        text,
    circulating_nurse  text,
    patient_position   text CHECK (patient_position IN
                           ('SUPINE', 'PRONE', 'LATERAL', 'SITTING', 'LITHOTOMY', 'OTHER')),
    skin_prep          text,
    tourniquet_start   timestamptz,
    tourniquet_end     timestamptz,
    specimen_count     int NOT NULL DEFAULT 0 CHECK (specimen_count >= 0),
    notes              text,
    status             text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SIGNED')),
    signed_by          text,
    signed_at          timestamptz,
    created_by         text NOT NULL,
    version            bigint NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_nursing_tourniquet CHECK (
        tourniquet_end IS NULL OR tourniquet_start IS NULL OR tourniquet_end >= tourniquet_start
    ),
    CONSTRAINT ck_nursing_signed CHECK (
        (status = 'SIGNED' AND signed_by IS NOT NULL AND signed_at IS NOT NULL)
        OR status <> 'SIGNED'
    )
);

COMMENT ON TABLE clinical_svc.intraop_nursing IS
    '术中护理记录：洗手/巡回护士、体位、皮肤准备、止血带与标本；SIGNED 后冻结';

-- ---- 签署后冻结（触发器兜底，应用层校验只是提前失败）----

CREATE OR REPLACE FUNCTION clinical_svc.guard_anesthesia_record() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'SIGNED' THEN
        RAISE EXCEPTION '麻醉记录已签署，不能修改' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_anesthesia_record ON clinical_svc.anesthesia_record;
CREATE TRIGGER trg_guard_anesthesia_record
    BEFORE UPDATE ON clinical_svc.anesthesia_record
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_anesthesia_record();

CREATE OR REPLACE FUNCTION clinical_svc.guard_anesthesia_vital() RETURNS trigger AS $$
DECLARE
    st text;
BEGIN
    SELECT status INTO st FROM clinical_svc.anesthesia_record WHERE id = NEW.record_id;
    IF st = 'SIGNED' THEN
        RAISE EXCEPTION '麻醉记录已签署，不能追加生命体征' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_anesthesia_vital ON clinical_svc.anesthesia_vital;
CREATE TRIGGER trg_guard_anesthesia_vital
    BEFORE INSERT ON clinical_svc.anesthesia_vital
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_anesthesia_vital();

CREATE OR REPLACE FUNCTION clinical_svc.guard_intraop_nursing() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'SIGNED' THEN
        RAISE EXCEPTION '术中护理记录已签署，不能修改' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_intraop_nursing ON clinical_svc.intraop_nursing;
CREATE TRIGGER trg_guard_intraop_nursing
    BEFORE UPDATE ON clinical_svc.intraop_nursing
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_intraop_nursing();
