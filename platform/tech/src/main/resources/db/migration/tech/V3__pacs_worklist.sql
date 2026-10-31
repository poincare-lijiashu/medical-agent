-- ============================================================================
-- V3：影像（PACS）集成 —— 检查申请与 DICOM Study 的登记/状态回传
--
-- 设计约束：
--   * 一张检查申请只允许登记一次影像（application_id 唯一）；重复登记同一
--     StudyInstanceUID 是幂等操作，不同 UID 视为冲突（防止一次检查拆成两份报告）；
--   * StudyInstanceUID 全局唯一（PACS 侧主键），AccessionNumber 复用申请号；
--   * 状态机 SCHEDULED → ARRIVED → IN_PROGRESS → COMPLETED，任意未完成状态可 CANCELLED；
--   * 影像状态与报告状态解耦：报告仍由医技域出具，PACS 只回传检查进度与影像数量；
--   * 医技项目补充 DICOM Modality（CT/MR/US/CR/DX…），缺省 OT（Other）。
--
-- 与 LIS 入站（V2）同一原则：外部系统只写"结果/状态"，不直接改业务状态机。
-- ============================================================================

CREATE TABLE IF NOT EXISTS tech_svc.pacs_study (
    id                  uuid PRIMARY KEY,
    application_id      uuid NOT NULL UNIQUE REFERENCES tech_svc.exam_application (id),
    study_instance_uid  text NOT NULL UNIQUE,
    accession_number    text NOT NULL UNIQUE,
    modality            text NOT NULL,
    body_part           text,
    status              text NOT NULL DEFAULT 'SCHEDULED'
                        CHECK (status IN ('SCHEDULED', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    series_count        int  NOT NULL DEFAULT 0 CHECK (series_count >= 0),
    image_count         int  NOT NULL DEFAULT 0 CHECK (image_count >= 0),
    viewer_url          text,
    started_at          timestamptz,
    completed_at        timestamptz,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pacs_completed CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL) OR status <> 'COMPLETED'
    )
);

CREATE INDEX IF NOT EXISTS ix_pacs_study_status
    ON tech_svc.pacs_study (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_pacs_study_modality
    ON tech_svc.pacs_study (modality);

COMMENT ON TABLE tech_svc.pacs_study IS 'PACS 影像检查登记（与检查申请一对一；UID/Accession 全局唯一）';

-- 医技项目补充 DICOM Modality：worklist 按设备类型过滤的前提
ALTER TABLE tech_svc.md_exam_item ADD COLUMN IF NOT EXISTS modality text NOT NULL DEFAULT 'OT';
UPDATE tech_svc.md_exam_item SET modality = 'CT'
 WHERE item_code = 'EXM-CT-CHEST' AND modality = 'OT';
