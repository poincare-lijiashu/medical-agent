package com.medassist.clinical.service;

import com.medassist.clinical.domain.PrintModel;
import com.medassist.clinical.domain.PrintModel.PrintAnomaly;
import com.medassist.clinical.domain.PrintModel.PrintEvent;
import com.medassist.clinical.domain.PrintModel.PrintEventPage;
import com.medassist.clinical.repo.PrintEventRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 打印留痕（第 230 批）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>前端在**打印窗口成功打开后**上报，不阻塞打印；上报失败不回滚、不提示，
 *       避免"打印机已经出纸，界面却报错"；</li>
 *   <li>服务端仍做字段校验（类型/单号必填、长度上限），防止流水被脏数据污染；</li>
 *   <li>{@code docNo} 是打印件上的业务号，与 {@code targetId} 分开存：
 *       补打历史按单号查（用户手上的单子只有号），排障按主键查。</li>
 * </ul>
 *
 * <p>第 238 批补打原因：同一单号第 2 次及以后打印必须登记原因。
 * 判定放在服务端（{@code countByDoc}）而不是前端，是因为前端只能看到当前页，
 * 换个日期区间或换个人登录就"看不见历史"，原因约束会被绕过。
 * 首次打印不要求原因——首次不存在"为什么又打一张"。
 */
@Service
public class PrintEventService {

    private static final int MAX_DOC_TYPE = 32;
    private static final int MAX_DOC_NO = 64;
    private static final int MAX_TARGET_TYPE = 32;
    private static final int MAX_PATIENT_NO = 32;
    private static final int MAX_REASON = 64;
    private static final int MAX_VERDICT = 16;
    private static final int MAX_REVIEW_NOTE = 200;
    /** 处置结论（写入库，前端只做下拉，不在服务端做 i18n）。 */
    private static final String VERDICT_REASONABLE = "REASONABLE";
    private static final String VERDICT_NEEDS_ACTION = "NEEDS_ACTION";
    /** 待核查角标的默认统计窗口（天）：角标轮询不能每次全表聚合。 */
    private static final int DEFAULT_ANOMALY_DAYS = 30;
    private static final int DEFAULT_LIMIT = 50;
    /** 单页上限：与前端“最近 200 条”对齐，防止一次拉全表。 */
    private static final int MAX_LIMIT = 200;

    private final PrintEventRepository repo;

    public PrintEventService(PrintEventRepository repo) {
        this.repo = repo;
    }

    /** 记录一次打印；返回落库后的记录（含打印时间、第几次、补打原因）。 */
    public PrintEvent record(String docType, String docNo, String targetType, UUID targetId,
                             String patientNo, String reason, String actor, UUID campusId) {
        String type = requireText(docType, "单据类型", MAX_DOC_TYPE);
        String no = requireText(docNo, "单据号", MAX_DOC_NO);
        String target = optionalText(targetType, MAX_TARGET_TYPE);
        String patient = optionalText(patientNo, MAX_PATIENT_NO);
        String why = optionalText(reason, MAX_REASON);
        String who = optionalText(actor, 64);
        long prior = repo.countByDoc(type, no);
        if (prior > 0 && why == null) {
            throw new BusinessException(ErrorCode.PRINT_REASON_REQUIRED,
                    "该单据已打印 " + prior + " 次，第 " + (prior + 1) + " 次补打必须登记原因");
        }
        PrintEvent event = new PrintEvent(UUID.randomUUID(), type, no, target, targetId,
                patient, who == null || who.isBlank() ? "system" : who, campusId,
                OffsetDateTime.now(), (int) prior + 1, why);
        repo.insert(event);
        return event;
    }

    /**
     * 分页查询（第 235 批）：支持按日期区间核查打印历史。
     *
     * <p>时间口径是**业务日期**：`[from 00:00, to 次日 00:00)`，用系统时区换算，
     * 与运营报表按日归属的做法一致；`from/to` 为空表示不限。
     */
    public PrintEventPage page(String docType, String docNo, String targetType, UUID targetId,
                               String campusCsv, LocalDate from, LocalDate to,
                               Integer offset, Integer limit) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        OffsetDateTime[] win = window(from, to);
        int size = clampLimit(limit);
        int start = offset == null ? 0 : Math.max(0, offset);
        String type = optionalText(docType, MAX_DOC_TYPE);
        String no = optionalText(docNo, MAX_DOC_NO);
        String target = optionalText(targetType, MAX_TARGET_TYPE);
        long total = repo.count(type, no, target, targetId, campusCsv, win[0], win[1]);
        List<PrintEvent> items = total <= start
                ? List.of()
                : repo.list(type, no, target, targetId, campusCsv, win[0], win[1], start, size);
        return new PrintEventPage(items, total, start, size);
    }

    /**
     * 补打异常：同一单号在期间内打印次数 ≥ threshold（默认 3 次）。
     *
     * <p>不按“人”去重——换人补打同样是重复出单；阈值上限 50，
     * 避免有人把阈值调到 1 万后“查询成功但没有异常”的假安全感。
     */
    public List<PrintAnomaly> anomalies(String campusCsv, LocalDate from, LocalDate to,
                                        Integer threshold, Integer limit) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        int th = threshold == null ? 3 : Math.max(2, Math.min(threshold, 50));
        OffsetDateTime[] win = window(from, to);
        return repo.listAnomalies(campusCsv, win[0], win[1], th, clampLimit(limit));
    }

    /**
     * 待核查统计（第 239 批）：全局待办角标只认这个数。
     *
     * <p>为什么默认只算最近 30 天：打印流水随业务量线性增长，
     * 角标每 60 秒轮询一次，若每次都全表聚合，等于给自己造一个慢查询。
     * 30 天覆盖“当月质控回头看”的常规节奏；历史账仍可在留痕页自定义区间查。
     */
    public PrintModel.PrintAnomalySummary anomalySummary(String campusCsv, LocalDate from,
                                                         LocalDate to, Integer threshold) {
        LocalDate end = to;
        LocalDate start = from != null
                ? from
                : (end != null ? end.minusDays(DEFAULT_ANOMALY_DAYS)
                               : LocalDate.now(ZoneId.systemDefault()).minusDays(DEFAULT_ANOMALY_DAYS));
        if (start.isAfter(end != null ? end : start)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        int th = threshold == null ? 3 : Math.max(2, Math.min(threshold, 50));
        OffsetDateTime[] win = window(start, end);
        return repo.anomalySummary(campusCsv, win[0], win[1], th);
    }

    /**
     * 核查处置（第 239 批）：给一条补打异常签字。
     *
     * <p>追加式：同一单号再次被处置会新增一行，最新一条为当前结论。
     * `NEEDS_ACTION` 必须写说明——只勾“需整改”不写要改什么，等于把问题又推回给下一个人；
     * `REASONABLE` 允许不写（核实为卡纸重打这类无需解释的情形）。
     */
    public PrintModel.Review review(String docType, String docNo, String verdict, String note,
                                    String actor) {
        String type = requireText(docType, "单据类型", MAX_DOC_TYPE);
        String no = requireText(docNo, "单据号", MAX_DOC_NO);
        String v = requireText(verdict, "处置结论", MAX_VERDICT);
        if (!VERDICT_REASONABLE.equals(v) && !VERDICT_NEEDS_ACTION.equals(v)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "处置结论只能是 " + VERDICT_REASONABLE + " 或 " + VERDICT_NEEDS_ACTION);
        }
        String text = optionalText(note, MAX_REVIEW_NOTE);
        if (VERDICT_NEEDS_ACTION.equals(v) && text == null) {
            throw new BusinessException(ErrorCode.PRINT_ANOMALY_NOTE_REQUIRED);
        }
        // 没有打印记录的单号不允许凭空“核查通过”：那会让台账出现无法追溯的处置
        if (repo.countByDoc(type, no) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "该单据号没有打印记录，无法核查");
        }
        String who = optionalText(actor, 64);
        String by = who == null || who.isBlank() ? "system" : who;
        OffsetDateTime now = OffsetDateTime.now();
        repo.insertReview(UUID.randomUUID(), type, no, v, text, by, now);
        return new PrintModel.Review(v, text, by, now);
    }

    /** 业务日期 → `[from 00:00, to 次日 00:00)`；任一为空则该端不限。 */
    private static OffsetDateTime[] window(LocalDate from, LocalDate to) {
        ZoneId zone = ZoneId.systemDefault();
        return new OffsetDateTime[]{
                from == null ? null : from.atStartOfDay(zone).toOffsetDateTime(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()};
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static String requireText(String value, String label, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空");
        }
        if (trimmed.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "过长（最多 " + max + " 字符）");
        }
        return trimmed;
    }

    private static String optionalText(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
