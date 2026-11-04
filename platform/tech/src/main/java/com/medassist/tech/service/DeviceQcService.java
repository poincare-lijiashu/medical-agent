package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.tech.domain.TechModel.Device;
import com.medassist.tech.domain.TechModel.DeviceQc;
import com.medassist.tech.domain.TechModel.DeviceStatusCount;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.DeviceDtos.CreateDeviceReq;
import com.medassist.tech.web.DeviceDtos.DeviceSummary;
import com.medassist.tech.web.DeviceDtos.DeviceView;
import com.medassist.tech.web.DeviceDtos.RecordQcReq;
import com.medassist.tech.web.DeviceDtos.UpdateDeviceStatusReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 医技设备档案与质控（G35）。
 *
 * <p>规则：
 * <ul>
 *   <li>设备编号由服务生成且唯一；装机日期不能晚于业务日；</li>
 *   <li>停用（RETIRED）不可恢复，也不允许再登记质控；</li>
 *   <li>状态变更走版本号 CAS；质控记录追加式，失败记录在详情与看板中保留。</li>
 * </ul>
 */
@Service
public class DeviceQcService {

    private static final Set<String> MODALITIES =
            Set.of("CT", "MR", "US", "DR", "CR", "LIS", "ECG", "OTHER");
    private static final Set<String> STATUSES =
            Set.of(Device.ACTIVE, Device.MAINTENANCE, Device.RETIRED);

    private final TechRepository repo;
    private final ObjectMapper objectMapper;

    public DeviceQcService(TechRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DeviceView create(CreateDeviceReq req, String actor) {
        String operator = requireActor(actor);
        if (req.installedAt() != null && req.installedAt().isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "装机日期不能晚于今天");
        }
        Device device = new Device(UUID.randomUUID(), repo.nextDeviceNo(), req.name().trim(),
                req.modality(), req.deptId(), blankToNull(req.location()),
                blankToNull(req.manufacturer()), blankToNull(req.model()),
                blankToNull(req.serialNo()), Device.ACTIVE, req.installedAt(), 0L, Instant.now());
        repo.insertDevice(device);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deviceNo", device.deviceNo());
        detail.put("modality", device.modality());
        audit(operator, "DEVICE_CREATE", device.id().toString(), detail);
        return new DeviceView(device, null, List.of());
    }

    public List<Device> list(String keyword, String modality, String status, Integer limit) {
        String normalizedModality = normalizeModality(modality, true);
        String normalizedStatus = normalizeStatus(status, true);
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        return repo.listDevices(keyword, normalizedModality, normalizedStatus, size);
    }

    public DeviceView detail(UUID id) {
        Device device = repo.findDevice(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_DEVICE_NOT_FOUND));
        return new DeviceView(device, repo.latestDeviceQcResult(id).orElse(null),
                repo.listDeviceQc(id, 50));
    }

    @Transactional
    public DeviceView updateStatus(UUID id, UpdateDeviceStatusReq req, String actor) {
        String operator = requireActor(actor);
        Device device = repo.lockDevice(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_DEVICE_NOT_FOUND));
        String target = req.status().trim().toUpperCase();
        if (!STATUSES.contains(target)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的设备状态：" + req.status());
        }
        if (device.retired() && !Device.RETIRED.equals(target)) {
            throw new BusinessException(ErrorCode.TECH_DEVICE_STATUS_INVALID,
                    "设备已停用，不能恢复为 " + target);
        }
        if (!repo.updateDeviceStatus(id, target, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "设备状态已变化，请刷新后重试");
        }
        audit(operator, "DEVICE_STATUS", id.toString(), Map.of(
                "deviceNo", device.deviceNo(), "from", device.status(), "to", target));
        Device updated = repo.findDevice(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_DEVICE_NOT_FOUND));
        return new DeviceView(updated, repo.latestDeviceQcResult(id).orElse(null),
                repo.listDeviceQc(id, 50));
    }

    @Transactional
    public DeviceView recordQc(UUID id, RecordQcReq req, String actor) {
        String operator = requireActor(actor);
        Device device = repo.lockDevice(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_DEVICE_NOT_FOUND));
        if (device.retired()) {
            throw new BusinessException(ErrorCode.TECH_DEVICE_STATUS_INVALID,
                    "设备已停用，不能登记质控");
        }
        DeviceQc qc = new DeviceQc(UUID.randomUUID(), id, device.deviceNo(), device.name(),
                req.qcType(), req.result(), req.itemName().trim(),
                blankToNull(req.expectedValue()), blankToNull(req.actualValue()),
                blankToNull(req.unit()), operator, req.performedAt(), blankToNull(req.note()),
                Instant.now());
        repo.insertDeviceQc(qc);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deviceNo", device.deviceNo());
        detail.put("qcType", qc.qcType());
        detail.put("result", qc.result());
        detail.put("itemName", qc.itemName());
        audit(operator, "DEVICE_QC", id.toString(), detail);
        return new DeviceView(device, qc.result(), repo.listDeviceQc(id, 50));
    }

    public DeviceSummary summary() {
        int active = 0;
        int maintenance = 0;
        int retired = 0;
        for (DeviceStatusCount row : repo.deviceStatusCounts()) {
            switch (row.status()) {
                case Device.ACTIVE -> active = row.count();
                case Device.MAINTENANCE -> maintenance = row.count();
                case Device.RETIRED -> retired = row.count();
                default -> {
                }
            }
        }
        OffsetDateTime from = java.time.LocalDate.now(BusinessTime.zone()).minusDays(29)
                .atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        return new DeviceSummary(active + maintenance + retired, active, maintenance, retired,
                repo.countDeviceQcFailSince(from));
    }

    private static String normalizeModality(String modality, boolean allowNull) {
        if (modality == null || modality.isBlank()) {
            if (allowNull) {
                return null;
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "设备类型不能为空");
        }
        String value = modality.trim().toUpperCase();
        if (!MODALITIES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的设备类型：" + modality);
        }
        return value;
    }

    private static String normalizeStatus(String status, boolean allowNull) {
        if (status == null || status.isBlank()) {
            if (allowNull) {
                return null;
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "设备状态不能为空");
        }
        String value = status.trim().toUpperCase();
        if (!STATUSES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的设备状态：" + status);
        }
        return value;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行设备操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "DEVICE", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "DEVICE", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
