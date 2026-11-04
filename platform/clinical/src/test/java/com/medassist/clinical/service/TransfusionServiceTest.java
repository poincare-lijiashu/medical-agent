package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.BloodCrossmatch;
import com.medassist.clinical.domain.ClinicalModel.BloodProduct;
import com.medassist.clinical.domain.ClinicalModel.TransfusionRecord;
import com.medassist.clinical.domain.ClinicalModel.TransfusionRequest;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.TransfusionDtos.CompleteTransfusionReq;
import com.medassist.clinical.web.TransfusionDtos.CreateRequestReq;
import com.medassist.clinical.web.TransfusionDtos.CrossmatchReq;
import com.medassist.clinical.web.TransfusionDtos.ReactionReq;
import com.medassist.clinical.web.TransfusionDtos.StartTransfusionReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 输血闭环：申请、配血/发血、双人核对、完成与不良反应。
 */
class TransfusionServiceTest {

    private ClinicalRepository repo;
    private PatientClient patientClient;
    private TransfusionService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID admissionId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();
    private final UUID bedId = UUID.randomUUID();
    private final UUID componentId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID crossmatchId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        patientClient = mock(PatientClient.class);
        service = new TransfusionService(repo, patientClient, objectMapper);
    }

    private Admission admission() {
        return new Admission(admissionId, "A20261104000001", visitId, patientId, "P001",
                wardId, bedId, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 0);
    }

    private BloodProduct product() {
        return new BloodProduct(componentId, "BLOOD-RBC", "去白细胞红细胞", "U", true);
    }

    private TransfusionRequest request(String status, long version) {
        Instant now = Instant.now();
        return new TransfusionRequest(requestId, "TR20261104000001", admissionId, visitId,
                patientId, "P001", "张三", "MALE", LocalDate.of(1960, 1, 1), wardId, "内科病区",
                "01", componentId, "BLOOD-RBC", "去白细胞红细胞", new BigDecimal("2"), "U",
                "A", "POSITIVE", new BigDecimal("70"), 150, "URGENT", "重度贫血",
                status, status.equals(TransfusionRequest.REQUESTED) ? null : crossmatchId,
                status.equals(TransfusionRequest.REQUESTED) ? null : "BAG001",
                status.equals(TransfusionRequest.REQUESTED) ? null : BusinessTime.today(),
                "doctor01", now,
                status.equals(TransfusionRequest.ISSUED) || status.equals(TransfusionRequest.TRANSFUSING)
                        || status.equals(TransfusionRequest.COMPLETED) ? "bloodbank01" : null,
                status.equals(TransfusionRequest.ISSUED) || status.equals(TransfusionRequest.TRANSFUSING)
                        || status.equals(TransfusionRequest.COMPLETED) ? now : null,
                status.equals(TransfusionRequest.TRANSFUSING)
                        || status.equals(TransfusionRequest.COMPLETED) ? "nurse01" : null,
                status.equals(TransfusionRequest.TRANSFUSING)
                        || status.equals(TransfusionRequest.COMPLETED) ? now : null,
                status.equals(TransfusionRequest.COMPLETED) ? "nurse01" : null,
                status.equals(TransfusionRequest.COMPLETED) ? now : null,
                null, null, null, "doctor01", version, now, now);
    }

    private BloodCrossmatch crossmatch(String result) {
        return new BloodCrossmatch(crossmatchId, "TXM20261104000001", requestId, "BAG001",
                "A", "POSITIVE", result, "微柱凝胶法", BusinessTime.today().plusDays(20),
                null, "bloodbank01", Instant.now(), 0L, Instant.now());
    }

    private TransfusionRecord record(String status, long version) {
        Instant now = Instant.now();
        return new TransfusionRecord(recordId, "TRN20261104000001", requestId, "BAG001",
                status, now, null, null, new BigDecimal("36.5"), 80, 120, 80,
                null, null, null, null, false, "nurse01", "nurse02", now, "nurse01",
                null, null, version, now);
    }

    private void stubDetail() {
        when(repo.findTransfusionRequest(any())).thenReturn(Optional.of(
                request(TransfusionRequest.REQUESTED, 0)));
        when(repo.listCrossmatchesByRequest(any())).thenReturn(List.of());
        when(repo.findTransfusionRecordByRequest(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("申请：血型/成分/数量/紧急度校验")
    void createRequestValidation() {
        when(repo.findBloodProduct(componentId)).thenReturn(Optional.of(product()));
        CreateRequestReq badType = new CreateRequestReq(null, "A001", componentId,
                new BigDecimal("2"), "C", "POSITIVE", null, null, null, "贫血");
        assertThatThrownBy(() -> service.createRequest(badType, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_REQUEST_INVALID);

        when(repo.findBloodProduct(componentId)).thenReturn(Optional.of(
                new BloodProduct(componentId, "BLOOD-RBC", "红细胞", "U", false)));
        CreateRequestReq ok = new CreateRequestReq(null, "A001", componentId,
                new BigDecimal("2"), "A", "POSITIVE", null, null, null, "贫血");
        assertThatThrownBy(() -> service.createRequest(ok, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_REQUEST_INVALID);
    }

    @Test
    @DisplayName("申请成功：写入患者/床位/血型快照")
    void createRequestSnapshot() {
        when(repo.findAdmissionByNo("A20261104000001")).thenReturn(Optional.of(admission()));
        when(repo.findBloodProduct(componentId)).thenReturn(Optional.of(product()));
        when(patientClient.requireProfile(patientId)).thenReturn(
                new PatientClient.PatientProfile(patientId, "P001", "张三", "MALE",
                        LocalDate.of(1960, 1, 1)));
        when(repo.findWard(wardId)).thenReturn(Optional.of(
                new com.medassist.clinical.domain.ClinicalModel.Ward(wardId, "W01", "内科病区",
                        UUID.randomUUID(), true)));
        when(repo.findBed(bedId)).thenReturn(Optional.of(
                new com.medassist.clinical.domain.ClinicalModel.Bed(bedId, wardId, "01",
                        "OCCUPIED", 0)));
        when(repo.nextTransfusionRequestNo()).thenReturn("TR20261104000001");
        stubDetail();

        var view = service.createRequest(new CreateRequestReq(null, "A20261104000001",
                componentId, new BigDecimal("2"), "A", "POSITIVE", new BigDecimal("70"), 150,
                "URGENT", "重度贫血"), "doctor01");
        assertThat(view.request().status()).isEqualTo(TransfusionRequest.REQUESTED);
        verify(repo).insertTransfusionRequest(any());
    }

    @Test
    @DisplayName("配血：相合推进 CROSSMATCHED，不合保持 REQUESTED，血袋号重复拒绝")
    void crossmatchFlow() {
        TransfusionRequest requested = request(TransfusionRequest.REQUESTED, 0);
        when(repo.lockTransfusionRequest(requestId)).thenReturn(Optional.of(requested));
        when(repo.findCrossmatchByBagNo("BAG001")).thenReturn(Optional.empty());
        when(repo.nextCrossmatchNo()).thenReturn("TXM20261104000001");
        when(repo.markTransfusionCrossmatched(eq(requestId), any(), eq("BAG001"),
                any(), eq(0L))).thenReturn(true);
        stubDetail();

        service.crossmatch(requestId, new CrossmatchReq("BAG001", "A", "POSITIVE",
                "COMPATIBLE", "微柱凝胶法", BusinessTime.today().plusDays(20), null),
                "bloodbank01");
        verify(repo).markTransfusionCrossmatched(eq(requestId), any(), eq("BAG001"),
                any(), eq(0L));

        when(repo.findCrossmatchByBagNo("BAG001")).thenReturn(Optional.of(crossmatch("COMPATIBLE")));
        assertThatThrownBy(() -> service.crossmatch(requestId,
                new CrossmatchReq("BAG001", "A", "POSITIVE", "COMPATIBLE", "微柱凝胶法",
                        BusinessTime.today().plusDays(20), null), "bloodbank01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_CROSSMATCH_INVALID);
    }

    @Test
    @DisplayName("发血：未配血/过期拒绝，相合后推进 ISSUED")
    void issueRules() {
        TransfusionRequest requested = request(TransfusionRequest.REQUESTED, 0);
        when(repo.lockTransfusionRequest(requestId)).thenReturn(Optional.of(requested));
        assertThatThrownBy(() -> service.issue(requestId, "bloodbank01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_STATUS_INVALID);

        TransfusionRequest crossmatched = request(TransfusionRequest.CROSSMATCHED, 1);
        when(repo.lockTransfusionRequest(requestId)).thenReturn(Optional.of(crossmatched));
        BloodCrossmatch expired = new BloodCrossmatch(crossmatchId, "TXM1", requestId, "BAG001",
                "A", "POSITIVE", "COMPATIBLE", "微柱凝胶法", BusinessTime.today().minusDays(1),
                null, "bloodbank01", Instant.now(), 0L, Instant.now());
        when(repo.findCompatibleCrossmatchByRequest(requestId)).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.issue(requestId, "bloodbank01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_CROSSMATCH_INVALID);

        when(repo.findCompatibleCrossmatchByRequest(requestId)).thenReturn(Optional.of(
                crossmatch("COMPATIBLE")));
        when(repo.markTransfusionIssued(requestId, "bloodbank01", 1)).thenReturn(true);
        stubDetail();
        service.issue(requestId, "bloodbank01");
        verify(repo).markTransfusionIssued(requestId, "bloodbank01", 1);
    }

    @Test
    @DisplayName("开始输注：双人必须不同，未发血拒绝")
    void startRules() {
        StartTransfusionReq sameChecker = new StartTransfusionReq("nurse01", "nurse01",
                new BigDecimal("36.5"), 80, 120, 80);
        assertThatThrownBy(() -> service.start(requestId, sameChecker, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_DOUBLE_CHECK_REQUIRED);

        when(repo.lockTransfusionRequest(requestId)).thenReturn(Optional.of(
                request(TransfusionRequest.CROSSMATCHED, 1)));
        StartTransfusionReq ok = new StartTransfusionReq("nurse01", "nurse02",
                new BigDecimal("36.5"), 80, 120, 80);
        assertThatThrownBy(() -> service.start(requestId, ok, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_STATUS_INVALID);
    }

    @Test
    @DisplayName("开始输注成功：写输注记录并推进 TRANSFUSING")
    void startSuccess() {
        TransfusionRequest issued = request(TransfusionRequest.ISSUED, 2);
        when(repo.lockTransfusionRequest(requestId)).thenReturn(Optional.of(issued));
        when(repo.nextTransfusionRecordNo()).thenReturn("TRN20261104000001");
        when(repo.markTransfusionStarted(requestId, "nurse01", 2)).thenReturn(true);
        stubDetail();

        service.start(requestId, new StartTransfusionReq("nurse01", "nurse02",
                new BigDecimal("36.5"), 80, 120, 80), "nurse01");
        verify(repo).insertTransfusionRecord(any());
        verify(repo).markTransfusionStarted(requestId, "nurse01", 2);
    }

    @Test
    @DisplayName("完成输注：记录与申请同时完成")
    void completeFlow() {
        when(repo.lockTransfusionRecord(recordId)).thenReturn(Optional.of(
                record(TransfusionRecord.TRANSFUSING, 0)));
        when(repo.lockTransfusionRequest(requestId)).thenReturn(Optional.of(
                request(TransfusionRequest.TRANSFUSING, 3)));
        when(repo.completeTransfusionRecord(eq(recordId), any(), any(), any(), any(), any(),
                eq("nurse01"), eq(0L))).thenReturn(true);
        when(repo.markTransfusionCompleted(requestId, "nurse01", 3)).thenReturn(true);
        stubDetail();
        when(repo.findTransfusionRecordByRequest(requestId)).thenReturn(Optional.of(
                record(TransfusionRecord.COMPLETED, 1)));

        service.complete(recordId, new CompleteTransfusionReq(new BigDecimal("200"),
                new BigDecimal("36.8"), 82, 118, 78), "nurse01");
        verify(repo).markTransfusionCompleted(requestId, "nurse01", 3);
    }

    @Test
    @DisplayName("不良反应：分级/重复上报拒绝，成功后记录 reaction=true")
    void reactionRules() {
        when(repo.lockTransfusionRecord(recordId)).thenReturn(Optional.of(
                record(TransfusionRecord.TRANSFUSING, 0)));
        assertThatThrownBy(() -> service.reportReaction(recordId,
                new ReactionReq("UNKNOWN", "皮疹", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_REACTION_INVALID);

        when(repo.findReactionByRecord(recordId)).thenReturn(Optional.of(
                new com.medassist.clinical.domain.ClinicalModel.TransfusionReaction(
                        UUID.randomUUID(), "TRR1", recordId, requestId, "MILD", "皮疹",
                        null, "nurse01", Instant.now(), Instant.now())));
        assertThatThrownBy(() -> service.reportReaction(recordId,
                new ReactionReq("MILD", "皮疹", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_REACTION_EXISTS);
    }

    @Test
    @DisplayName("取消：发血后拒绝，未填原因拒绝")
    void cancelRules() {
        when(repo.lockTransfusionRequest(requestId)).thenReturn(Optional.of(
                request(TransfusionRequest.ISSUED, 2)));
        assertThatThrownBy(() -> service.cancel(requestId, "患者拒绝", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_STATUS_INVALID);

        when(repo.lockTransfusionRequest(requestId)).thenReturn(Optional.of(
                request(TransfusionRequest.REQUESTED, 0)));
        assertThatThrownBy(() -> service.cancel(requestId, " ", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TRANSFUSION_CANCEL_REASON_REQUIRED);
        verify(repo, never()).cancelTransfusionRequest(any(), any(), any(), anyLong());
    }
}
