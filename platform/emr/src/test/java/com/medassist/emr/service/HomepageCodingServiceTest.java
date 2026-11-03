package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.domain.EmrModel.CodingQueueItem;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.domain.EmrModel.HomepageCoding;
import com.medassist.emr.domain.EmrModel.IcdDiagnosis;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.CodingDiagnosisReq;
import com.medassist.emr.web.EmrDtos.SaveCodingReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 病案编码：字典校验、主诊断唯一、CODED 后回写首页、队列 PHI 掩码。
 */
class HomepageCodingServiceTest {

    private EmrRepository repo;
    private HomepageCodingService service;

    private final UUID homepageId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final DataScope scope = DataScope.all("qc01", true);
    private final DataScope maskedScope = DataScope.all("qc01", false);

    private final IcdDiagnosis dx = new IcdDiagnosis("I10.x00", "原发性高血压",
            "IX 循环系统疾病", "I10-I15", true, 0L);

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        service = new HomepageCodingService(repo, JsonMapper.builder().build());
    }

    // ---------------------------------------------------------------- 保存草稿

    @Test
    @DisplayName("保存：草稿态首页不允许编码（必须先提交病案室）")
    void saveRejectsDraftHomepage() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.DRAFT, 0)));

        assertThatThrownBy(() -> service.save(homepageId, saveReq("I10.x00", 0L), "coder01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_STATUS_INVALID);
    }

    @Test
    @DisplayName("保存：编码不在字典内 → 404（不允许自由文本编码）")
    void saveRejectsUnknownCode() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 0)));
        when(repo.findIcdDiagnoses(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.save(homepageId, saveReq("X99.999", 0L), "coder01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ICD_ITEM_NOT_FOUND);
        verify(repo, never()).insertCoding(any());
    }

    @Test
    @DisplayName("保存：诊断编码重复 → 422")
    void saveRejectsDuplicateCodes() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 0)));

        SaveCodingReq req = new SaveCodingReq(
                List.of(new CodingDiagnosisReq("I10.x00", true),
                        new CodingDiagnosisReq("I10.x00", false)), List.of(), null, 0L);

        assertThatThrownBy(() -> service.save(homepageId, req, "coder01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_INVALID);
    }

    @Test
    @DisplayName("保存：名称由字典回填（不接受客户端自造名称）")
    void saveUsesDictionaryName() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 0)));
        when(repo.findIcdDiagnoses(any())).thenReturn(List.of(dx));
        when(repo.findCoding(homepageId)).thenReturn(Optional.empty());

        service.save(homepageId, saveReq("I10.x00", 0L), "coder01", scope);

        ArgumentCaptor<HomepageCoding> captor = ArgumentCaptor.forClass(HomepageCoding.class);
        verify(repo).insertCoding(captor.capture());
        assertThat(captor.getValue().diagnoses()).contains("原发性高血压");
        assertThat(captor.getValue().status()).isEqualTo(HomepageCoding.CODING);
    }

    @Test
    @DisplayName("保存：已完成编码后拒绝再改（需走病案修改流程）")
    void saveRejectsAlreadyCoded() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 0)));
        when(repo.findIcdDiagnoses(any())).thenReturn(List.of(dx));
        when(repo.findCoding(homepageId)).thenReturn(Optional.of(coding(HomepageCoding.CODED,
                "[{\"code\":\"I10.x00\",\"name\":\"原发性高血压\",\"primary\":true}]", 1L)));

        assertThatThrownBy(() -> service.save(homepageId, saveReq("I10.x00", 1L), "coder01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_STATUS_INVALID);
    }

    @Test
    @DisplayName("保存：版本号过期 → 409（CAS 未命中）")
    void saveRejectsStaleVersion() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 0)));
        when(repo.findIcdDiagnoses(any())).thenReturn(List.of(dx));
        when(repo.findCoding(homepageId)).thenReturn(Optional.of(coding(HomepageCoding.CODING,
                "[{\"code\":\"I10.x00\",\"name\":\"原发性高血压\",\"primary\":true}]", 3L)));
        when(repo.updateCodingDraft(eq(homepageId), anyString(), anyString(), any(), anyString(),
                eq(1L))).thenReturn(false);

        assertThatThrownBy(() -> service.save(homepageId, saveReq("I10.x00", 1L), "coder01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 提交编码

    @Test
    @DisplayName("提交：主诊断缺失 → 422")
    void submitRequiresSinglePrimary() {
        when(repo.lockHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 0)));
        when(repo.findCoding(homepageId)).thenReturn(Optional.of(coding(HomepageCoding.CODING,
                "[{\"code\":\"I10.x00\",\"name\":\"原发性高血压\",\"primary\":false}]", 1L)));

        assertThatThrownBy(() -> service.submit(homepageId, "coder01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_INVALID);
        verify(repo, never()).markCoded(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("提交：CODED 后回写首页诊断/手术快照")
    void submitMarksCodedAndWritesBack() {
        when(repo.lockHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 4)));
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 4)));
        when(repo.findCoding(homepageId)).thenReturn(Optional.of(coding(HomepageCoding.CODING,
                "[{\"code\":\"I10.x00\",\"name\":\"原发性高血压\",\"primary\":true}]", 2L)));
        when(repo.markCoded(homepageId, "coder01", 2L)).thenReturn(true);
        when(repo.applyCodedSnapshot(eq(homepageId), anyString(), anyString(), eq("coder01"), eq(4L)))
                .thenReturn(true);

        service.submit(homepageId, "coder01", scope);

        verify(repo).markCoded(homepageId, "coder01", 2L);
        verify(repo).applyCodedSnapshot(eq(homepageId), anyString(), anyString(), eq("coder01"), eq(4L));
    }

    @Test
    @DisplayName("提交：已 CODED 时幂等返回，不重复回写")
    void submitIsIdempotentWhenCoded() {
        when(repo.lockHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 4)));
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 4)));
        when(repo.findCoding(homepageId)).thenReturn(Optional.of(coding(HomepageCoding.CODED,
                "[{\"code\":\"I10.x00\",\"name\":\"原发性高血压\",\"primary\":true}]", 3L)));

        service.submit(homepageId, "coder01", scope);

        verify(repo, never()).markCoded(any(), anyString(), anyLong());
        verify(repo, never()).applyCodedSnapshot(any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("提交：没有编码记录 → 404")
    void submitRejectsMissingCoding() {
        when(repo.lockHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 4)));
        when(repo.findCoding(homepageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(homepageId, "coder01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_NOT_FOUND);
    }

    // ---------------------------------------------------------------- 队列

    @Test
    @DisplayName("队列：无 phi:read 时患者号掩码返回")
    void queueMasksPatientNo() {
        when(repo.listCodingQueue(null, 100)).thenReturn(List.of(new CodingQueueItem(homepageId,
                "HP20261103000001", "P20261030000001", "张**", "内科", Instant.now(), 5,
                "PENDING", null, null, 2L, 0L)));

        List<CodingQueueItem> rows = service.queue(null, null, maskedScope);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).patientNo()).contains("*").isNotEqualTo("P20261030000001");
        assertThat(rows.get(0).patientNameMasked()).isEqualTo("张**");
    }

    // ---------------------------------------------------------------- 夹具

    private SaveCodingReq saveReq(String code, long version) {
        return new SaveCodingReq(List.of(new CodingDiagnosisReq(code, true)), List.of(), null,
                version);
    }

    private HomepageCoding coding(String status, String diagnoses, long version) {
        return new HomepageCoding(UUID.randomUUID(), homepageId, status, "coder01",
                HomepageCoding.CODED.equals(status) ? Instant.now() : null, null, diagnoses, "[]",
                version, Instant.now(), Instant.now());
    }

    private Homepage homepage(String status, long version) {
        boolean submitted = Homepage.SUBMITTED.equals(status);
        return new Homepage(homepageId, "HP20261103000001", UUID.randomUUID(), null,
                UUID.randomUUID(), "P20261030000001", "张三", "M", LocalDate.of(1980, 1, 1), 46,
                "V001", UUID.randomUUID(), "内科", null, "一病区", "01",
                Instant.now().minusSeconds(86400 * 5), Instant.now().minusSeconds(3600), 5,
                "李医生", null, null, null, null, "NONE", null, null, null, null, null,
                0, 0, false, false, null, null, null,
                "[]", "[]", "{}", new BigDecimal("100.00"), status,
                submitted ? "admin01" : null, submitted ? submitterId : null,
                submitted ? Instant.now() : null, null, null, version, Instant.now());
    }
}
