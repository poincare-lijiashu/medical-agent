-- ============================================================================
-- 知识库域 V2：文档解析增强 + 抽取式回答审计
--
-- 设计要点：
--   1. 来源类型从 4 种扩到 7 种：新增 HTML / MARKDOWN / TEXT。
--      解析在写入时一次完成（不是检索时临时清洗），保证切片偏移、去重哈希、
--      引用回指三者都基于**同一份规范化正文**，否则前端高亮会对不上原文。
--   2. raw_char_count 记录"解析前"的字符数，与 char_count（解析后）形成对照：
--      运营能直接看出 HTML 清洗掉了多少标签噪声，不需要翻日志。
--   3. parse_notes 是**解析告警**，与 error_message（索引告警）分开：
--      一个是"内容被清洗过"，一个是"语义检索不可用"，混在一列会互相覆盖。
--   4. kb_answer_log 记录抽取式回答的每次调用：是否命中证据、引用条数、时延、
--      top 分数。没有证据就拒答（grounded=false），这条审计是"拒答率"的来源。
-- ============================================================================

-- 1. 来源类型扩容（原内联 CHECK 的自动名称为 kb_document_source_type_check）
ALTER TABLE kb_svc.kb_document DROP CONSTRAINT IF EXISTS kb_document_source_type_check;
ALTER TABLE kb_svc.kb_document
    ADD CONSTRAINT ck_kb_document_source_type
    CHECK (source_type IN ('MANUAL', 'UPLOAD', 'URL', 'IMPORT', 'HTML', 'MARKDOWN', 'TEXT'));

-- 2. 解析观测列
ALTER TABLE kb_svc.kb_document ADD COLUMN raw_char_count int NOT NULL DEFAULT 0;
ALTER TABLE kb_svc.kb_document ADD COLUMN parse_notes text;

-- 历史文档没有原始输入，按"解析前后一致"回填
UPDATE kb_svc.kb_document SET raw_char_count = char_count WHERE raw_char_count = 0;

ALTER TABLE kb_svc.kb_document
    ADD CONSTRAINT ck_kb_document_raw_chars CHECK (raw_char_count >= 0);

COMMENT ON COLUMN kb_svc.kb_document.raw_char_count IS
    '解析前原始输入字符数；与 char_count 的差值即清洗掉的噪声量';
COMMENT ON COLUMN kb_svc.kb_document.parse_notes IS
    '解析告警（如 HTML 去标签、实体解码、Markdown 去语法）；与索引告警 error_message 分开';

-- 3. 抽取式回答审计
CREATE TABLE kb_svc.kb_answer_log (
    id             bigserial PRIMARY KEY,
    space_id       uuid NOT NULL,
    actor          text NOT NULL,
    question       text NOT NULL,
    grounded       boolean NOT NULL,
    refused_reason text,
    cited_count    int NOT NULL,
    latency_ms     int NOT NULL,
    top_score      numeric(8, 6),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_kb_answer_log_space ON kb_svc.kb_answer_log (space_id, created_at DESC);

COMMENT ON TABLE kb_svc.kb_answer_log IS
    '抽取式回答审计：grounded=false 表示无证据拒答（系统不生成无依据内容），用于统计拒答率与时延';
COMMENT ON COLUMN kb_svc.kb_answer_log.refused_reason IS
    '拒答原因：NO_EVIDENCE（检索未命中）/ SPACE_EMPTY（空间内无就绪文档）';
