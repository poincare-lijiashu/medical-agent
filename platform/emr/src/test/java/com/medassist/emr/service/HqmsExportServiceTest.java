package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.domain.EmrModel.HqmsExportBatch;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.HqmsReceiptReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HQMS 上报批次：期间校验、只导出已归档、报文固化、回执与撤销的单向性。
 */
class HqmsExportServiceTest {

    private EmrRepository repo;
    private HqmsExportService service;

    private final LocalDate today = LocalDate.now();
    private final UUID homepageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        service = new HqmsExportService(repo, JsonMapper.builder().build());
        when(repo.nextHqmsBatchNo()).thenReturn("HQ20261101000001");
    }

    @Test
    @DisplayName("期间校验：结束早于开始 / 超过 92 天 / 晚于今天 均 422")
    void createValidatesPeriod() {
        assertThatThrownBy(() -> service.create(today, today.minusDays(1), "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.create(today.minusDays(200), today, "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.create(today, today.plusDays(1), "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertHqmsBatch(any());
    }

    @Test
    @DisplayName("没有待上报首页时 409，并提示已被其它批次占用的数量")
    void createRejectsWhenNothingPending() {
        when(repo.countHqmsPending(today, today)).thenReturn(0L);
        when(repo.countHqmsAlreadyExported(today, today)).thenReturn(3L);

        assertThatThrownBy(() -> service.create(today, today, "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HQMS_EXPORT_EMPTY);
                    assertThat(e.getMessage()).contains("3");
                });
        verify(repo, never()).insertHqmsBatch(any());
    }

    @Test
    @DisplayName("生成批次：报文固化首页/诊断/费用，哈希 64 位，明细逐条落库")
    void createBuildsPayloadAndItems() {
        when(repo.countHqmsPending(today, today)).thenReturn(2L);
        when(repo.countHqmsAlreadyExported(today, today)).thenReturn(0L);
        Homepage homepage = homepage();
        when(repo.listHqmsPending(today, today, 500)).thenReturn(List.of(homepage));
        AtomicReference<HqmsExportBatch> inserted = new AtomicReference<>();
        doAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return null;
        }).when(repo).insertHqmsBatch(any());
        when(repo.findHqmsBatch(any())).thenAnswer(inv -> Optional.of(inserted.get()));

        HqmsExportService.CreateResult result = service.create(today, today, "qc01");

        HqmsExportBatch batch = inserted.get();
        assertThat(batch).isNotNull();
        assertThat(batch.status()).isEqualTo(HqmsExportBatch.GENERATED);
        assertThat(batch.homepageCount()).isEqualTo(1);
        assertThat(batch.totalAmount()).isEqualByComparingTo("25.00");
        assertThat(batch.payloadHash()).hasSize(64);
        assertThat(batch.payload())
                .contains("<homepageBatch version=\"1\"")
                .contains("batchNo=\"HQ20261101000001\"")
                .contains("homepageNo=\"HP20261101000001\"")
                .contains("patientName=\"张三\"")
                .contains("<diagnosis code=\"J18.900\"")
                .contains("<fee category=\"EXAM\" amount=\"25.00\"/>");
        verify(repo).insertHqmsItem(batch.id(), homepage);
        // 并发串行必须用全局单键（按期间拼键时重叠期间不互斥，第六轮 MF-07）
        verify(repo).lockHqmsExport();
        // 2 条待上报，单批只取到 1 条 → 还有 1 条要再报
        assertThat(result.remainingPending()).isEqualTo(1);
        assertThat(result.excludedAlreadyExported()).isZero();
        verify(repo).logChange(eq("qc01"), eq("HQMS_BATCH_CREATE"), eq("HQMS_BATCH"),
                eq("HQ20261101000001"), anyString());
    }

    @Test
    @DisplayName("明细唯一约束冲突（并发被他人抢先上报）转 409，而不是 500")
    void createTranslatesUniqueViolationToConflict() {
        when(repo.countHqmsPending(today, today)).thenReturn(1L);
        when(repo.countHqmsAlreadyExported(today, today)).thenReturn(0L);
        Homepage homepage = homepage();
        when(repo.listHqmsPending(today, today, 500)).thenReturn(List.of(homepage));
        doThrow(new DataIntegrityViolationException("ux_hqms_item_active_homepage"))
                .when(repo).insertHqmsItem(any(), any());

        assertThatThrownBy(() -> service.create(today, today, "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getMessage()).contains("上报批次");
                });
    }

    @Test
    @DisplayName("回执：REJECTED 必须写原因；成功后走 CAS")
    void receiptRequiresReasonForRejection() {
        HqmsExportBatch generated = batch(HqmsExportBatch.GENERATED);
        when(repo.findHqmsBatch(generated.id())).thenReturn(Optional.of(generated));

        assertThatThrownBy(() -> service.receipt(generated.id(),
                new HqmsReceiptReq("REJECTED", "RJ-1", null), "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).updateHqmsReceipt(any(), anyString(), anyString(), any(), anyLong());

        when(repo.updateHqmsReceipt(generated.id(), "REJECTED", "RJ-1", "诊断编码错误", 0L))
                .thenReturn(true);
        when(repo.findHqmsBatch(generated.id())).thenReturn(Optional.of(generated));
        service.receipt(generated.id(), new HqmsReceiptReq("REJECTED", "RJ-1", "诊断编码错误"),
                "qc01");
        verify(repo).updateHqmsReceipt(generated.id(), "REJECTED", "RJ-1", "诊断编码错误", 0L);
    }

    @Test
    @DisplayName("已登记的批次不能重复回执")
    void receiptRejectsAlreadyReceipted() {
        HqmsExportBatch accepted = batch(HqmsExportBatch.ACCEPTED);
        when(repo.findHqmsBatch(accepted.id())).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.receipt(accepted.id(),
                new HqmsReceiptReq("ACCEPTED", "RC-1", null), "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HQMS_EXPORT_INVALID));
        verify(repo, never()).updateHqmsReceipt(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("撤销：已回执不能撤销，未回执可撤销（CAS）")
    void cancelRules() {
        HqmsExportBatch accepted = batch(HqmsExportBatch.ACCEPTED);
        when(repo.findHqmsBatch(accepted.id())).thenReturn(Optional.of(accepted));
        assertThatThrownBy(() -> service.cancel(accepted.id(), "选错期间", "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HQMS_EXPORT_INVALID));

        HqmsExportBatch generated = batch(HqmsExportBatch.GENERATED);
        when(repo.findHqmsBatch(generated.id())).thenReturn(Optional.of(generated));
        when(repo.cancelHqmsBatch(generated.id(), "选错期间", 0L)).thenReturn(true);
        service.cancel(generated.id(), "选错期间", "qc01");
        verify(repo).cancelHqmsBatch(generated.id(), "选错期间", 0L);
    }

    @Test
    @DisplayName("撤销必须写原因")
    void cancelRequiresReason() {
        HqmsExportBatch generated = batch(HqmsExportBatch.GENERATED);
        when(repo.findHqmsBatch(generated.id())).thenReturn(Optional.of(generated));

        assertThatThrownBy(() -> service.cancel(generated.id(), "  ", "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).cancelHqmsBatch(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("报文下载留痕（批量 PHI）")
    void payloadIsAudited() {
        HqmsExportBatch batch = batch(HqmsExportBatch.GENERATED);
        when(repo.findHqmsBatch(batch.id())).thenReturn(Optional.of(batch));

        HqmsExportBatch result = service.payload(batch.id(), "qc01");

        assertThat(result.payload()).contains("<homepageBatch");
        verify(repo).logChange(eq("qc01"), eq("HQMS_PAYLOAD_DOWNLOAD"), eq("HQMS_BATCH"),
                eq(batch.batchNo()), anyString());
    }

    @Test
    @DisplayName("列表默认 20 条、上限 100 条")
    void listCapsLimit() {
        when(repo.listHqmsBatches(anyInt())).thenReturn(List.of());
        service.list(null);
        service.list(1000);
        verify(repo).listHqmsBatches(20);
        verify(repo).listHqmsBatches(100);
    }

    // ---------------------------------------------------------------- fixtures

    private Homepage homepage() {
        return new Homepage(homepageId, "HP20261101000001", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "P20261101000001", "张三", "V001", UUID.randomUUID(), "内科",
                UUID.randomUUID(), "一病区", "A1", Instant.parse("2026-11-01T02:00:00Z"),
                Instant.parse("2026-11-01T08:00:00Z"), 1, "doctor01", "OUTPATIENT", "BY_DOCTOR",
                "NONE", "MARRIED", "Engineer", "Han", "A", "POSITIVE", 0, 0, false, false,
                "chief01", "resident01", "coder01",
                "[{\"code\":\"J18.900\",\"name\":\"肺炎\",\"primary\":true}]", "[]",
                "{\"REGISTRATION\":0.00,\"EXAM\":25.00}", new BigDecimal("25.00"),
                Homepage.ARCHIVED, "admin01", Instant.now(), "qc01", Instant.now(),
                3L, Instant.now());
    }

    private HqmsExportBatch batch(String status) {
        return new HqmsExportBatch(UUID.randomUUID(), "HQ20261101000001", today, today, 1,
                new BigDecimal("25.00"), "<homepageBatch version=\"1\"/>", "hash", status,
                HqmsExportBatch.GENERATED.equals(status) ? null : "RC-1", null,
                HqmsExportBatch.GENERATED.equals(status) ? null : Instant.now(),
                null, null, "qc01", 0L, Instant.now());
    }
}
