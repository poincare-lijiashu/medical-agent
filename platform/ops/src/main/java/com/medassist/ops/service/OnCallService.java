package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.ops.domain.OpsModel.OnCallRotation;
import com.medassist.ops.domain.OpsModel.OnCallShift;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 值班轮换与排班。
 *
 * <p>排班是"生成出来的事实"：生成后写入 `ops_oncall_shift`，查询不现算。
 * 这样人工调班、历史追溯、交接记录都有稳定载体；重新生成不会覆盖已有人工调整。
 *
 * <p>轮换算法：以起始日为锚，每 {@code cycleDays} 天换下一人；主值班与备值班取相邻成员，
 * 保证"主不在有备"，而不是让备班变成随机名单。
 */
@Service
public class OnCallService {

    private static final int MAX_RANGE_DAYS = 366;

    private final OpsRepository repo;

    public OnCallService(OpsRepository repo) {
        this.repo = repo;
    }

    public List<OnCallRotation> listRotations() {
        return repo.listRotations();
    }

    public OnCallRotation createRotation(OpsDtos.RotationReq req, String actor,
                                         String clientIp, String traceId) {
        requireActor(actor);
        requiredMembers(req.members());
        if (req.cycleDays() < 1 || req.cycleDays() > 90) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID, "轮换周期必须在 1–90 天之间");
        }
        OnCallRotation rotation = new OnCallRotation(UUID.randomUUID(), req.groupCode().trim(),
                req.name(), req.timezone() == null || req.timezone().isBlank()
                        ? "Asia/Shanghai" : req.timezone(),
                req.startDate(), req.cycleDays(), req.members(),
                req.handoverTime() == null ? java.time.LocalTime.of(8, 0) : req.handoverTime(),
                "ACTIVE", 0);
        if (!repo.insertRotation(rotation)) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID,
                    "值班组编码已存在：" + req.groupCode());
        }
        repo.audit(actor, "ROTATION_UPSERT", rotation.groupCode(),
                Map.of("members", rotation.members(), "cycleDays", rotation.cycleDays()),
                clientIp, traceId);
        return requireRotation(rotation.id());
    }

    /** 生成 [from, to] 的排班；已存在的日期保留原值班人（不覆盖人工调整）。 */
    public Map<String, Object> generate(UUID rotationId, LocalDate from, LocalDate to,
                                        String actor, String clientIp, String traceId) {
        requireActor(actor);
        OnCallRotation rotation = requireRotation(rotationId);
        if (!"ACTIVE".equals(rotation.status())) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID, "该值班组已停用");
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID, "排班日期范围不合法");
        }
        if (from.isBefore(rotation.startDate())) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID,
                    "排班起始日期不能早于轮换起始日 " + rotation.startDate());
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID,
                    "单次最多生成 " + MAX_RANGE_DAYS + " 天");
        }

        List<String> members = rotation.members();
        int created = 0;
        int existing = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int index = memberIndex(rotation, date);
            String primary = members.get(index);
            String secondary = members.size() > 1 ? members.get((index + 1) % members.size()) : null;
            int inserted = repo.generateShift(rotationId, date, primary, secondary);
            created += inserted;
            if (inserted == 0) {
                existing++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rotation", rotation.groupCode());
        summary.put("from", from.toString());
        summary.put("to", to.toString());
        summary.put("created", created);
        summary.put("existing", existing);
        repo.audit(actor, "SHIFT_GENERATE", rotation.groupCode(), summary, clientIp, traceId);
        return summary;
    }

    public List<OnCallShift> shifts(UUID rotationId, LocalDate from, LocalDate to) {
        requireRotation(rotationId);
        LocalDate start = from == null ? BusinessTime.today() : from;
        LocalDate end = to == null ? start.plusDays(13) : to;
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID, "结束日期不能早于开始日期");
        }
        return repo.listShifts(rotationId, start, end);
    }

    public Map<String, Object> today(String groupCode) {
        LocalDate today = BusinessTime.today();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", today.toString());
        result.put("items", repo.shiftsOn(today, groupCode));
        return result;
    }

    public void overrideShift(UUID rotationId, LocalDate date, OpsDtos.ShiftOverrideReq req,
                              String actor, String clientIp, String traceId) {
        requireActor(actor);
        OnCallRotation rotation = requireRotation(rotationId);
        if (date == null) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID, "缺少日期");
        }
        if (req.reason() == null || req.reason().trim().length() < 2) {
            throw new BusinessException(ErrorCode.OPS_REASON_REQUIRED);
        }
        if (req.primaryUser() == null || req.primaryUser().isBlank()) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID, "主值班人不能为空");
        }
        repo.overrideShift(rotationId, date, req.primaryUser().trim(),
                req.secondaryUser() == null ? null : req.secondaryUser().trim(),
                req.reason().trim());
        repo.audit(actor, "SHIFT_OVERRIDE", rotation.groupCode(),
                Map.of("date", date.toString(), "primary", req.primaryUser(),
                        "secondary", req.secondaryUser() == null ? "" : req.secondaryUser(),
                        "reason", req.reason()), clientIp, traceId);
    }

    public OnCallRotation requireRotation(UUID id) {
        return repo.findRotation(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_ROTATION_NOT_FOUND));
    }

    /** 成员轮换位置：以起始日锚定，cycleDays 天换一人。 */
    static int memberIndex(OnCallRotation rotation, LocalDate date) {
        long days = ChronoUnit.DAYS.between(rotation.startDate(), date);
        long block = Math.floorDiv(days, rotation.cycleDays());
        return (int) Math.floorMod(block, rotation.members().size());
    }

    private static void requiredMembers(List<String> members) {
        if (members == null || members.isEmpty()) {
            throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID, "值班成员不能为空");
        }
        Set<String> unique = new HashSet<>();
        for (String member : members) {
            if (member == null || member.isBlank()) {
                throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID, "值班成员不能为空");
            }
            if (!unique.add(member.trim())) {
                throw new BusinessException(ErrorCode.OPS_ROTATION_INVALID,
                        "值班成员重复：" + member);
            }
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.OPS_ACTOR_REQUIRED);
        }
    }
}
