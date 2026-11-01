-- ============================================================================
-- 知识库域 V1：知识空间（隔离单位）→ 文档 → 切片 → 异步索引任务 → 检索审计
--
-- 设计要点：
--   1. 隔离边界是 kb_space：所有查询都必须带 space_id，成员关系决定可见性；
--      不加"租户列"到每张表再用 WHERE 过滤那种容易漏写的形式，而是以 space 为聚合根。
--   2. 切片检索用**中文二元组（bigram）+ tsvector**：PostgreSQL 默认分词器不切中文，
--      直接 to_tsvector 会把整句当一个词，检索等于不可用。入库时把正文转成
--      "空格分隔的二元组/英文词"写入 terms 列，查询词同样转换后用 tsquery OR 匹配。
--   3. embedding 用 real[] 存放（L2 归一化后的向量）：在未安装 pgvector 的环境也能跑，
--      余弦相似度退化为点积；后续装 pgvector 可平滑迁移到 vector 列与 HNSW 索引。
--   4. 索引是异步作业：文档先落库为 PENDING，worker 用 FOR UPDATE SKIP LOCKED 抢占，
--      失败按次数退避重试；同一文档同时只允许一个未完成任务（部分唯一索引兜底）。
-- ============================================================================

CREATE TABLE kb_svc.kb_space (
    id          uuid PRIMARY KEY,
    code        text NOT NULL UNIQUE,
    name        text NOT NULL,
    description text,
    org_code    text,
    visibility  text NOT NULL DEFAULT 'PRIVATE'
                CHECK (visibility IN ('PRIVATE', 'ORG', 'PUBLIC')),
    status      text NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by  text NOT NULL,
    version     bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE kb_svc.kb_space IS
    '知识空间：隔离与授权的最小单位；PUBLIC 空间对具备检索权限的登录用户只读可见';

CREATE TABLE kb_svc.kb_space_member (
    space_id   uuid NOT NULL REFERENCES kb_svc.kb_space (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL,
    role       text NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, user_id)
);

CREATE INDEX ix_kb_space_member_user ON kb_svc.kb_space_member (user_id);

COMMENT ON TABLE kb_svc.kb_space_member IS
    '空间成员：OWNER 可管理成员与归档空间；EDITOR 可写文档；VIEWER 只读与检索';

CREATE TABLE kb_svc.kb_document (
    id            uuid PRIMARY KEY,
    space_id      uuid NOT NULL REFERENCES kb_svc.kb_space (id),
    title         text NOT NULL,
    source_type   text NOT NULL DEFAULT 'MANUAL'
                  CHECK (source_type IN ('MANUAL', 'UPLOAD', 'URL', 'IMPORT')),
    source_uri    text,
    content       text NOT NULL,
    content_hash  text NOT NULL,
    status        text NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'INDEXING', 'READY', 'FAILED', 'ARCHIVED')),
    chunk_count   int NOT NULL DEFAULT 0,
    char_count    int NOT NULL DEFAULT 0,
    error_message text,
    created_by    text NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- 同一空间内内容完全相同（SHA-256 相同）的文档不重复入库；归档后允许重新入库
CREATE UNIQUE INDEX ux_kb_document_hash
    ON kb_svc.kb_document (space_id, content_hash)
    WHERE status <> 'ARCHIVED';

CREATE INDEX ix_kb_document_space ON kb_svc.kb_document (space_id, status, created_at DESC);

COMMENT ON TABLE kb_svc.kb_document IS
    '知识文档：正文以原文保存（引用溯源需要原文偏移），content_hash 用于增量更新与去重';

CREATE TABLE kb_svc.kb_chunk (
    id          uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES kb_svc.kb_document (id) ON DELETE CASCADE,
    seq         int NOT NULL,
    content     text NOT NULL,
    char_start  int NOT NULL,
    char_end    int NOT NULL,
    section     text,
    terms       text NOT NULL,
    embedding   real[],
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, seq),
    CONSTRAINT ck_kb_chunk_range CHECK (char_start >= 0 AND char_end > char_start),
    CONSTRAINT ck_kb_chunk_terms CHECK (length(terms) > 0)
);

CREATE INDEX ix_kb_chunk_terms ON kb_svc.kb_chunk USING gin (to_tsvector('simple', terms));
CREATE INDEX ix_kb_chunk_document ON kb_svc.kb_chunk (document_id, seq);

COMMENT ON COLUMN kb_svc.kb_chunk.terms IS
    '检索词元：中文二元组 + 英文/数字小写词，空格分隔；配合 gin(to_tsvector(''simple'', terms)) 使用';
COMMENT ON COLUMN kb_svc.kb_chunk.embedding IS
    'L2 归一化向量；为空表示该块未生成向量（未配置向量模型或尚未索引）';
COMMENT ON COLUMN kb_svc.kb_chunk.char_start IS
    '相对文档原文的字符偏移（按 Java 的 code unit 计），用于把命中片段精确回指原文并做高亮';

CREATE TABLE kb_svc.kb_ingest_task (
    id          uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES kb_svc.kb_document (id) ON DELETE CASCADE,
    type        text NOT NULL DEFAULT 'INDEX' CHECK (type IN ('INDEX', 'REINDEX')),
    status      text NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts    int NOT NULL DEFAULT 0,
    last_error  text,
    next_run_at timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- 同一文档同时只允许一个待执行/执行中的任务：避免重复点击"重建索引"把 CPU 打满
CREATE UNIQUE INDEX ux_kb_ingest_task_active
    ON kb_svc.kb_ingest_task (document_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX ix_kb_ingest_task_ready
    ON kb_svc.kb_ingest_task (status, next_run_at)
    WHERE status IN ('PENDING', 'FAILED');

COMMENT ON TABLE kb_svc.kb_ingest_task IS
    '异步索引任务：worker 以 FOR UPDATE SKIP LOCKED 抢占；失败按 attempts 退避重试';

CREATE TABLE kb_svc.kb_query_log (
    id          bigserial PRIMARY KEY,
    space_id    uuid NOT NULL,
    actor       text NOT NULL,
    query_text  text NOT NULL,
    mode        text NOT NULL CHECK (mode IN ('KEYWORD', 'HYBRID', 'VECTOR')),
    top_k       int NOT NULL,
    hit_count   int NOT NULL,
    vector_used boolean NOT NULL,
    latency_ms  int NOT NULL,
    top_score   numeric(8, 6),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_kb_query_log_space ON kb_svc.kb_query_log (space_id, created_at DESC);

COMMENT ON TABLE kb_svc.kb_query_log IS
    '检索审计与效果观测：命中数/是否用向量/时延/top 分数，用于召回率与性能回归';

CREATE TABLE kb_svc.kb_change_log (
    id          bigserial PRIMARY KEY,
    actor       text NOT NULL,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   text NOT NULL,
    detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_kb_change_log_target ON kb_svc.kb_change_log (target_type, target_id, created_at DESC);

COMMENT ON TABLE kb_svc.kb_change_log IS
    '知识库审计（追加式）：空间/成员/文档的写操作全部留痕，不提供更新与删除接口';
