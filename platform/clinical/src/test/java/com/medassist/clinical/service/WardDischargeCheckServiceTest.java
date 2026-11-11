package com.medassist.clinical.service;

import com.medassist.clinical.client.EmrArchiveClient;
import com.medassist.clinical.client.EmrArchiveClient.ArchiveSummary;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.WardDischargeReadiness;
import com.medassist.clinical.web.ClinicalDtos.WardDischargeReadinessView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 病区出院预检：一次聚合、口径与单患者体检一致、未核查不冒充通过。
 */
class WardDischargeCheckServiceTest {

    private ClinicalRepository repo;
    private EmrArchiveClient emrClient;
    private WardDischargeCheckService service;

    private final UUID wardId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();
    private final Ward ward = new Ward(wardId, "W01", "内科一病区", deptId, true);

    private Admission ready;
    private Admission blocked;
    private Admission warned;
    private UUID bedReady;
    private UUID bedBlocked;
    private UUID bedWarned;

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        emrClient = mock(EmrArchiveClient.class);
        service = new WardDischargeCheckService(repo, emrClient, JsonMapper.builder().build());
        when(repo.findWard(wardId)).thenReturn(Optional.of(ward));

        bedReady = UUID.randomUUID();
        bedBlocked = UUID.randomUUID();
        bedWarned = UUID.randomUUID();
        ready = admission(Admission.IN_HOSPITAL, bedReady);
        blocked = admission(Admission.IN_HOSPITAL, bedBlocked);
        warned = admission(Admission.IN_HOSPITAL, bedWarned);
        when(repo.listActiveAdmissions(wardId)).thenReturn(List.of(ready, blocked, warned));
        when(repo.listBeds(wardId)).thenReturn(List.of(
                new Bed(bedReady, wardId, "01", Bed.OCCUPIED, 0L),
                new Bed(bedWarned, wardId, "02", Bed.OCCUPIED, 0L),
                new Bed(bedBlocked, wardId, "03", Bed.OCCUPIED, 0L)));

        when(repo.countUnsettledChargesByVisits(any())).thenReturn(Map.of(blocked.visitId(), 1));
        when(repo.countUnbilledTempOrdersByVisits(any())).thenReturn(Map.of(blocked.visitId(), 2));
        when(repo.countUnbilledDailyOrdersByVisits(any(), any(), any()))
                .thenReturn(Map.of(blocked.visitId(), 3));
        when(repo.countUnstoppedLongOrdersByVisits(any())).thenReturn(Map.of(warned.visitId(), 1));
        when(repo.listActiveDischargeMedicationAdmissions(any())).thenReturn(Set.of(warned.id()));
        when(emrClient.batchArchiveSummary(any())).thenReturn(Map.of(
                ready.visitId(), new ArchiveSummary(2, 2, 0, true, "ARCHIVED", true),
                blocked.visitId(), new ArchiveSummary(2, 2, 0, true, "ARCHIVED", true),
                warned.visitId(), new ArchiveSummary(3, 2, 1, true, "SUBMITTED", false)));
    }

    @Test
    @DisplayName("病区预检：阻断在前、建议不阻断，条数与机器码可核对")
    void mixesBlockedAndWarningOnly() {
        WardDischargeReadinessView view = service.check(wardId, "nurse01");

        assertThat(view.total()).isEqualTo(3);
        assertThat(view.blockedCount()).isEqualTo(1);
        assertThat(view.readyCount()).isEqualTo(2);
        assertThat(view.emrChecked()).isTrue();
        // 阻断的排最前；其余按床号
        assertThat(view.items()).extracting(WardDischargeReadiness::admissionId)
                .containsExactly(blocked.id(), ready.id(), warned.id());

        WardDischargeReadiness b = item(view, blocked.id());
        assertThat(b.ready()).isFalse();
        assertThat(b.blockerCount()).isEqualTo(3);
        assertThat(b.blockers()).containsExactly(
                DischargeCheckService.CODE_UNSETTLED_CHARGE,
                DischargeCheckService.CODE_UNBILLED_TEMP_ORDER,
                DischargeCheckService.CODE_UNBILLED_DAILY_ORDER);
        assertThat(b.bedNo()).isEqualTo("03");

        WardDischargeReadiness w = item(view, warned.id());
        assertThat(w.ready()).isTrue();
        assertThat(w.warnings()).containsExactly(
                DischargeCheckService.CODE_UNSTOPPED_LONG_ORDER,
                DischargeCheckService.CODE_DISCHARGE_MED_PENDING,
                DischargeCheckService.CODE_EMR_ARCHIVE);

        assertThat(item(view, ready.id()).ready()).isTrue();
        assertThat(item(view, ready.id()).warnings()).isEmpty();
    }

    @Test
    @DisplayName("一次批量查询：不为每个患者重复打库（N+1 回归锁）")
    void queriesOncePerCategory() {
        service.check(wardId, "nurse01");

        verify(repo, times(1)).countUnsettledChargesByVisits(any());
        verify(repo, times(1)).countUnbilledTempOrdersByVisits(any());
        verify(repo, times(1)).countUnbilledDailyOrdersByVisits(any(), any(), any());
        verify(repo, times(1)).countUnstoppedLongOrdersByVisits(any());
        verify(repo, times(1)).listActiveDischargeMedicationAdmissions(any());
        verify(emrClient, times(1)).batchArchiveSummary(any());
    }

    @Test
    @DisplayName("EMR 不可达：emrChecked=false，不给任何患者加病历归档项（不猜）")
    void emrUnavailableMarksUnchecked() {
        when(emrClient.batchArchiveSummary(any())).thenReturn(Map.of());

        WardDischargeReadinessView view = service.check(wardId, "nurse01");

        assertThat(view.emrChecked()).isFalse();
        assertThat(view.items()).noneMatch(i ->
                i.warnings().contains(DischargeCheckService.CODE_EMR_ARCHIVE));
    }

    @Test
    @DisplayName("仅登记未入院：状态本身即阻断项")
    void admittedNotInHospitalIsBlocked() {
        when(repo.listActiveAdmissions(wardId)).thenReturn(List.of(
                admission(Admission.ADMITTED, bedReady)));

        WardDischargeReadinessView view = service.check(wardId, "nurse01");

        assertThat(view.blockedCount()).isEqualTo(1);
        assertThat(view.items().get(0).blockers())
                .containsExactly(DischargeCheckService.CODE_ADMISSION_STATUS);
    }

    @Test
    @DisplayName("空病区：直接返回全 0，不触发批量查询")
    void emptyWardSkipsQueries() {
        when(repo.listActiveAdmissions(wardId)).thenReturn(List.of());

        WardDischargeReadinessView view = service.check(wardId, "nurse01");

        assertThat(view.total()).isZero();
        assertThat(view.items()).isEmpty();
        verify(emrClient, times(0)).batchArchiveSummary(any());
    }

    @Test
    @DisplayName("汇总：可出院 = 在院 − 受阻；口径与体检一致（一条阻断 SQL）")
    void summaryCountsBlockedInOneQuery() {
        when(repo.countActiveAdmissionsByWards(any())).thenReturn(10);
        when(repo.countDischargeBlockedByWards(any(), any(), any())).thenReturn(3);

        var summary = service.summary(List.of(wardId, UUID.randomUUID()));

        assertThat(summary.wardCount()).isEqualTo(2);
        assertThat(summary.total()).isEqualTo(10);
        assertThat(summary.blocked()).isEqualTo(3);
        assertThat(summary.ready()).isEqualTo(7);
        verify(repo, times(1)).countDischargeBlockedByWards(any(), any(), any());
    }

    @Test
    @DisplayName("汇总：没有在院患者时不再查阻断项；没有可见病区直接回 0")
    void summaryShortCircuits() {
        when(repo.countActiveAdmissionsByWards(any())).thenReturn(0);
        var empty = service.summary(List.of(wardId));
        assertThat(empty.total()).isZero();
        verify(repo, times(0)).countDischargeBlockedByWards(any(), any(), any());

        var noWard = service.summary(List.of());
        assertThat(noWard.wardCount()).isZero();
        verify(repo, times(1)).countActiveAdmissionsByWards(any());
    }

    // ---------------------------------------------------------------- 构造

    private WardDischargeReadiness item(WardDischargeReadinessView view, UUID admissionId) {
        return view.items().stream().filter(i -> admissionId.equals(i.admissionId()))
                .findFirst().orElseThrow();
    }

    private Admission admission(String status, UUID bedId) {
        return new Admission(UUID.randomUUID(), "A-" + UUID.randomUUID().toString().substring(0, 4),
                UUID.randomUUID(), UUID.randomUUID(), "P1", wardId, bedId, "doctor01",
                status, Instant.now().minusSeconds(7200), null, 0L);
    }
}
