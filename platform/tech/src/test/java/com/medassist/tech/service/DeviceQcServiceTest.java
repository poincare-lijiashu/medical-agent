package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.domain.TechModel.Device;
import com.medassist.tech.domain.TechModel.DeviceQc;
import com.medassist.tech.domain.TechModel.DeviceStatusCount;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.DeviceDtos.CreateDeviceReq;
import com.medassist.tech.web.DeviceDtos.DeviceSummary;
import com.medassist.tech.web.DeviceDtos.RecordQcReq;
import com.medassist.tech.web.DeviceDtos.UpdateDeviceStatusReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备档案与质控：装机日期、停用不可恢复、质控追加与看板计数。
 */
class DeviceQcServiceTest {

    private TechRepository repo;
    private DeviceQcService service;

    private final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        service = new DeviceQcService(repo, JsonMapper.builder().build());
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    private Device device(String status) {
        return new Device(deviceId, "DEV20261104000001", "CT-1", "CT", null, "放射科 1 室",
                "西门子", "SOMATOM", "SN-001", status, LocalDate.of(2024, 1, 1), 1L, Instant.now());
    }

    private CreateDeviceReq createReq(LocalDate installedAt) {
        return new CreateDeviceReq("CT-1", "CT", null, "放射科 1 室", "西门子", "SOMATOM",
                "SN-001", installedAt);
    }

    @Test
    @DisplayName("建档：装机日期晚于今天 → 422")
    void createRejectsFutureInstalledDate() {
        assertCode(() -> service.create(createReq(LocalDate.now().plusDays(1)), "tech01"),
                ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertDevice(any());
    }

    @Test
    @DisplayName("建档：生成设备编号并写审计")
    void createSucceeds() {
        when(repo.nextDeviceNo()).thenReturn("DEV20261104000001");

        var view = service.create(createReq(LocalDate.of(2024, 1, 1)), "tech01");

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(repo).insertDevice(captor.capture());
        assertThat(captor.getValue().deviceNo()).isEqualTo("DEV20261104000001");
        assertThat(captor.getValue().status()).isEqualTo(Device.ACTIVE);
        assertThat(view.device().modality()).isEqualTo("CT");
        verify(repo).logChange(eq("tech01"), eq("DEVICE_CREATE"), eq("DEVICE"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("列表：非法设备类型 → 422，limit 上限 200")
    void listNormalizesParams() {
        assertCode(() -> service.list(null, "XRAY", null, 10), ErrorCode.VALIDATION_FAILED);
        service.list("CT", "ct", "active", 9999);
        verify(repo).listDevices("CT", "CT", "ACTIVE", 200);
    }

    @Test
    @DisplayName("状态：停用设备不可恢复 → 409")
    void statusCannotReviveRetiredDevice() {
        when(repo.lockDevice(deviceId)).thenReturn(Optional.of(device(Device.RETIRED)));

        assertCode(() -> service.updateStatus(deviceId,
                new UpdateDeviceStatusReq("ACTIVE", 1L), "tech01"),
                ErrorCode.TECH_DEVICE_STATUS_INVALID);
        verify(repo, never()).updateDeviceStatus(any(), any(), anyLong());
    }

    @Test
    @DisplayName("状态：CAS 未命中 → 409")
    void statusCasConflict() {
        when(repo.lockDevice(deviceId)).thenReturn(Optional.of(device(Device.ACTIVE)));
        when(repo.updateDeviceStatus(deviceId, Device.MAINTENANCE, 1L)).thenReturn(false);

        assertCode(() -> service.updateStatus(deviceId,
                new UpdateDeviceStatusReq("MAINTENANCE", 1L), "tech01"), ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("状态：ACTIVE → MAINTENANCE 成功并写审计")
    void statusUpdates() {
        when(repo.lockDevice(deviceId)).thenReturn(Optional.of(device(Device.ACTIVE)));
        when(repo.updateDeviceStatus(deviceId, Device.MAINTENANCE, 1L)).thenReturn(true);
        when(repo.findDevice(deviceId)).thenReturn(Optional.of(device(Device.MAINTENANCE)));
        when(repo.listDeviceQc(any(), anyInt())).thenReturn(List.of());

        service.updateStatus(deviceId, new UpdateDeviceStatusReq("MAINTENANCE", 1L), "tech01");

        verify(repo).updateDeviceStatus(deviceId, Device.MAINTENANCE, 1L);
        verify(repo).logChange(eq("tech01"), eq("DEVICE_STATUS"), eq("DEVICE"),
                eq(deviceId.toString()), anyString());
    }

    @Test
    @DisplayName("质控：停用设备拒绝登记 → 409")
    void qcRejectsRetiredDevice() {
        when(repo.lockDevice(deviceId)).thenReturn(Optional.of(device(Device.RETIRED)));

        assertCode(() -> service.recordQc(deviceId,
                new RecordQcReq("DAILY", "PASS", "球管预热", null, null, null, null, null),
                "tech01"), ErrorCode.TECH_DEVICE_STATUS_INVALID);
        verify(repo, never()).insertDeviceQc(any());
    }

    @Test
    @DisplayName("质控：登记成功并回传最近结论")
    void qcRecords() {
        when(repo.lockDevice(deviceId)).thenReturn(Optional.of(device(Device.ACTIVE)));
        when(repo.listDeviceQc(any(), anyInt())).thenReturn(List.of());

        var view = service.recordQc(deviceId,
                new RecordQcReq("CALIBRATION", "FAIL", "CT 值校准", "0", "12", "HU",
                        null, "校准偏差"), "tech01");

        ArgumentCaptor<DeviceQc> captor = ArgumentCaptor.forClass(DeviceQc.class);
        verify(repo).insertDeviceQc(captor.capture());
        assertThat(captor.getValue().result()).isEqualTo(DeviceQc.FAIL);
        assertThat(captor.getValue().performedBy()).isEqualTo("tech01");
        assertThat(view.latestQcResult()).isEqualTo(DeviceQc.FAIL);
        verify(repo).logChange(eq("tech01"), eq("DEVICE_QC"), eq("DEVICE"),
                eq(deviceId.toString()), anyString());
    }

    @Test
    @DisplayName("看板：按状态归并并统计近 30 天失败质控")
    void summaryAggregates() {
        when(repo.deviceStatusCounts()).thenReturn(List.of(
                new DeviceStatusCount(Device.ACTIVE, 5),
                new DeviceStatusCount(Device.MAINTENANCE, 1),
                new DeviceStatusCount(Device.RETIRED, 2)));
        when(repo.countDeviceQcFailSince(any())).thenReturn(3);

        DeviceSummary summary = service.summary();

        assertThat(summary.total()).isEqualTo(8);
        assertThat(summary.active()).isEqualTo(5);
        assertThat(summary.maintenance()).isEqualTo(1);
        assertThat(summary.retired()).isEqualTo(2);
        assertThat(summary.qcFail30d()).isEqualTo(3);
    }
}
