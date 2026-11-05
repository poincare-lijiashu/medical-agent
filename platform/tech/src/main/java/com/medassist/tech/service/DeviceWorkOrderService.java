package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.CampusFilters;
import com.medassist.common.security.DataScope;
import com.medassist.tech.domain.TechModel.Device;
import com.medassist.tech.domain.TechModel.DeviceWorkOrder;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.DeviceWorkOrderDtos.AcceptWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.CancelWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.CreateWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.FinishWorkOrderReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 设备维保工单（G82）：报修 → 维修 → 完工 → 验收。
 *
 * <p>规则：
 * <ol>
 *   <li>停用设备不能报修（先从设备档案恢复启用，避免给报废设备开工单）；</li>
 *   <li>状态按 OPEN → IN_PROGRESS → DONE → ACCEPTED 单向推进；每一步都带版本 CAS；</li>
 *   <li>完工必须写处理措施；验收必须由建单人以外的人完成（四眼），建单人自己验收等于没验收；</li>
 *   <li>验收或取消后工单冻结（数据库触发器兜底）；取消必须写原因；</li>
 *   <li>故障维修开始 → 设备置 MAINTENANCE；工单关闭且该设备无其他未完结工单 → 置回 ACTIVE。</li>
 * </ol>
 */
@Service
public class DeviceWorkOrderService {

    private static final Set<String> TYPES = Set.of(DeviceWorkOrder.PREVENTIVE,
            DeviceWorkOrder.CORRECTIVE, DeviceWorkOrder.INSPECTION);
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> STATUSES = Set.of(DeviceWorkOrder.OPEN,
            DeviceWorkOrder.IN_PROGRESS, DeviceWorkOrder.DONE, DeviceWorkOrder.ACCEPTED,
            DeviceWorkOrder.CANCELLED);
    private static final int LIST_LIMIT = 200;
    private static final long REPORT_SKEW_SECONDS = 86_400L;

    private final TechRepository repo;
    private final TechAccessGuard guard;

    public DeviceWorkOrderService(TechRepository repo, TechAccessGuard guard) {
        this.repo = repo;
        this.guard = guard;
    }

    public List<DeviceWorkOrder> list(UUID deviceId, String status, String orderType,
                                      Integer limit, DataScope scope) {
        String normalizedStatus = normalizeStatus(status);
        String normalizedType = normalizeType(orderType);
        if (deviceId != null) {
            guard.requireDevice(scope, deviceId);
        }
        int size = limit == null || limit <= 0 ? LIST_LIMIT : Math.min(limit, LIST_LIMIT);
        return repo.listWorkOrders(deviceId, normalizedStatus, normalizedType,
                CampusFilters.csv(scope.campusFilter()), size);
    }

    public DeviceWorkOrder detail(UUID id, DataScope scope) {
        DeviceWorkOrder order = requireOrder(id);
        guard.requireDevice(scope, order.deviceId());
        return order;
    }

    @Transactional
    public DeviceWorkOrder create(UUID deviceId, CreateWorkOrderReq req, String actor,
                                  DataScope scope) {
        String operator = requireActor(actor);
        Device device = guard.requireDevice(scope, deviceId);
        if (device.retired()) {
            throw new BusinessException(ErrorCode.TECH_DEVICE_STATUS_INVALID,
                    "设备已停用，不能新建维保工单");
        }
        String type = normalizeType(req.orderType());
        if (type == null) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_INVALID, "工单类型不能为空");
        }
        String priority = normalizePriority(req.priority());
        String title = blankToNull(req.title());
        if (title == null) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_INVALID, "工单标题不能为空");
        }
        Instant reportedAt = req.reportedAt() == null ? Instant.now() : req.reportedAt();
        if (reportedAt.isAfter(Instant.now().plusSeconds(REPORT_SKEW_SECONDS))) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_INVALID,
                    "上报时间不能晚于当前时间一天以上");
        }
        DeviceWorkOrder created = new DeviceWorkOrder(UUID.randomUUID(), repo.nextWorkOrderNo(),
                deviceId, device.deviceNo(), device.name(), type, priority, title,
                blankToNull(req.description()), operator, reportedAt, blankToNull(req.vendor()),
                DeviceWorkOrder.OPEN, null, null, null, null, null, null, null, null, operator,
                0L, Instant.now());
        repo.insertWorkOrder(created);
        audit(operator, "DEVICE_WORK_ORDER_CREATE", created.id(),
                "orderNo=" + created.orderNo() + " type=" + type);
        return requireOrder(created.id());
    }

    /** 开始维修；故障维修同时把设备置为 MAINTENANCE（仅当设备仍 ACTIVE）。 */
    @Transactional
    public DeviceWorkOrder start(UUID id, long version, String actor, DataScope scope) {
        String operator = requireActor(actor);
        DeviceWorkOrder order = requireOrder(id);
        guard.requireDevice(scope, order.deviceId());
        if (!DeviceWorkOrder.OPEN.equals(order.status())) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_STATUS_INVALID,
                    "只有待处理的工单可以开始维修，当前：" + order.status());
        }
        if (!repo.startWorkOrder(id, Instant.now(), version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "工单已被他人处理，请刷新后重试");
        }
        if (order.corrective()) {
            repo.markDeviceMaintenanceIfActive(order.deviceId());
        }
        audit(operator, "DEVICE_WORK_ORDER_START", id, "orderNo=" + order.orderNo());
        return requireOrder(id);
    }

    /** 完工：必须写处理措施；完工时间不得早于开始时间。 */
    @Transactional
    public DeviceWorkOrder finish(UUID id, FinishWorkOrderReq req, String actor, DataScope scope) {
        String operator = requireActor(actor);
        DeviceWorkOrder order = requireOrder(id);
        guard.requireDevice(scope, order.deviceId());
        if (!DeviceWorkOrder.IN_PROGRESS.equals(order.status())) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_STATUS_INVALID,
                    "只有维修中的工单可以完工，当前：" + order.status());
        }
        String resolution = blankToNull(req.resolution());
        if (resolution == null) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_INVALID, "完工必须填写处理措施");
        }
        Instant finishedAt = req.finishedAt() == null ? Instant.now() : req.finishedAt();
        if (order.startedAt() != null && finishedAt.isBefore(order.startedAt())) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_INVALID,
                    "完工时间不能早于开始维修时间");
        }
        if (!repo.finishWorkOrder(id, finishedAt, resolution, req.costAmount(), req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "工单已被他人处理，请刷新后重试");
        }
        audit(operator, "DEVICE_WORK_ORDER_FINISH", id,
                "orderNo=" + order.orderNo() + " cost=" + req.costAmount());
        return requireOrder(id);
    }

    /** 验收：四眼——建单人不能验收自己的工单；验收后设备若已无未完结工单则恢复 ACTIVE。 */
    @Transactional
    public DeviceWorkOrder accept(UUID id, AcceptWorkOrderReq req, String actor, DataScope scope) {
        String operator = requireActor(actor);
        DeviceWorkOrder order = requireOrder(id);
        guard.requireDevice(scope, order.deviceId());
        if (!DeviceWorkOrder.DONE.equals(order.status())) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_STATUS_INVALID,
                    "只有已完工的工单可以验收，当前：" + order.status());
        }
        if (operator.equals(order.createdBy())) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_SELF_ACCEPT);
        }
        if (!repo.acceptWorkOrder(id, operator, blankToNull(req.note()), req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "工单已被他人处理，请刷新后重试");
        }
        repo.restoreDeviceActiveIfIdle(order.deviceId(), id);
        audit(operator, "DEVICE_WORK_ORDER_ACCEPT", id, "orderNo=" + order.orderNo());
        return requireOrder(id);
    }

    /** 取消：必须写原因；取消后同样按"无其他未完结工单"恢复设备状态。 */
    @Transactional
    public DeviceWorkOrder cancel(UUID id, CancelWorkOrderReq req, String actor, DataScope scope) {
        String operator = requireActor(actor);
        DeviceWorkOrder order = requireOrder(id);
        guard.requireDevice(scope, order.deviceId());
        if (order.terminal()) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_STATUS_INVALID,
                    "工单已" + (DeviceWorkOrder.ACCEPTED.equals(order.status()) ? "验收" : "取消")
                            + "，不能重复取消");
        }
        String reason = blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_CANCEL_REASON_REQUIRED);
        }
        if (!repo.cancelWorkOrder(id, reason, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "工单已被他人处理，请刷新后重试");
        }
        repo.restoreDeviceActiveIfIdle(order.deviceId(), id);
        audit(operator, "DEVICE_WORK_ORDER_CANCEL", id,
                "orderNo=" + order.orderNo() + " reason=" + reason);
        return requireOrder(id);
    }

    // ---------------------------------------------------------------- 内部

    private DeviceWorkOrder requireOrder(UUID id) {
        return repo.findWorkOrder(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_WORK_ORDER_NOT_FOUND));
    }

    private static String normalizeType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (!TYPES.contains(normalized)) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_INVALID,
                    "不支持的工单类型：" + value);
        }
        return normalized;
    }

    private static String normalizePriority(String value) {
        if (value == null || value.isBlank()) {
            return "NORMAL";
        }
        String normalized = value.trim().toUpperCase();
        if (!PRIORITIES.contains(normalized)) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_INVALID,
                    "不支持的优先级：" + value);
        }
        return normalized;
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (!STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.TECH_WORK_ORDER_INVALID,
                    "不支持的工单状态：" + value);
        }
        return normalized;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "缺少操作人，不能执行维保工单操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, UUID targetId, String detail) {
        repo.logChange(actor, action, "DEVICE_WORK_ORDER", targetId.toString(),
                "{\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
