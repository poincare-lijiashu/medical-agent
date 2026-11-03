-- P1-D4：V2 里的 "ADD COLUMN IF NOT EXISTS ... CHECK (...)" 是**单条语句**。
-- 当列已存在（重跑、或从别的分支补环境）时 PostgreSQL 整条跳过，CHECK 不会被补上，
-- 于是同一套迁移在不同环境产出不同约束，且没有任何报错。
--
-- 这里用幂等 DO 块单独补约束：
--   1) 若列已由 V2 内联约束（PostgreSQL 自动命名 prescription_billing_mode_check）建好，
--      先重命名为稳定名字，避免重复约束；
--   2) 仍不存在则显式 ADD CONSTRAINT。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conrelid = 'pharmacy_svc.prescription'::regclass
                  AND conname = 'prescription_billing_mode_check') THEN
        ALTER TABLE pharmacy_svc.prescription
            RENAME CONSTRAINT prescription_billing_mode_check TO ck_prescription_billing_mode;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'pharmacy_svc.prescription'::regclass
                      AND conname = 'ck_prescription_billing_mode') THEN
        ALTER TABLE pharmacy_svc.prescription
            ADD CONSTRAINT ck_prescription_billing_mode
            CHECK (billing_mode IN ('ORDER', 'DISPENSE'));
    END IF;

    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conrelid = 'pharmacy_svc.dispense'::regclass
                  AND conname = 'dispense_billing_status_check') THEN
        ALTER TABLE pharmacy_svc.dispense
            RENAME CONSTRAINT dispense_billing_status_check TO ck_dispense_billing_status;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'pharmacy_svc.dispense'::regclass
                      AND conname = 'ck_dispense_billing_status') THEN
        ALTER TABLE pharmacy_svc.dispense
            ADD CONSTRAINT ck_dispense_billing_status
            CHECK (billing_status IN ('NONE', 'PENDING', 'CHARGED', 'CHARGE_FAILED',
                                      'REFUND_PENDING', 'REFUND_FAILED'));
    END IF;
END $$;
