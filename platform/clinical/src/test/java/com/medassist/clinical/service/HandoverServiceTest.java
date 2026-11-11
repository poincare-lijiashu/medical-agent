package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Handover;
import com.medassist.clinical.domain.ClinicalModel.HandoverItem;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.HandoverItemReq;
import com.medassist.clinical.web.ClinicalDtos.HandoverSaveReq;
import com.medassist.clinical.web.ClinicalDtos.HandoverView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * 交班报告：实时清单风险项、草稿叠加备注、签字冻结、签字后不可改。
 */
class HandoverServiceTest {

    private ClinicalRepository repo;
    private HandoverService service;

    private final UUID wardId = UUID.randomUUID();
    private final Ward ward = new Ward(wardId, "W01", "内科一病区", UUID.randomUUID(), true);
    private final LocalDate today = BusinessTime.today();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new HandoverService(repo, JsonMapper.builder().build());
        when(repo.findWard(wardId)).thenReturn(Optional.of(ward));
    }

    @Test
    @DisplayName("未保存：返回实时在院清单（按床号），风险项与出院预检同码，可编辑")
    void liveViewCarriesFlags() {
        UUID bed1 = UUID.randomUUID();
        UUID bed2 = UUID.randomUUID();
        Admission a1 = admission("P1", bed2, today.minusDays(3), today.minusDays(1));
        Admission a2 = admission("P2", bed1, today.minusDays(1), today);
        when(repo.listActiveAdmissions(wardId)).thenReturn(List.of(a1, a2));
        when(repo.listBeds(wardId)).thenReturn(List.of(
                new Bed(bed1, wardId, "01", Bed.OCCUPIED, 0L),
                new Bed(bed2, wardId, "02", Bed.OCCUPIED, 0L)));
        when(repo.countUnsettledChargesByVisits(any())).thenReturn(Map.of(a2.visitId(), 1));

        HandoverView view = service.view(wardId, today, "DAY");

        assertThat(view.id()).isNull();
        assertThat(view.status()).isEqualTo(Handover.DRAFT);
        assertThat(view.editable()).isTrue();
        assertThat(view.items()).extracting(i -> i.bedNo()).containsExactly("01", "02");
        assertThat(view.items().get(0).flags())
                .containsExactly(DischargeCheckService.CODE_UNSETTLED_CHARGE,
                        HandoverService.CODE_PLANNED_TODAY);
        assertThat(view.items().get(1).flags())
                .containsExactly(WardDischargeCheckService.CODE_OVERDUE_DISCHARGE);
    }

    @Test
    @DisplayName("草稿：实时风险项 + 叠加已保存的逐人备注")
    void draftOverlaysNotes() {
        UUID bed = UUID.randomUUID();
        Admission a = admission("P1", bed, today.minusDays(1), null);
        Handover draft = handover(Handover.DRAFT, 2L);
        HandoverItem saved = new HandoverItem(UUID.randomUUID(), draft.id(), a.id(), "01", "P1",
                "", "注意夜间血压");
        when(repo.findHandover(wardId, today, "DAY")).thenReturn(Optional.of(draft));
        when(repo.listHandoverItems(draft.id())).thenReturn(List.of(saved));
        when(repo.listActiveAdmissions(wardId)).thenReturn(List.of(a));
        when(repo.listBeds(wardId)).thenReturn(List.of(
                new Bed(bed, wardId, "01", Bed.OCCUPIED, 0L)));

        HandoverView view = service.view(wardId, today, "DAY");

        assertThat(view.editable()).isTrue();
        assertThat(view.note()).isEqualTo("班次说明");
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).note()).isEqualTo("注意夜间血压");
    }

    @Test
    @DisplayName("已签字：返回冻结快照，不再读实时在院清单")
    void signedViewIsFrozen() {
        Handover signed = handover(Handover.SIGNED, 5L);
        HandoverItem item = new HandoverItem(UUID.randomUUID(), signed.id(),
                UUID.randomUUID(), "03", "P9", "UNSETTLED_CHARGE,OVERDUE_DISCHARGE", "已交接");
        when(repo.findHandover(wardId, today, "NIGHT")).thenReturn(Optional.of(signed));
        when(repo.listHandoverItems(signed.id())).thenReturn(List.of(item));

        HandoverView view = service.view(wardId, today, "NIGHT");

        assertThat(view.editable()).isFalse();
        assertThat(view.status()).isEqualTo(Handover.SIGNED);
        assertThat(view.signedBy()).isEqualTo("nurse01");
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).flags())
                .containsExactly(DischargeCheckService.CODE_UNSETTLED_CHARGE,
                        WardDischargeCheckService.CODE_OVERDUE_DISCHARGE);
        verify(repo, never()).listActiveAdmissions(any());
    }

    @Test
    @DisplayName("保存草稿：插入报告 + 用服务端快照重建条目 + 留痕")
    void saveCreatesDraftWithServerSnapshot() {
        UUID admissionId = UUID.randomUUID();
        when(repo.listActiveAdmissions(wardId)).thenReturn(List.of(admission(admissionId)));
        when(repo.listBeds(wardId)).thenReturn(List.of());
        when(repo.findHandover(wardId, today, "DAY")).thenReturn(Optional.empty());

        HandoverView view = service.save(wardId, new HandoverSaveReq(today, "DAY", "班次说明",
                List.of(new HandoverItemReq(admissionId, "重点看血压"))), "nurse01", false);

        assertThat(view.status()).isEqualTo(Handover.DRAFT);
        verify(repo).insertHandover(any(Handover.class));
        verify(repo).deleteHandoverItems(any());
        verify(repo).insertHandoverItem(any(HandoverItem.class));
        verify(repo).logChange(eq("nurse01"), eq("HANDOVER_SAVE"), eq("HANDOVER"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("已签字：保存与签字都 409，不重建条目")
    void signedCannotBeEdited() {
        Handover signed = handover(Handover.SIGNED, 4L);
        when(repo.findHandover(wardId, today, "DAY")).thenReturn(Optional.of(signed));
        when(repo.lockHandover(signed.id())).thenReturn(Optional.of(signed));
        var req = new HandoverSaveReq(today, "DAY", "改一下", List.of());

        assertThatThrownBy(() -> service.save(wardId, req, "nurse01", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已签字")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).deleteHandoverItems(any());
    }

    @Test
    @DisplayName("签字：保存后 DRAFT → SIGNED 并留痕；非法班次 422")
    void signFreezesAndValidatesShift() {
        Handover draft = handover(Handover.DRAFT, 1L);
        when(repo.findHandover(wardId, today, "DAY"))
                .thenReturn(Optional.of(draft), Optional.of(draft));
        when(repo.lockHandover(draft.id()))
                .thenReturn(Optional.of(draft), Optional.of(draft), Optional.of(draft));
        when(repo.updateHandoverDraft(draft.id(), "交班说明", 1L)).thenReturn(true);
        when(repo.signHandover(draft.id(), "nurse01", 1L)).thenReturn(true);
        when(repo.listActiveAdmissions(wardId)).thenReturn(List.of(admission(UUID.randomUUID())));

        service.save(wardId, new HandoverSaveReq(today, "DAY", "交班说明", List.of()),
                "nurse01", true);

        verify(repo).signHandover(draft.id(), "nurse01", 1L);
        verify(repo).logChange(eq("nurse01"), eq("HANDOVER_SIGN"), eq("HANDOVER"),
                anyString(), anyString());

        assertThatThrownBy(() -> service.view(wardId, today, "MORNING"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DAY / EVENING / NIGHT")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 构造

    private Handover handover(String status, long version) {
        return new Handover(UUID.randomUUID(), wardId, today, "DAY", "班次说明",
                status, "nurse00", Handover.SIGNED.equals(status) ? "nurse01" : null,
                Handover.SIGNED.equals(status) ? Instant.now() : null, Instant.now(), version);
    }

    private Admission admission(UUID admissionId) {
        return new Admission(admissionId, "A1", UUID.randomUUID(), UUID.randomUUID(), "P1",
                wardId, null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null,
                null, 0L);
    }

    private Admission admission(String patientNo, UUID bedId, LocalDate admittedDay,
                                LocalDate planned) {
        return new Admission(UUID.randomUUID(), "A-" + patientNo, UUID.randomUUID(),
                UUID.randomUUID(), patientNo, wardId, bedId, "doctor01",
                Admission.IN_HOSPITAL, admittedDay.atStartOfDay(BusinessTime.zone()).toInstant(),
                null, planned, 0L);
    }
}
