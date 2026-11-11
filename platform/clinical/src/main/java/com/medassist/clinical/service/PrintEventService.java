package com.medassist.clinical.service;

import com.medassist.clinical.domain.PrintModel.PrintEvent;
import com.medassist.clinical.repo.PrintEventRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
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
 */
@Service
public class PrintEventService {

    private static final int MAX_DOC_TYPE = 32;
    private static final int MAX_DOC_NO = 64;
    private static final int MAX_TARGET_TYPE = 32;
    private static final int MAX_PATIENT_NO = 32;
    private static final int DEFAULT_LIMIT = 50;

    private final PrintEventRepository repo;

    public PrintEventService(PrintEventRepository repo) {
        this.repo = repo;
    }

    /** 记录一次打印；返回落库后的记录（含打印时间）。 */
    public PrintEvent record(String docType, String docNo, String targetType, UUID targetId,
                             String patientNo, String actor, UUID campusId) {
        String type = requireText(docType, "单据类型", MAX_DOC_TYPE);
        String no = requireText(docNo, "单据号", MAX_DOC_NO);
        String target = optionalText(targetType, MAX_TARGET_TYPE);
        String patient = optionalText(patientNo, MAX_PATIENT_NO);
        String who = optionalText(actor, 64);
        PrintEvent event = new PrintEvent(UUID.randomUUID(), type, no, target, targetId,
                patient, who == null || who.isBlank() ? "system" : who, campusId,
                OffsetDateTime.now());
        repo.insert(event);
        return event;
    }

    public List<PrintEvent> list(String docType, String docNo, String targetType, UUID targetId,
                                 String campusCsv, Integer limit) {
        return repo.list(optionalText(docType, MAX_DOC_TYPE), optionalText(docNo, MAX_DOC_NO),
                optionalText(targetType, MAX_TARGET_TYPE), targetId, campusCsv,
                limit == null ? DEFAULT_LIMIT : limit);
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
