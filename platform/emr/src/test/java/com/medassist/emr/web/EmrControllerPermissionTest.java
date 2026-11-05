package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.service.EmrAccessGuard;
import com.medassist.emr.service.RecordService;
import com.medassist.emr.web.EmrDtos.CreateRecordReq;
import com.medassist.emr.web.EmrDtos.ReviewReq;
import com.medassist.emr.web.EmrDtos.SignReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 病历接口权限矩阵（直接驱动 Controller，不启动 Spring 上下文）。
 *
 * <p>锁住 V13/V14 的授权边界：书写/提交/签名/归档/修订归医师；
 * 病案修改审批是独立权限点（emr:record:approve），医师不得自审；
 * 只读权限不得触发任何写操作。
 */
class EmrControllerPermissionTest {

    private RecordService service;
    private RecordController controller;

    private final UUID id = UUID.randomUUID();
    /** 本类只测权限点；科室可见性由 EmrAccessGuard 与其单测覆盖。 */
    private final DataScope scope = DataScope.all("admin01", true);

    @BeforeEach
    void setUp() {
        service = mock(RecordService.class);
        controller = new RecordController(service, mock(EmrAccessGuard.class));
    }

    @Test
    @DisplayName("书写：只读权限不能建病历")
    void createRequiresCreatePermission() {
        var req = new CreateRecordReq(id, null, null, null, "TPL-ADMISSION", null, Map.of());

        assertThatThrownBy(() -> controller.create(req, "emr:record:read", "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("签名：只读权限不能签名")
    void signRequiresSignPermission() {
        var req = new SignReq("AUTHOR", null);

        assertThatThrownBy(() -> controller.sign(id, req, "emr:record:read", "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).sign(any(), any(), anyString());
    }

    @Test
    @DisplayName("归档：修订权限不等于归档权限")
    void archiveRequiresArchivePermission() {
        assertThatThrownBy(() -> controller.archive(id, "emr:record:amend", "doctor01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).archive(any(), anyString());
    }

    @Test
    @DisplayName("病案修改审批：医师只有修订权，不能审批（需 emr:record:approve）")
    void approveRequiresApprovePermission() {
        var req = new ReviewReq("同意");

        assertThatThrownBy(() -> controller.approveChange(id, id, req,
                "emr:record:amend", "doctor01", null, scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).approveChange(any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("病案修改审批：质控审批权限放行")
    void approveAllowedForQcPermission() {
        var req = new ReviewReq("同意");

        controller.approveChange(id, id, req, "emr:record:approve", "qc01", null, scope);

        verify(service).approveChange(id, id, req, "qc01", null);
    }

    @Test
    @DisplayName("只读查询：emr:record:read 放行")
    void detailAllowedForReadPermission() {
        // P0-S2：detail 现在对记录做 PHI 掩码，桩需返回非空视图
        var record = new com.medassist.emr.domain.EmrModel.MedicalRecord(id, "MR0001",
                UUID.randomUUID(), "P0001", UUID.randomUUID(), "V0001", UUID.randomUUID(),
                "NOTE", "TPL", "标题", "内容", "内容", "doctor01",
                com.medassist.emr.domain.EmrModel.MedicalRecord.DRAFT, 1,
                null, null, null, null, 0L, java.time.Instant.now());
        when(service.detail(id)).thenReturn(new com.medassist.emr.web.EmrDtos.RecordView(
                record, List.of(), List.of(), List.of()));

        controller.detail(id, "emr:record:read", scope);

        verify(service).detail(id);
    }

    @Test
    @DisplayName("CDR 检索：只有 emr:record:search 可用，只读权限不够（V17）")
    void searchRequiresSearchPermission() {
        assertThatThrownBy(() -> controller.search("x", null, null, null, null, null, null,
                "emr:record:read", "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).search(any(), any(), any(), any(), any(), any(), any(),
                anyString(), any(), any());

        controller.search("x", null, null, null, null, null, null,
                "emr:record:search", "doctor01", scope);
        // G74：科室条件下推 SQL；本 scope 是全院科室角色，故 deptIds 传 null
        verify(service).search("x", null, null, null, null, null, null, "doctor01",
                scope.campusFilter(), null);
    }

    @Test
    @DisplayName("病历列表：单科室账号把科室集合下推服务层（G74）")
    void listPushesDeptFilterDown() {
        UUID deptId = UUID.randomUUID();
        DataScope scoped = DataScope.ofDepartments("doctor01", java.util.Set.of(deptId), false);
        when(service.list(null, null, null, 10, java.util.Set.of(), java.util.Set.of(deptId)))
                .thenReturn(List.of());

        var resp = controller.list(null, null, null, 10, "emr:record:read", scoped);

        assertThat(resp.data()).isEmpty();
        verify(service).list(null, null, null, 10, java.util.Set.of(), java.util.Set.of(deptId));
    }

    @Test
    @DisplayName("打印：只读权限放行，并把操作人传给服务层留痕")
    void printAllowedForReadPermission() {
        controller.print(id, "emr:record:read", "nurse01", scope);

        verify(service).printHtml(id, "nurse01");
    }

    @Test
    @DisplayName("权限头缺失：提示重新登录（403）")
    void emptyPermissionsHeader() {
        assertThatThrownBy(() -> controller.detail(id, null, scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
