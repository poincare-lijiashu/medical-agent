-- ============================================================================
-- 临床域 V58：麻醉复苏（PACU）闭环（G80）
--
-- 为什么补这一块：手术域此前覆盖"计划 → 开台 → 术中麻醉/护理 → 完成"，
-- 但术后复苏没有任何系统记录——Aldrete 评分、出室去向只能写在纸质单上，
-- 与评审"手术与麻醉 75%，缺复苏"的缺口一致。
--
-- 两张事实表：
--   * pacu_stay：一台已结束手术一份复苏停留（入室 → 出室 → 签名冻结）；
--   * pacu_observation：Aldrete 五项（各 0–2 分，合计 0–10）+ 生命体征时间序列。
--
-- 口径与边界：
--   * 出室标准（最近一次 Aldrete ≥ 9）由服务层判定，库层只保证"出室必有时间与去向"；
--   * dept_id / campus_id 在入室时从就诊固化，看板据此在 SQL 层收敛（不在内存过滤）；
--   * 签名由触发器兜底冻结（与麻醉记录/术中护理同口径），应用层校验只是提前失败；
--   * 患者一旦出室，观察记录同样不可再追加——复苏单是原始医疗文书。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.pacu_stay (
    id              uuid PRIMARY KEY,
    surgery_case_id uuid NOT NULL UNIQUE REFERENCES clinical_svc.surgery_case (id),
    bed_no          text,
    dept_id         uuid NOT NULL,
    campus_id       uuid NOT NULL,
    admitted_at     timestamptz NOT NULL,
    discharged_at   timestamptz,
    destination     text CHECK (destination IN ('WARD', 'ICU', 'DISCHARGE')),
    status          text NOT NULL DEFAULT 'IN_PACU' CHECK (status IN ('IN_PACU', 'OUT')),
    signed_by       text,
    signed_at       timestamptz,
    created_by      text NOT NULL,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pacu_out CHECK (
        (status = 'OUT' AND discharged_at IS NOT NULL AND destination IS NOT NULL)
        OR status = 'IN_PACU'
    ),
    CONSTRAINT ck_pacu_time CHECK (discharged_at IS NULL OR discharged_at >= admitted_at),
    CONSTRAINT ck_pacu_signed CHECK (
        (signed_by IS NOT NULL AND signed_at IS NOT NULL
         AND status = 'OUT' AND discharged_at IS NOT NULL)
        OR (signed_by IS NULL AND signed_at IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_pacu_stay_board
    ON clinical_svc.pacu_stay (status, admitted_at DESC);
CREATE INDEX IF NOT EXISTS ix_pacu_stay_dept
    ON clinical_svc.pacu_stay (dept_id, admitted_at DESC);
CREATE INDEX IF NOT EXISTS ix_pacu_stay_campus
    ON clinical_svc.pacu_stay (campus_id, admitted_at DESC);

COMMENT ON TABLE clinical_svc.pacu_stay IS
    '麻醉复苏停留：一台已结束手术一份；OUT 后可签名，签名后由触发器冻结';
COMMENT ON COLUMN clinical_svc.pacu_stay.destination IS
    '出室去向：WARD 病区 / ICU / DISCHARGE 离院（由服务层校验合法性）';

CREATE TABLE IF NOT EXISTS clinical_svc.pacu_observation (
    id            uuid PRIMARY KEY,
    stay_id       uuid NOT NULL REFERENCES clinical_svc.pacu_stay (id) ON DELETE CASCADE,
    recorded_at   timestamptz NOT NULL,
    activity      int NOT NULL CHECK (activity BETWEEN 0 AND 2),
    respiration   int NOT NULL CHECK (respiration BETWEEN 0 AND 2),
    circulation   int NOT NULL CHECK (circulation BETWEEN 0 AND 2),
    consciousness int NOT NULL CHECK (consciousness BETWEEN 0 AND 2),
    oxygenation   int NOT NULL CHECK (oxygenation BETWEEN 0 AND 2),
    total         int NOT NULL CHECK (total BETWEEN 0 AND 10),
    heart_rate    int CHECK (heart_rate BETWEEN 0 AND 300),
    sbp           int CHECK (sbp BETWEEN 0 AND 300),
    dbp           int CHECK (dbp BETWEEN 0 AND 200),
    spo2          int CHECK (spo2 BETWEEN 0 AND 100),
    temperature   numeric(3, 1) CHECK (temperature IS NULL OR temperature BETWEEN 25.0 AND 45.0),
    note          text,
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pacu_total CHECK (
        total = activity + respiration + circulation + consciousness + oxygenation
    ),
    CONSTRAINT ck_pacu_bp CHECK (dbp IS NULL OR sbp IS NULL OR sbp >= dbp),
    UNIQUE (stay_id, recorded_at)
);

CREATE INDEX IF NOT EXISTS ix_pacu_observation_stay
    ON clinical_svc.pacu_observation (stay_id, recorded_at);

COMMENT ON TABLE clinical_svc.pacu_observation IS
    '复苏观察：Aldrete 五项（各 0–2 分）与生命体征，按时间点追加；同一时间点仅一条';

-- ---- 冻结边界（触发器兜底，应用层校验只是提前失败）----

CREATE OR REPLACE FUNCTION clinical_svc.guard_pacu_stay() RETURNS trigger AS $$
BEGIN
    IF OLD.signed_at IS NOT NULL THEN
        RAISE EXCEPTION '复苏记录已签署，不能修改' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_pacu_stay ON clinical_svc.pacu_stay;
CREATE TRIGGER trg_guard_pacu_stay
    BEFORE UPDATE ON clinical_svc.pacu_stay
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_pacu_stay();

CREATE OR REPLACE FUNCTION clinical_svc.guard_pacu_observation() RETURNS trigger AS $$
DECLARE
    st       text;
    signed_at timestamptz;
BEGIN
    SELECT s.status, s.signed_at INTO st, signed_at
      FROM clinical_svc.pacu_stay s
     WHERE s.id = COALESCE(NEW.stay_id, OLD.stay_id);
    IF signed_at IS NOT NULL THEN
        RAISE EXCEPTION '复苏记录已签署，不能追加或修改观察记录' USING ERRCODE = 'check_violation';
    END IF;
    IF st IS DISTINCT FROM 'IN_PACU' THEN
        RAISE EXCEPTION '患者已出复苏室，不能追加或修改观察记录' USING ERRCODE = 'check_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_pacu_observation ON clinical_svc.pacu_observation;
CREATE TRIGGER trg_guard_pacu_observation
    BEFORE INSERT OR UPDATE OR DELETE ON clinical_svc.pacu_observation
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_pacu_observation();
