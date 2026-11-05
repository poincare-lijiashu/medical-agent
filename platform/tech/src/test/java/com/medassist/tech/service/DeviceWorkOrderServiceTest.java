package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.tech.domain.TechModel.Device;
import com.medassist.tech.domain.TechModel.DeviceWorkOrder;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.DeviceWorkOrderDtos.AcceptWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.CancelWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.CreateWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.FinishWorkOrderReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
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

/** 维保工单规则：停用设备不可报修、单向状态机、四眼验收、设备状态联动。 */
class DeviceWorkOrderServiceTest {

    private TechRepository repo;
    private TechAccessGuard guard;
    private DeviceWorkOrderService service;

    private final UUID deviceId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final Instant reportedAt = Instant.parse("2026-11-05T01:00:00Z");
    private final Instant startedAt = Instant.parse("2026-11-05T02:00:00Z");
    private final DataScope scope = DataScope.all("tech01", true);

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        guard = mock(TechAccessGuard.class);
        service = new DeviceWorkOrderService(repo, guard);
    }

    private Device device(String status) {
        return new Device(deviceId, "DEV20261105000001", "CT-1", "CT", UUID.randomUUID(),
                "3F", "GE", "Revolution", "SN1", status, LocalDate.now().minusYears(1),
                DataScope.DEFAULT_CAMPUS_ID, Device.CAMPUS_RUNTIME, 0L, Instant.now());
    }

    private DeviceWorkOrder order(String status, String type, String createdBy) {
        return new DeviceWorkOrder(orderId, "WO20261105000001", deviceId,
                "DEV20261105000001", "CT-1", type, "NORMAL", "球管异响", null, createdBy,
                reportedAt, null, status, startedAt, null, null, null, null, null, null, null,
                createdBy, 0L, Instant.now());
    }

    private CreateWorkOrderReq createReq(String type) {
        return new CreateWorkOrderReq(type, "high", "  球管异响  ", null, null, "GE");
    }

    // ---------------------------------------------------------------- 建单

    @Test
    @DisplayName("建单：停用设备不能报修 → 409")
    void createRejectsRetiredDevice() {
        when(guard.requireDevice(scope, deviceId)).thenReturn(device(Device.RETIRED));

        assertThatThrownBy(() -> service.create(deviceId, createReq("CORRECTIVE"), "tech01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_DEVICE_STATUS_INVALID));
        verify(repo, never()).insertWorkOrder(any());
    }

    @Test
    @DisplayName("建单：归一化类型/优先级，落 OPEN 并留审计")
    void createNormalizesAndPersists() {
        when(guard.requireDevice(scope, deviceId)).thenReturn(device(Device.ACTIVE));
        when(repo.nextWorkOrderNo()).thenReturn("WO20261105000001");
        when(repo.findWorkOrder(any())).thenReturn(Optional.of(
                order(DeviceWorkOrder.OPEN, DeviceWorkOrder.CORRECTIVE, "tech01")));

        service.create(deviceId, createReq("corrective"), "tech01", scope);

        ArgumentCaptor<DeviceWorkOrder> captor = ArgumentCaptor.forClass(DeviceWorkOrder.class);
        verify(repo).insertWorkOrder(captor.capture());
        assertThat(captor.getValue().orderType()).isEqualTo(DeviceWorkOrder.CORRECTIVE);
        assertThat(captor.getValue().priority()).isEqualTo("HIGH");
        assertThat(captor.getValue().title()).isEqualTo("球管异响");
        assertThat(captor.getValue().status()).isEqualTo(DeviceWorkOrder.OPEN);
        assertThat(captor.getValue().createdBy()).isEqualTo("tech01");
        verify(repo).logChange(eq("tech01"), eq("DEVICE_WORK_ORDER_CREATE"),
                eq("DEVICE_WORK_ORDER"), anyString(), anyString());
    }

    @Test
    @DisplayName("建单：未知类型 → 422")
    void createRejectsUnknownType() {
        when(guard.requireDevice(scope, deviceId)).thenReturn(device(Device.ACTIVE));

        assertThatThrownBy(() -> service.create(deviceId, createReq("UNKNOWN"), "tech01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_INVALID));
    }

    @Test
    @DisplayName("建单：上报时间晚于当前一天以上 → 422（防脏时间进时间线）")
    void createRejectsFarFutureReportedAt() {
        when(guard.requireDevice(scope, deviceId)).thenReturn(device(Device.ACTIVE));

        assertThatThrownBy(() -> service.create(deviceId,
                new CreateWorkOrderReq("CORRECTIVE", null, "球管异响", null,
                        Instant.now().plusSeconds(3 * 86400L), null), "tech01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_INVALID));
    }

    // ---------------------------------------------------------------- 开始

    @Test
    @DisplayName("开始：只有 OPEN 可以开始 → 409")
    void startRejectsNonOpen() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.DONE, DeviceWorkOrder.CORRECTIVE,
                        "tech01")));

        assertThatThrownBy(() -> service.start(orderId, 0L, "tech01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_STATUS_INVALID));
        verify(repo, never()).startWorkOrder(any(), any(), anyLong());
    }

    @Test
    @DisplayName("开始：故障维修 → 设备置 MAINTENANCE")
    void startCorrectiveMarksDeviceMaintenance() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.OPEN, DeviceWorkOrder.CORRECTIVE,
                        "tech01")));
        when(repo.startWorkOrder(eq(orderId), any(), eq(0L))).thenReturn(true);

        service.start(orderId, 0L, "tech02", scope);

        verify(repo).markDeviceMaintenanceIfActive(deviceId);
    }

    @Test
    @DisplayName("开始：计划保养不改变设备状态")
    void startPreventiveLeavesDeviceStatus() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.OPEN, DeviceWorkOrder.PREVENTIVE,
                        "tech01")));
        when(repo.startWorkOrder(eq(orderId), any(), eq(0L))).thenReturn(true);

        service.start(orderId, 0L, "tech02", scope);

        verify(repo, never()).markDeviceMaintenanceIfActive(any());
    }

    @Test
    @DisplayName("开始：版本过期 → 409")
    void startRejectsStaleVersion() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.OPEN, DeviceWorkOrder.CORRECTIVE,
                        "tech01")));
        when(repo.startWorkOrder(eq(orderId), any(), eq(9L))).thenReturn(false);

        assertThatThrownBy(() -> service.start(orderId, 9L, "tech02", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    // ---------------------------------------------------------------- 完工

    @Test
    @DisplayName("完工：只有 IN_PROGRESS 可以完工 → 409")
    void finishRejectsNonInProgress() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.OPEN, DeviceWorkOrder.CORRECTIVE,
                        "tech01")));

        assertThatThrownBy(() -> service.finish(orderId,
                new FinishWorkOrderReq(0L, null, "更换球管", new BigDecimal("1200.00")), "tech02",
                scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_STATUS_INVALID));
    }

    @Test
    @DisplayName("完工：处理措施为空 → 422")
    void finishRequiresResolution() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.IN_PROGRESS,
                        DeviceWorkOrder.CORRECTIVE, "tech01")));

        assertThatThrownBy(() -> service.finish(orderId,
                new FinishWorkOrderReq(0L, null, "   ", null), "tech02", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_INVALID));
    }

    @Test
    @DisplayName("完工：完工时间早于开始时间 → 422")
    void finishRejectsTimeBeforeStart() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.IN_PROGRESS,
                        DeviceWorkOrder.CORRECTIVE, "tech01")));

        assertThatThrownBy(() -> service.finish(orderId,
                new FinishWorkOrderReq(0L, startedAt.minusSeconds(60), "更换球管", null), "tech02",
                scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_INVALID));
        verify(repo, never()).finishWorkOrder(any(), any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("完工：落处理措施与费用")
    void finishPersists() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.IN_PROGRESS,
                        DeviceWorkOrder.CORRECTIVE, "tech01")));
        when(repo.finishWorkOrder(eq(orderId), any(), eq("更换球管"), any(), eq(1L)))
                .thenReturn(true);

        service.finish(orderId,
                new FinishWorkOrderReq(1L, startedAt.plusSeconds(3600), "更换球管",
                        new BigDecimal("1200.00")), "tech02", scope);

        verify(repo).finishWorkOrder(orderId, startedAt.plusSeconds(3600), "更换球管",
                new BigDecimal("1200.00"), 1L);
    }

    // ---------------------------------------------------------------- 验收

    @Test
    @DisplayName("验收：只有 DONE 可以验收 → 409")
    void acceptRejectsNonDone() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.IN_PROGRESS,
                        DeviceWorkOrder.CORRECTIVE, "tech01")));

        assertThatThrownBy(() -> service.accept(orderId, new AcceptWorkOrderReq(0L, null),
                "qc01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_STATUS_INVALID));
    }

    @Test
    @DisplayName("验收：建单人不能自己验收 → 409（四眼）")
    void acceptRejectsSelfAcceptance() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.DONE, DeviceWorkOrder.CORRECTIVE,
                        "tech01")));

        assertThatThrownBy(() -> service.accept(orderId, new AcceptWorkOrderReq(0L, null),
                "tech01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_SELF_ACCEPT));
        verify(repo, never()).acceptWorkOrder(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("验收：他人验收后，若无其他未完结工单则设备恢复 ACTIVE")
    void acceptRestoresDeviceWhenIdle() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.DONE, DeviceWorkOrder.CORRECTIVE,
                        "tech01")));
        when(repo.acceptWorkOrder(eq(orderId), eq("qc01"), any(), eq(2L))).thenReturn(true);

        service.accept(orderId, new AcceptWorkOrderReq(2L, "验收合格"), "qc01", scope);

        verify(repo).restoreDeviceActiveIfIdle(deviceId, orderId);
        verify(repo).logChange(eq("qc01"), eq("DEVICE_WORK_ORDER_ACCEPT"),
                eq("DEVICE_WORK_ORDER"), anyString(), anyString());
    }

    // ---------------------------------------------------------------- 取消

    @Test
    @DisplayName("取消：终态工单不能重复取消 → 409")
    void cancelRejectsTerminal() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.ACCEPTED,
                        DeviceWorkOrder.CORRECTIVE, "tech01")));

        assertThatThrownBy(() -> service.cancel(orderId,
                new CancelWorkOrderReq(0L, "重复报修"), "tech01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_STATUS_INVALID));
    }

    @Test
    @DisplayName("取消：原因必填 → 422")
    void cancelRequiresReason() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.OPEN, DeviceWorkOrder.CORRECTIVE,
                        "tech01")));

        assertThatThrownBy(() -> service.cancel(orderId, new CancelWorkOrderReq(0L, "  "),
                "tech01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.TECH_WORK_ORDER_CANCEL_REASON_REQUIRED));
    }

    @Test
    @DisplayName("取消成功：留原因并尝试恢复设备状态")
    void cancelOk() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.OPEN, DeviceWorkOrder.CORRECTIVE,
                        "tech01")));
        when(repo.cancelWorkOrder(orderId, "重复报修", 0L)).thenReturn(true);

        service.cancel(orderId, new CancelWorkOrderReq(0L, "重复报修"), "tech01", scope);

        verify(repo).cancelWorkOrder(orderId, "重复报修", 0L);
        verify(repo).restoreDeviceActiveIfIdle(deviceId, orderId);
    }

    @Test
    @DisplayName("跨院区：工单所属设备不可见 → 404（越权与不存在同码）")
    void mutationsHideOtherCampusOrder() {
        when(repo.findWorkOrder(orderId))
                .thenReturn(Optional.of(order(DeviceWorkOrder.OPEN, DeviceWorkOrder.CORRECTIVE,
                        "tech01")));
        when(guard.requireDevice(scope, deviceId)).thenThrow(
                new BusinessException(ErrorCode.TECH_DEVICE_NOT_FOUND));

        assertThatThrownBy(() -> service.start(orderId, 0L, "tech02", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_DEVICE_NOT_FOUND));
        verify(repo, never()).startWorkOrder(any(), any(), anyLong());
    }

    // ---------------------------------------------------------------- 列表

    @Test
    @DisplayName("列表：状态非法 → 422；合法时归一化、收敛 limit 并下推院区")
    void listNormalizesAndClamps() {
        when(repo.listWorkOrders(any(), any(), any(), anyString(), anyInt()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.list(null, "UNKNOWN", null, 10, scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TECH_WORK_ORDER_INVALID));

        service.list(deviceId, "in_progress", "corrective", 500, scope);
        verify(repo).listWorkOrders(eq(deviceId), eq("IN_PROGRESS"),
                eq("CORRECTIVE"), eq(""), eq(200));

        UUID east = UUID.randomUUID();
        DataScope eastOnly = DataScope.of("eastadm01", java.util.Set.of(), true,
                java.util.Set.of(east), false, true);
        service.list(null, null, null, 10, eastOnly);
        verify(repo).listWorkOrders(eq(null), eq(null), eq(null), eq(east.toString()), eq(10));
    }
}
