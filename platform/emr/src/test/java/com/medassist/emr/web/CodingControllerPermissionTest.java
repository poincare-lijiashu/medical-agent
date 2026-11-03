package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.service.HomepageCodingService;
import com.medassist.emr.service.IcdDictionaryService;
import com.medassist.emr.web.EmrDtos.CodingDiagnosisReq;
import com.medassist.emr.web.EmrDtos.IcdDiagnosisReq;
import com.medassist.emr.web.EmrDtos.IcdToggleReq;
import com.medassist.emr.web.EmrDtos.SaveCodingReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 病案编码接口权限矩阵：字典维护、编码读写、编码提交三层分权。
 */
class CodingControllerPermissionTest {

    private IcdDictionaryService icdService;
    private HomepageCodingService codingService;
    private IcdController icdController;
    private CodingController codingController;

    private final UUID homepageId = UUID.randomUUID();
    private final DataScope scope = DataScope.all("coder01", true);

    @BeforeEach
    void setUp() {
        icdService = mock(IcdDictionaryService.class);
        codingService = mock(HomepageCodingService.class);
        icdController = new IcdController(icdService);
        codingController = new CodingController(codingService);
    }

    @Test
    @DisplayName("ICD 字典新增：只有 emr:icd:manage 放行")
    void icdCreateRequiresManage() {
        assertThatThrownBy(() -> icdController.createDiagnosis(
                new IcdDiagnosisReq("I10.x00", "原发性高血压", null, null),
                "emr:coding:write", "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(icdService, never()).createDiagnosis(any(), anyString());
    }

    @Test
    @DisplayName("ICD 字典启停：只有 emr:icd:manage 放行")
    void icdToggleRequiresManage() {
        assertThatThrownBy(() -> icdController.toggleDiagnosis("I10.x00",
                new IcdToggleReq(false, 0L), "emr:icd:read", "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("编码队列：emr:coding:read 放行")
    void queueAllowsRead() {
        codingController.queue(null, null, "emr:coding:read", scope);

        verify(codingService).queue(null, null, scope);
    }

    @Test
    @DisplayName("保存编码：需要 emr:coding:write（只读不能改编码）")
    void saveRequiresWrite() {
        SaveCodingReq req = new SaveCodingReq(
                List.of(new CodingDiagnosisReq("I10.x00", true)), List.of(), null, 0L);

        assertThatThrownBy(() -> codingController.save(homepageId, req, "emr:coding:read",
                "coder01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(codingService, never()).save(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("提交编码：需要 emr:coding:submit（写草稿不等于能提交）")
    void submitRequiresSubmitPermission() {
        assertThatThrownBy(() -> codingController.submit(homepageId, "emr:coding:write",
                "coder01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(codingService, never()).submit(any(), anyString(), any());
    }
}
