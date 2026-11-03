-- P1-D5：危急值按 report_id 定向查询缺索引。
-- ReportService.rewrite 通过 listCriticalValuesByReport(reportId) 做"整份报告全量比对"，
-- 无索引时每次重写都全表扫描；报告与危急值都会随时间线性增长。
CREATE INDEX IF NOT EXISTS ix_critical_report
    ON tech_svc.critical_value (report_id);
