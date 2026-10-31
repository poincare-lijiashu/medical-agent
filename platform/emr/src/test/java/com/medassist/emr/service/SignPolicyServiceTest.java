package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.SignPolicy;
import com.medassist.emr.repo.EmrRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;

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
 * 签名策略管理（N-04）：创建/修改的 CAS 语义与审计 old→new。
 */
class SignPolicyServiceTest {

    private EmrRepository repo;
    private SignPolicyService service;

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        service = new SignPolicyService(repo, JsonMapper.builder().build());
    }

    private SignPolicy policy(String docType, int level, long version) {
        return new SignPolicy(docType, level, version, "qc01", Instant.now());
    }

    @Test
    @DisplayName("创建：策略不存在时插入并把 before 记为 null")
    void upsertCreatesMissingPolicy() {
        when(repo.findSignPolicy("CONSULT_NOTE")).thenReturn(Optional.empty());
        when(repo.insertSignPolicy("CONSULT_NOTE", 2, "qc01")).thenReturn(true);
        when(repo.findSignPolicy("CONSULT_NOTE"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(policy("CONSULT_NOTE", 2, 0L)));

        SignPolicy saved = service.upsert("consult_note", 2, null, "qc01");

        assertThat(saved.requiredLevel()).isEqualTo(2);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repo).logChange(eq("qc01"), eq("SIGN_POLICY_UPSERT"), eq("SIGN_POLICY"),
                eq("CONSULT_NOTE"), detail.capture());
        assertThat(detail.getValue()).contains("\"before\":null", "\"after\":2", "CREATE");
    }

    @Test
    @DisplayName("创建：并发重复创建（ON CONFLICT 0 行）转 409")
    void upsertCreateConflict() {
        when(repo.findSignPolicy("CONSULT_NOTE")).thenReturn(Optional.empty());
        when(repo.insertSignPolicy("CONSULT_NOTE", 2, "qc01")).thenReturn(false);

        assertThatThrownBy(() -> service.upsert("CONSULT_NOTE", 2, null, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).logChange(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("修改：不携带版本号直接拒绝，避免静默覆盖")
    void upsertUpdateRequiresVersion() {
        when(repo.findSignPolicy("DISCHARGE_SUMMARY"))
                .thenReturn(Optional.of(policy("DISCHARGE_SUMMARY", 3, 4L)));

        assertThatThrownBy(() -> service.upsert("DISCHARGE_SUMMARY", 1, null, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_SIGN_POLICY_INVALID);
        verify(repo, never()).updateSignPolicy(anyString(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("修改：版本号过期（CAS 0 行）转 409，不写审计")
    void upsertUpdateCasConflict() {
        when(repo.findSignPolicy("DISCHARGE_SUMMARY"))
                .thenReturn(Optional.of(policy("DISCHARGE_SUMMARY", 3, 4L)));
        when(repo.updateSignPolicy("DISCHARGE_SUMMARY", 3, 3L, "qc01")).thenReturn(false);

        assertThatThrownBy(() -> service.upsert("DISCHARGE_SUMMARY", 3, 3L, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).logChange(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("修改：CAS 成功，审计记录 old→new")
    void upsertUpdateWritesAuditWithBeforeAndAfter() {
        when(repo.findSignPolicy("PROGRESS_NOTE"))
                .thenReturn(Optional.of(policy("PROGRESS_NOTE", 1, 2L)))
                .thenReturn(Optional.of(policy("PROGRESS_NOTE", 2, 3L)));
        when(repo.updateSignPolicy("PROGRESS_NOTE", 2, 2L, "qc01")).thenReturn(true);

        SignPolicy saved = service.upsert("PROGRESS_NOTE", 2, 2L, "qc01");

        assertThat(saved.requiredLevel()).isEqualTo(2);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repo).logChange(eq("qc01"), eq("SIGN_POLICY_UPSERT"), eq("SIGN_POLICY"),
                eq("PROGRESS_NOTE"), detail.capture());
        assertThat(detail.getValue()).contains("\"before\":1", "\"after\":2", "UPDATE");
    }

    @Test
    @DisplayName("文书类型不合法：拒绝且不触库")
    void upsertRejectsUnknownDocType() {
        assertThatThrownBy(() -> service.upsert("NOT_A_DOC_TYPE", 1, null, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_SIGN_POLICY_INVALID);
        verify(repo, never()).findSignPolicy(anyString());
    }

    @Test
    @DisplayName("签名级别越界：拒绝")
    void upsertRejectsInvalidLevel() {
        assertThatThrownBy(() -> service.upsert("PROGRESS_NOTE", 4, null, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_SIGN_POLICY_INVALID);
    }
}
