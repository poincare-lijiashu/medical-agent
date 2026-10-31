package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.PacsDtos.RegisterStudyReq;
import com.medassist.tech.web.PacsDtos.UpdateStudyStatusReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
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
 * 影像集成：登记幂等/冲突、状态机与 CAS、Worklist 过滤与上限。
 */
class PacsServiceTest {

    private static final String UID = "1.2.840.113619.2.55.3.20261031.1";
    private static final String MODALITY = "CT";

    private TechRepository repo;
    private PacsService service;

    private final UUID appId = UUID.randomUUID();
    private final String examCode = "EXM-CT-CHEST";
    private final String accession = "AP20261031000001";

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        service = new PacsService(repo, JsonMapper.builder().build());
        when(repo.lockApplication(appId)).thenReturn(Optional.of(application("EXAM", Application.APPLIED)));
    }

    private Application application(String category, String status) {
        return new Application(appId, accession, UUID.randomUUID(), "P20261031000001",
                null, null, null, "doctor01", category, examCode, "胸部CT平扫", "胸部",
                "URGENT", "咳嗽待查", null, status, 0L, Instant.now());
    }

    private PacsStudy study(String uid, String status) {
        return new PacsStudy(UUID.randomUUID(), appId, uid, accession, MODALITY, "胸部",
                status, 0, 0, null, null, null, 0L, Instant.now());
    }

    private RegisterStudyReq registerReq(String uid) {
        return new RegisterStudyReq(appId, uid, null, null, null);
    }

    @Test
    @DisplayName("登记：Modality 缺省取项目字典，AccessionNumber 复用申请号，写审计")
    void registerUsesItemModalityAndAccession() {
        PacsStudy stored = study(UID, PacsStudy.SCHEDULED);
        when(repo.findPacsStudyByApplication(appId)).thenReturn(Optional.empty());
        when(repo.findItemModality(examCode)).thenReturn(Optional.of(MODALITY));
        when(repo.findPacsStudyByUid(UID)).thenReturn(Optional.of(stored));

        PacsStudy result = service.register(registerReq(UID), "tech01");

        assertThat(result.modality()).isEqualTo(MODALITY);
        assertThat(result.accessionNumber()).isEqualTo(accession);
        assertThat(result.status()).isEqualTo(PacsStudy.SCHEDULED);
        verify(repo).insertPacsStudy(any(PacsStudy.class));
        verify(repo).logChange(eq("tech01"), eq("PACS_STUDY_REGISTER"), eq("PACS_STUDY"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("登记：同申请同 UID 幂等返回，不再插入")
    void registerIsIdempotentForSameUid() {
        PacsStudy existing = study(UID, PacsStudy.SCHEDULED);
        when(repo.findPacsStudyByApplication(appId)).thenReturn(Optional.of(existing));

        PacsStudy result = service.register(registerReq(UID), "tech01");

        assertThat(result).isSameAs(existing);
        verify(repo, never()).insertPacsStudy(any());
    }

    @Test
    @DisplayName("登记：同申请不同 UID 冲突")
    void registerRejectsDifferentUid() {
        when(repo.findPacsStudyByApplication(appId))
                .thenReturn(Optional.of(study("1.2.3.4.5", PacsStudy.SCHEDULED)));

        assertThatThrownBy(() -> service.register(registerReq(UID), "tech01"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("登记：检验申请与已报告申请都不允许")
    void registerRejectsWrongCategoryOrStatus() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(application("LAB", Application.APPLIED)));
        assertThatThrownBy(() -> service.register(registerReq(UID), "tech01"))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        when(repo.lockApplication(appId)).thenReturn(Optional.of(application("EXAM", Application.REPORTED)));
        assertThatThrownBy(() -> service.register(registerReq(UID), "tech01"))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.TECH_APPLICATION_STATUS_INVALID));
    }

    @Test
    @DisplayName("状态回传：SCHEDULED → IN_PROGRESS 带影像数量，CAS 版本号正确")
    void updateStatusAdvancesWithCounts() {
        when(repo.lockPacsStudyByUid(UID)).thenReturn(Optional.of(study(UID, PacsStudy.SCHEDULED)));
        when(repo.updatePacsStudyStatus(UID, PacsStudy.SCHEDULED, PacsStudy.IN_PROGRESS,
                2, 120, null, 0L)).thenReturn(true);
        when(repo.findPacsStudyByUid(UID)).thenReturn(Optional.of(study(UID, PacsStudy.IN_PROGRESS)));

        PacsStudy result = service.updateStatus(UID,
                new UpdateStudyStatusReq("in_progress", 2, 120, null), "pacs01");

        assertThat(result.status()).isEqualTo(PacsStudy.IN_PROGRESS);
        verify(repo).updatePacsStudyStatus(UID, PacsStudy.SCHEDULED, PacsStudy.IN_PROGRESS,
                2, 120, null, 0L);
    }

    @Test
    @DisplayName("状态回传：跳过中间态或回退一律拒绝")
    void updateStatusRejectsIllegalTransition() {
        when(repo.lockPacsStudyByUid(UID)).thenReturn(Optional.of(study(UID, PacsStudy.SCHEDULED)));

        assertThatThrownBy(() -> service.updateStatus(UID,
                new UpdateStudyStatusReq("COMPLETED", null, null, null), "pacs01"))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.TECH_PACS_STATUS_INVALID));

        when(repo.lockPacsStudyByUid(UID)).thenReturn(Optional.of(study(UID, PacsStudy.COMPLETED)));
        assertThatThrownBy(() -> service.updateStatus(UID,
                new UpdateStudyStatusReq("IN_PROGRESS", null, null, null), "pacs01"))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.TECH_PACS_STATUS_INVALID));
    }

    @Test
    @DisplayName("状态回传：CAS 失败返回 409")
    void updateStatusCasConflict() {
        when(repo.lockPacsStudyByUid(UID)).thenReturn(Optional.of(study(UID, PacsStudy.ARRIVED)));
        when(repo.updatePacsStudyStatus(anyString(), anyString(), anyString(),
                any(), any(), any(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.updateStatus(UID,
                new UpdateStudyStatusReq("IN_PROGRESS", null, null, null), "pacs01"))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("Worklist：Modality 转大写、limit 上限 200、默认当天")
    void worklistNormalizesAndClamps() {
        when(repo.listWorklist(MODALITY, BusinessTime.today(), BusinessTime.zone().getId(), 200))
                .thenReturn(List.of());

        service.worklist("ct", null, 1000);

        verify(repo).listWorklist(MODALITY, BusinessTime.today(), BusinessTime.zone().getId(), 200);
    }

    @Test
    @DisplayName("Worklist：非法 Modality 直接拒绝")
    void worklistRejectsInvalidModality() {
        assertThatThrownBy(() -> service.worklist("C", null, 10))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("按 UID 查询不存在 → 46406")
    void byUidNotFound() {
        when(repo.findPacsStudyByUid("1.2.3")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.byUid("1.2.3"))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.TECH_PACS_STUDY_NOT_FOUND));
    }
}
