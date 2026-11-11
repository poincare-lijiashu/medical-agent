package com.medassist.masterdata.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.domain.MasterDataModel.ChangeRequest;
import com.medassist.masterdata.domain.MasterDataModel.ImportBatch;
import com.medassist.masterdata.service.MasterDataImportService;
import com.medassist.masterdata.service.MasterDataService;
import com.medassist.masterdata.service.MasterDataSyncService;
import com.medassist.masterdata.web.MasterDataDtos.ItemCreateReq;
import com.medassist.masterdata.web.MasterDataDtos.ItemStatusReq;
import com.medassist.masterdata.web.MasterDataDtos.MappingDeleteReq;
import com.medassist.masterdata.web.MasterDataDtos.MappingReq;
import com.medassist.masterdata.web.MasterDataDtos.RejectReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主数据权限矩阵：查询 / 提交 / 审批 / 导入 / 审计五权分离。
 */
class MasterDataControllerPermissionTest {

    private MasterDataService service;
    private MasterDataImportService importService;
    private MasterDataSyncService syncService;
    private MasterDataController controller;

    @BeforeEach
    void setUp() {
        service = mock(MasterDataService.class);
        importService = mock(MasterDataImportService.class);
        syncService = mock(MasterDataSyncService.class);
        controller = new MasterDataController(service, importService, syncService);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询/详情/映射导出：必须 mdm:read")
    void readPermissions() {
        when(service.listDomains()).thenReturn(List.of());
        assertForbidden(() -> controller.domains("mdm:write"));
        assertThat(controller.domains("mdm:read").data()).isEmpty();

        assertForbidden(() -> controller.item("DEPT", "D1", "mdm:write"));
        assertForbidden(() -> controller.exportMappings(null, null, "mdm:import"));
    }

    @Test
    @DisplayName("提交变更：必须 mdm:write，且把原因与字段正确传给服务层")
    void writePermissions() {
        ItemCreateReq req = new ItemCreateReq("心内科", null, null, null, null, null,
                null, null, Map.of(), "新开科室");
        assertForbidden(() -> controller.submitCreate("DEPT", "N1", req, "mdm:read",
                "alice", null, null));
        verify(service, never()).submit(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(), any());

        when(service.submit(eq(ChangeRequest.ITEM_CREATE), eq("DEPT"), eq("N1"), any(),
                eq("新开科室"), eq("alice"), any(), any())).thenReturn(null);
        controller.submitCreate("DEPT", "N1", req, "mdm:write", "alice", null, null);
        verify(service).submit(eq(ChangeRequest.ITEM_CREATE), eq("DEPT"), eq("N1"), any(),
                eq("新开科室"), eq("alice"), any(), any());
    }

    @Test
    @DisplayName("审批/驳回：必须 mdm:approve；停用映射必须 mdm:write")
    void approvePermissions() {
        UUID id = UUID.randomUUID();
        assertForbidden(() -> controller.approve(id, null, "mdm:write", "bob", null, null));
        assertForbidden(() -> controller.reject(id, new RejectReq("资料不全"), "mdm:write",
                "bob", null, null));

        when(service.reject(eq(id), eq("资料不全"), eq("bob"), any(), any())).thenReturn(null);
        controller.reject(id, new RejectReq("资料不全"), "mdm:approve", "bob", null, null);
        verify(service).reject(eq(id), eq("资料不全"), eq("bob"), any(), any());

        MappingDeleteReq delete = new MappingDeleteReq("HIS", "H-1", "停用对照");
        assertForbidden(() -> controller.submitMappingDelete("DEPT", "D1", delete, "mdm:read",
                "alice", null, null));
    }

    @Test
    @DisplayName("源同步与批量导入：必须 mdm:import")
    void importPermissions() {
        assertForbidden(() -> controller.sync("DEPT", false, "mdm:read", "alice", null, null));
        assertForbidden(() -> controller.previewCsv("a,b\n", "f.csv", "mdm:write",
                "alice", null, null));

        when(syncService.sync(eq("DEPT"), eq(true), eq("alice"), any(), any())).thenReturn(null);
        controller.sync("DEPT", true, "mdm:import", "alice", null, null);
        verify(syncService).sync(eq("DEPT"), eq(true), eq("alice"), any(), any());
    }

    @Test
    @DisplayName("审计查询：必须 mdm:audit:read")
    void auditPermissions() {
        assertForbidden(() -> controller.audit("DEPT", null, null, 10, "mdm:read"));
        when(service.listAudit("DEPT", null, null, 10)).thenReturn(List.of());
        assertThat(controller.audit("DEPT", null, null, 10, "mdm:audit:read").data()).isEmpty();
    }

    @Test
    @DisplayName("映射导出：CSV + BOM + 附件文件名")
    void exportCsv() {
        when(service.exportMappingsCsv("DEPT", "HIS")).thenReturn("\uFEFF域编码,本地编码\r\nDEPT,D1\r\n");

        ResponseEntity<byte[]> response = controller.exportMappings("DEPT", "HIS", "mdm:read");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("mdm-mappings.csv");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).startsWith("\uFEFF");
    }

    @Test
    @DisplayName("JSON 导入预检：需要 mdm:import 且返回批次")
    void previewJson() {
        ImportBatch batch = new ImportBatch(UUID.randomUUID(), "MDM-IMP-1", ImportBatch.PREVIEW,
                "JSON", "DEPT", "f.json", "c", 1, 1, 0, 0, 0, 0, ImportBatch.PREVIEWED,
                Map.of(), "alice", Instant.now(), List.of());
        when(importService.previewJson(eq("DEPT"), eq("f.json"), any(), eq("alice"), any(), any()))
                .thenReturn(batch);
        MasterDataDtos.JsonImportReq req = new MasterDataDtos.JsonImportReq("DEPT", "f.json",
                List.of(new MasterDataDtos.ImportItemReq("N1", "心内科", null, null, null, null,
                        null, null, null, Map.of(), List.of())));

        assertForbidden(() -> controller.previewJson(req, "mdm:read", "alice", null, null));
        assertThat(controller.previewJson(req, "mdm:import", "alice", null, null).data()
                .batchNo()).isEqualTo("MDM-IMP-1");
    }

    @Test
    @DisplayName("启停与映射申请：字段转换不丢信息")
    void submitStatusAndMapping() {
        when(service.submit(anyString(), anyString(), anyString(), any(), any(), anyString(),
                any(), any())).thenReturn(null);
        controller.submitStatus("DEPT", "D1", new ItemStatusReq("INACTIVE", "科室合并停用"),
                "mdm:write", "alice", null, null);
        verify(service).submit(eq(ChangeRequest.ITEM_STATUS), eq("DEPT"), eq("D1"),
                eq(Map.of("status", "INACTIVE")), eq("科室合并停用"), eq("alice"), any(), any());

        controller.submitMapping("DEPT", "D1", new MappingReq("HIS", "H-1", "内科", "EXACT",
                        true, "ACTIVE", null, null, "主对照", "建立对照"),
                "mdm:write", "alice", null, null);
        verify(service).submit(eq(ChangeRequest.MAPPING_UPSERT), eq("DEPT"), eq("D1"), any(),
                eq("建立对照"), eq("alice"), any(), any());
    }

    @Test
    @DisplayName("未使用参数不改变权限判定（防止权限注解被后续重构遗漏）")
    void guardSanity() {
        assertForbidden(() -> controller.imports("DEPT", "JSON", 10, null));
        when(importService.listBatches(eq("DEPT"), eq("JSON"), eq(10))).thenReturn(List.of());
        assertThat(controller.imports("DEPT", "JSON", 10, "mdm:read").data()).isEmpty();
        verify(service, never()).listDomains();
    }
}
