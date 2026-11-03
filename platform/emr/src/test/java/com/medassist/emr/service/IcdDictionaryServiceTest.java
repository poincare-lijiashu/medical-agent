package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.IcdDiagnosis;
import com.medassist.emr.domain.EmrModel.IcdProcedure;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.IcdDiagnosisReq;
import com.medassist.emr.web.EmrDtos.IcdProcedureReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ICD 字典：编码唯一、停用不删除、启停幂等与版本号冲突。
 */
class IcdDictionaryServiceTest {

    private EmrRepository repo;
    private IcdDictionaryService service;

    private final IcdDiagnosis dx = new IcdDiagnosis("I10.x00", "原发性高血压",
            "IX 循环系统疾病", "I10-I15", true, 2L);
    private final IcdProcedure op = new IcdProcedure("47.0901", "阑尾切除术",
            "消化系统手术", true, 1L);

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        service = new IcdDictionaryService(repo, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("新增诊断编码：已存在 → 409")
    void createRejectsDuplicateDiagnosis() {
        when(repo.findIcdDiagnosis("I10.x00")).thenReturn(Optional.of(dx));

        assertThatThrownBy(() -> service.createDiagnosis(
                new IcdDiagnosisReq("I10.x00", "原发性高血压", null, null), "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).insertIcdDiagnosis(any());
    }

    @Test
    @DisplayName("新增诊断编码：正常入库并留审计")
    void createStoresDiagnosis() {
        when(repo.findIcdDiagnosis("E11.900")).thenReturn(Optional.empty());

        IcdDiagnosis created = service.createDiagnosis(
                new IcdDiagnosisReq(" E11.900 ", "2型糖尿病", "IV 内分泌", "E10-E14"), "coder01");

        assertThat(created.code()).isEqualTo("E11.900");
        assertThat(created.enabled()).isTrue();
        verify(repo).insertIcdDiagnosis(any());
        verify(repo).logChange(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("新增手术编码：重复 → 409")
    void createRejectsDuplicateProcedure() {
        when(repo.findIcdProcedure("47.0901")).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.createProcedure(
                new IcdProcedureReq("47.0901", "阑尾切除术", null), "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("启停：已是目标状态直接返回，不发 UPDATE（双击不报错）")
    void toggleIsIdempotent() {
        when(repo.findIcdDiagnosis("I10.x00")).thenReturn(Optional.of(dx));

        service.toggleDiagnosis("I10.x00", true, 2L, "coder01");

        verify(repo, never()).setIcdDiagnosisEnabled(anyString(), anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("启停：版本号过期 → 409")
    void toggleRejectsStaleVersion() {
        when(repo.findIcdDiagnosis("I10.x00")).thenReturn(Optional.of(dx));
        when(repo.setIcdDiagnosisEnabled("I10.x00", false, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.toggleDiagnosis("I10.x00", false, 1L, "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("启停：编码不存在 → 404")
    void toggleRejectsUnknownCode() {
        when(repo.findIcdProcedure("99.9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggleProcedure("99.9999", false, 0L, "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ICD_ITEM_NOT_FOUND);
    }
}
