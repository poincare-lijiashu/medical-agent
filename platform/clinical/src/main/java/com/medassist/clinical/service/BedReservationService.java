package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.BedReservation;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.BedReservationView;
import com.medassist.clinical.web.ClinicalDtos.BedReserveReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.Masks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 床位预留（择期入院 / 转科 / 急诊留床）。
 *
 * <p>为什么必须独立于入院登记：床位状态里早就有 RESERVED，但一直没有写入路径，
 * 择期患者、转科患者、急诊留床只能靠口头约定。把“预留”做成显式实体后：
 * <ul>
 *   <li>床位看板上是可见的（谁占着、为什么、到什么时候）；</li>
 *   <li>到期自动释放，不会出现“电话里说留一张床，三天后还锁着”；</li>
 *   <li>入院/转床可以直接消费预留（同一患者），不需要先手动解除再抢床。</li>
 * </ul>
 *
 * <p>并发约定：所有写路径按 <b>床位 → 预留</b> 的顺序加锁（与入院/转床一致），
 * 清理任务同样先锁床位再锁预留，避免反向加锁死锁。
 */
@Service
public class BedReservationService {

    private static final Logger log = LoggerFactory.getLogger(BedReservationService.class);

    /** 未指定到期时间时的默认保留时长：床位无限期被占在运营上不可接受。 */
    private static final Duration DEFAULT_HOLD = Duration.ofHours(24);

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public BedReservationService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 病区有效预留列表（含已到期但尚未回收的，标记 expired）。 */
    public List<BedReservationView> list(UUID wardId) {
        requireWard(wardId);
        Map<UUID, String> bedNos = new LinkedHashMap<>();
        for (Bed bed : repo.listBeds(wardId)) {
            bedNos.put(bed.id(), bed.bedNo());
        }
        Instant now = Instant.now();
        return repo.listActiveBedReservations(wardId).stream()
                .map(r -> toView(r, bedNos.get(r.bedId()), now))
                .toList();
    }

    /**
     * 新建预留：锁床 → 校验空闲 → 置 RESERVED → 写预留。
     *
     * <p>到期时间缺省 24 小时；显式传入必须晚于当前时间——创建一条“已经过期”的
     * 预留没有业务含义，只会让床位立刻被清理任务释放。
     */
    @Transactional
    public BedReservationView create(UUID wardId, BedReserveReq req, String actor) {
        requireWard(wardId);
        Bed bed = repo.lockBed(req.bedId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "床位不存在"));
        if (!bed.wardId().equals(wardId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "床位不属于该病区");
        }
        if (!bed.free()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "床位 " + bed.bedNo() + " 当前状态为" + bedStatusLabel(bed.status()) + "，不能预留");
        }
        Instant now = Instant.now();
        Instant until = req.reservedUntil() == null ? now.plus(DEFAULT_HOLD) : req.reservedUntil();
        if (!until.isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "预留截止时间必须晚于当前时间");
        }
        if (!repo.reserveBed(bed.id(), bed.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "床位状态已被他人修改，请刷新后重试");
        }

        BedReservation reservation = new BedReservation(UUID.randomUUID(), bed.id(), wardId,
                req.patientId(), blankToNull(req.patientNo()), blankToNull(req.reason()), actor,
                until, BedReservation.ACTIVE, now, 0L);
        try {
            repo.insertBedReservation(reservation);
        } catch (DataIntegrityViolationException e) {
            // ux_bed_reservation_active：并发预留同一张床，事务回滚会同时撤销上面的占床
            throw new BusinessException(ErrorCode.CONFLICT, "该床位已有生效中的预留，请刷新后重试");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bedNo", bed.bedNo());
        detail.put("wardId", wardId.toString());
        detail.put("patientNo", reservation.patientNo() == null ? ""
                : Masks.patientNo(reservation.patientNo()));
        detail.put("reservedUntil", until.toString());
        detail.put("reason", reservation.reason() == null ? "" : reservation.reason());
        audit(actor, "BED_RESERVE", "BED_RESERVATION", reservation.id().toString(), detail);
        return toView(reservation, bed.bedNo(), now);
    }

    /**
     * 解除预留：先锁床再锁预留，重查状态后置 RELEASED，并把仍为 RESERVED 的床位放开。
     */
    @Transactional
    public void release(UUID wardId, UUID reservationId, String reason, String actor) {
        BedReservation snapshot = repo.findBedReservation(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预留不存在"));
        if (!snapshot.wardId().equals(wardId)) {
            // 不暴露其他病区的预留是否存在
            throw new BusinessException(ErrorCode.NOT_FOUND, "预留不存在");
        }
        Bed bed = repo.lockBed(snapshot.bedId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "床位不存在"));
        BedReservation reservation = repo.lockBedReservation(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预留不存在"));
        if (!reservation.active()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该预留已结束（" + reservationStatusLabel(reservation.status()) + "）");
        }
        if (!repo.updateBedReservationStatus(reservation.id(), BedReservation.ACTIVE,
                BedReservation.RELEASED, reservation.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "预留已被他人修改，请刷新后重试");
        }
        if (Bed.RESERVED.equals(bed.status()) && !repo.freeReservedBed(bed.id(), bed.version())) {
            // 床位已被人占用时不再强释放，但预留仍应结束；这里只记录不改床位
            log.warn("clinical.bed_reservation.release_bed_conflict bedId={} status={}",
                    bed.id(), bed.status());
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bedNo", bed.bedNo());
        detail.put("reason", reason == null || reason.isBlank() ? "" : reason.trim());
        audit(actor, "BED_RESERVE_RELEASE", "BED_RESERVATION", reservation.id().toString(), detail);
    }

    /**
     * 回收到期预留（后台任务调用）。
     *
     * <p>每次最多处理 {@code limit} 条；先锁床位再锁预留并重查，保证与入院/解除并发时
     * 不会释放已被消费的床位。
     */
    @Transactional
    public int sweepExpired(int limit) {
        Instant now = Instant.now();
        List<BedReservation> candidates = repo.listExpiredBedReservations(now, limit);
        int expired = 0;
        for (BedReservation candidate : candidates) {
            Bed bed = repo.lockBed(candidate.bedId()).orElse(null);
            if (bed == null) {
                continue;
            }
            BedReservation current = repo.lockBedReservation(candidate.id()).orElse(null);
            if (current == null || !current.active()) {
                continue;
            }
            if (current.reservedUntil() == null || !current.reservedUntil().isBefore(now)) {
                continue;
            }
            if (!repo.updateBedReservationStatus(current.id(), BedReservation.ACTIVE,
                    BedReservation.EXPIRED, current.version())) {
                continue;
            }
            if (Bed.RESERVED.equals(bed.status())) {
                repo.freeReservedBed(bed.id(), bed.version());
            }
            expired++;
        }
        if (expired > 0) {
            log.info("clinical.bed_reservation.expired count={}", expired);
        }
        return expired;
    }

    // ---------------------------------------------------------------- 内部

    private Ward requireWard(UUID wardId) {
        Ward ward = repo.findWard(wardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "病区不存在"));
        if (!ward.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "病区已停用");
        }
        return ward;
    }

    private BedReservationView toView(BedReservation r, String bedNo, Instant now) {
        boolean expired = r.reservedUntil() != null && !r.reservedUntil().isAfter(now);
        return new BedReservationView(r.id(), r.bedId(), bedNo, r.wardId(),
                r.patientId(), r.patientNo(), r.reason(), r.reservedBy(),
                r.reservedUntil(), r.createdAt(), expired);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String bedStatusLabel(String status) {
        return switch (status) {
            case Bed.OCCUPIED -> "在床";
            case Bed.RESERVED -> "已预留";
            case Bed.DISABLED -> "停用";
            default -> status;
        };
    }

    private static String reservationStatusLabel(String status) {
        return switch (status) {
            case BedReservation.CONSUMED -> "已入住";
            case BedReservation.RELEASED -> "已解除";
            case BedReservation.EXPIRED -> "已过期";
            default -> status;
        };
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
