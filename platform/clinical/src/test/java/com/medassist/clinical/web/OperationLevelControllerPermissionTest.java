package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.OperationLevel;
import com.medassist.clinical.service.OperationLevelCatalogService;
import com.medassist.clinical.web.SurgeryLevelDtos.UpsertOperationLevelReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分级目录权限：读随手术记录；写单列 clinical:surgery:catalog:write（全院口径）。
 *
 * <p>关键断言：有 {@code clinical:surgery:write}（能录手术）不等于能改目录。
 */
class OperationLevelControllerPermissionTest {

    private OperationLevelCatalogService service;
    private OperationLevelController controller;

    private final String code = "81.5401";

    @BeforeEach
    void setUp() {
        service = mock(OperationLevelCatalogService.class);
        controller = new OperationLevelController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("列表：clinical:surgery:read 即可")
    void listRequiresRead() {
        when(service.list(null, 100)).thenReturn(List.of());

        assertForbidden(() -> controller.list(null, 100, "clinical:surgery:catalog:write"));
        controller.list(null, 100, "clinical:surgery:read");

        verify(service).list(null, 100);
    }

    @Test
    @DisplayName("维护：录手术权限不能改目录，必须 catalog:write")
    void upsertRequiresCatalogWrite() {
        when(service.upsert(any(), any(), any()))
                .thenReturn(new OperationLevel(code, 3, null, "admin01", 0L, Instant.now(),
                        Instant.now()));

        assertForbidden(() -> controller.upsert(code,
                new UpsertOperationLevelReq(3, null, null), "clinical:surgery:write", "doctor01"));
        controller.upsert(code, new UpsertOperationLevelReq(3, null, null),
                "clinical:surgery:catalog:write", "admin01");

        verify(service).upsert(code, new UpsertOperationLevelReq(3, null, null), "admin01");
    }

    @Test
    @DisplayName("删除：同样要求 catalog:write")
    void deleteRequiresCatalogWrite() {
        assertForbidden(() -> controller.delete(code, 0L, "clinical:surgery:write", "doctor01"));
        controller.delete(code, 0L, "clinical:surgery:catalog:write", "admin01");

        verify(service).delete(code, 0L, "admin01");
    }
}
