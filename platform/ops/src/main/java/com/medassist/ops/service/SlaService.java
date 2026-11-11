package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.domain.OpsModel.Calendar;
import com.medassist.ops.domain.OpsModel.ServiceCatalog;
import com.medassist.ops.domain.OpsModel.SlaPolicy;
import com.medassist.ops.domain.OpsModel.Ticket;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.support.SlaClock;
import com.medassist.ops.web.OpsDtos;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务目录、SLA 策略、营业日历与 SLA 计算。
 *
 * <p>把 SLA 的"口径"集中在一个服务里：工单服务只问"截止时间是什么"，
 * 不自己算营业时间、不自己猜日历，避免同一套承诺在不同接口算出不同结果。
 */
@Service
public class SlaService {

    private final OpsRepository repo;

    public SlaService(OpsRepository repo) {
        this.repo = repo;
    }

    // ------------------------------------------------------------------ 服务目录

    public List<ServiceCatalog> listServices(boolean onlyActive) {
        return repo.listServices(onlyActive);
    }

    public ServiceCatalog requireService(String serviceCode) {
        return repo.findService(serviceCode)
                .filter(s -> "ACTIVE".equals(s.status()))
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_SERVICE_NOT_FOUND));
    }

    public ServiceCatalog upsertService(OpsDtos.ServiceReq req, String actor,
                                        String clientIp, String traceId) {
        requireActor(actor);
        if (repo.findService(req.serviceCode()).isEmpty()) {
            repo.upsertService(new ServiceCatalog(UUID.randomUUID(), req.serviceCode(), req.name(),
                    req.domain(), req.owner(), req.supportGroup(), req.description(), "ACTIVE",
                    req.sortNo() == null ? 100 : req.sortNo(), 0));
        } else {
            ServiceCatalog existing = repo.findService(req.serviceCode()).orElseThrow();
            repo.upsertService(new ServiceCatalog(existing.id(), req.serviceCode(), req.name(),
                    req.domain(), req.owner(), req.supportGroup(), req.description(),
                    req.status() == null ? existing.status() : req.status(),
                    req.sortNo() == null ? existing.sortNo() : req.sortNo(), existing.version()));
        }
        repo.audit(actor, "SERVICE_UPSERT", req.serviceCode(),
                Map.of("service", req.serviceCode(), "group", req.supportGroup()), clientIp, traceId);
        // 管理员可能刚把服务停用，此时不能再走 requireService（只接受 ACTIVE）
        return repo.findService(req.serviceCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_SERVICE_NOT_FOUND));
    }

    // ------------------------------------------------------------------ 日历

    public List<Calendar> listCalendars() {
        return repo.listCalendars();
    }

    public Calendar requireCalendar(String code) {
        return repo.findCalendar(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_CALENDAR_NOT_FOUND));
    }

    public Calendar upsertCalendar(OpsDtos.CalendarReq req, String actor,
                                   String clientIp, String traceId) {
        requireActor(actor);
        if (req.workdays() == null || req.workdays().isEmpty()) {
            throw new BusinessException(ErrorCode.OPS_CALENDAR_INVALID, "工作日不能为空");
        }
        LocalTime start = req.startTime() == null ? LocalTime.of(8, 0) : req.startTime();
        LocalTime end = req.endTime() == null ? LocalTime.of(17, 0) : req.endTime();
        if (!end.isAfter(start)) {
            throw new BusinessException(ErrorCode.OPS_CALENDAR_INVALID, "结束时间必须晚于开始时间");
        }
        repo.upsertCalendar(new Calendar(req.code(), req.name(),
                req.timezone() == null || req.timezone().isBlank() ? "Asia/Shanghai" : req.timezone(),
                req.workdays(), start, end, req.holidays() == null ? List.of() : req.holidays(),
                "ACTIVE", 0));
        repo.audit(actor, "CALENDAR_UPSERT", req.code(),
                Map.of("calendar", req.code(), "workdays", req.workdays()), clientIp, traceId);
        return requireCalendar(req.code());
    }

    // ------------------------------------------------------------------ SLA 策略

    public List<SlaPolicy> listPolicies(String serviceCode) {
        return repo.listPolicies(serviceCode);
    }

    public SlaPolicy requirePolicy(String serviceCode, String priority) {
        return repo.findPolicy(serviceCode, priority)
                .filter(p -> "ACTIVE".equals(p.status()))
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_SLA_POLICY_NOT_FOUND,
                        "服务 " + serviceCode + " 的 " + priority + " 没有可用的 SLA 策略"));
    }

    public SlaPolicy upsertPolicy(OpsDtos.SlaPolicyReq req, String actor,
                                  String clientIp, String traceId) {
        requireActor(actor);
        requireService(req.serviceCode());
        if (req.responseMinutes() <= 0 || req.resolveMinutes() <= 0
                || req.resolveMinutes() < req.responseMinutes()) {
            throw new BusinessException(ErrorCode.OPS_SLA_INVALID,
                    "响应/解决时限必须为正，且解决时限不能早于响应时限");
        }
        if (req.businessHours() && (req.calendarCode() == null || req.calendarCode().isBlank())) {
            throw new BusinessException(ErrorCode.OPS_SLA_INVALID, "营业时间模式必须绑定营业日历");
        }
        Calendar calendar = null;
        if (req.calendarCode() != null && !req.calendarCode().isBlank()) {
            calendar = requireCalendar(req.calendarCode());
        }
        SlaPolicy existing = repo.findPolicy(req.serviceCode(), req.priority()).orElse(null);
        SlaPolicy saved = repo.upsertPolicy(new SlaPolicy(
                existing == null ? UUID.randomUUID() : existing.id(),
                req.serviceCode(), req.priority(), req.responseMinutes(), req.resolveMinutes(),
                req.escalationMinutes() == null ? 0 : req.escalationMinutes(),
                req.businessHours(), calendar == null ? null : calendar.code(),
                req.status() == null ? "ACTIVE" : req.status(), existing == null ? 0 : existing.version()));
        repo.audit(actor, "POLICY_UPSERT", req.serviceCode() + "/" + req.priority(),
                Map.of("responseMinutes", saved.responseMinutes(),
                        "resolveMinutes", saved.resolveMinutes(),
                        "businessHours", saved.businessHours()), clientIp, traceId);
        return saved;
    }

    /** 策略索引：serviceCode|priority 与策略 ID 两个键，供列表批量装饰 SLA 状态。 */
    public Map<String, SlaPolicy> policyIndex(String serviceCode) {
        Map<String, SlaPolicy> index = new LinkedHashMap<>();
        for (SlaPolicy policy : repo.listPolicies(serviceCode)) {
            index.put(policy.serviceCode() + "|" + policy.priority(), policy);
            index.put("id:" + policy.id(), policy);
        }
        return index;
    }

    public SlaPolicy policyOf(Map<String, SlaPolicy> index, Ticket ticket) {
        if (ticket.slaPolicyId() != null) {
            SlaPolicy byId = index.get("id:" + ticket.slaPolicyId());
            if (byId != null) {
                return byId;
            }
        }
        return index.get(ticket.serviceCode() + "|" + ticket.priority());
    }

    public Instant responseDue(Instant start, SlaPolicy policy) {
        Calendar calendar = policy.calendarCode() == null
                ? null : repo.findCalendar(policy.calendarCode()).orElse(null);
        return SlaClock.dueAt(start, policy.responseMinutes(), calendar, policy.businessHours());
    }

    public Instant resolveDue(Instant start, SlaPolicy policy) {
        Calendar calendar = policy.calendarCode() == null
                ? null : repo.findCalendar(policy.calendarCode()).orElse(null);
        return SlaClock.dueAt(start, policy.resolveMinutes(), calendar, policy.businessHours());
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.OPS_ACTOR_REQUIRED);
        }
    }
}
