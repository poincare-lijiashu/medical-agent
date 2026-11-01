package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.ExportReceiptReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医保导出批次：期间校验、报盘互斥、XML 报盘与哈希、回执/撤销 CAS 与审计。
 */
class SettlementExportServiceTest {

    private InsuranceRepository repo;
    private SettlementExportService service;

    private final UUID billId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(InsuranceRepository.class);
        service = new SettlementExportService(repo, JsonMapper.builder().build());
    }

    private static final String DETAIL = """
            [{"chargeNo":"CH1","itemCode":"ITEM-1","itemName":"A&B","category":"SERVICE",
              "chargeStatus":"PAID","amount":10.00,"insuranceCode":"I-1","payCategory":"A",
              "selfPayRatio":0.00,"overLimitAmount":null,"eligibleAmount":10.00,"unmapped":false}]
            """;

    private SettlementBill bill() {
        return new SettlementBill(billId, "SB001", UUID.randomUUID(), UUID.randomUUID(), "P001",
                UUID.randomUUID(), new BigDecimal("10.00"), new BigDecimal("10.00"),
                new BigDecimal("0.00"), new BigDecimal("10.00"), new BigDecimal("0.00"),
                SettlementBill.CONFIRMED, DETAIL, "admin01", "yb01", Instant.now(),
                null, null, null, new BigDecimal("1.0"), new BigDecimal("300000"), 1L, Instant.now(),
                "J18.900", "肺炎", 1,
                "[{\"code\":\"J18.900\",\"name\":\"肺炎\",\"primary\":true}]");
    }

    private ExportBatch batch(String status, long version) {
        return new ExportBatch(UUID.randomUUID(), "EB2026110100001", LocalDate.now(),
                LocalDate.now(), 1, new BigDecimal("10.00"), new BigDecimal("10.00"),
                new BigDecimal("0.00"), "<x/>", "hash", status, null, null, null,
                null, null, null, "yb01", version, Instant.now());
    }

    @Test
    @DisplayName("生成批次前先取期间级 advisory lock（R5-M5 防并发重复报盘）")
    void createAcquiresPeriodLockBeforeCounting() {
        when(repo.countConfirmedBillsPending(any(), any())).thenReturn(1L);
        when(repo.listConfirmedBills(any(), any(), anyInt())).thenReturn(List.of(bill()));
        when(repo.nextExportBatchNo()).thenReturn("EB2026110100001");
        LocalDate day = LocalDate.now();

        service.create(day, day, "yb01");

        InOrder order = inOrder(repo);
        order.verify(repo).lockExportPeriod(day, day);
        order.verify(repo).countConfirmedBillsPending(day, day);
        order.verify(repo).insertExportBatch(any());
    }

    @Test
    @DisplayName("期间内无已确认清单：409")
    void createRejectsEmptyPeriod() {
        when(repo.countConfirmedBillsAlreadyExported(any(), any())).thenReturn(0L);
        when(repo.listConfirmedBills(any(), any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(LocalDate.now(), LocalDate.now(), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_EXPORT_EMPTY);
        verify(repo, never()).insertExportBatch(any());
    }

    @Test
    @DisplayName("本期间清单已全部报盘：409 并在消息中给出张数（MF-01）")
    void createRejectsWhenAllAlreadyExported() {
        when(repo.countConfirmedBillsAlreadyExported(any(), any())).thenReturn(7L);
        when(repo.listConfirmedBills(any(), any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(LocalDate.now(), LocalDate.now(), "yb01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("7")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_EXPORT_EMPTY);
        verify(repo, never()).insertExportBatch(any());
    }

    @Test
    @DisplayName("生成批次：回显被跳过的已报盘张数（MF-01）")
    void createReportsExcludedCount() {
        when(repo.countConfirmedBillsAlreadyExported(any(), any())).thenReturn(3L);
        when(repo.countConfirmedBillsPending(any(), any())).thenReturn(4L);
        when(repo.listConfirmedBills(any(), any(), anyInt())).thenReturn(List.of(bill()));
        when(repo.nextExportBatchNo()).thenReturn("EB2026110100001");

        SettlementExportService.ExportCreateResult result =
                service.create(LocalDate.now(), LocalDate.now(), "yb01");

        assertThat(result.excludedAlreadyExported()).isEqualTo(3);
        assertThat(result.batch().billCount()).isEqualTo(1);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repo).logChange(eq("yb01"), eq("EXPORT_BATCH_CREATE"), eq("EXPORT_BATCH"),
                eq("EB2026110100001"), detail.capture());
        assertThat(detail.getValue()).contains("\"excludedAlreadyExported\":3");
        assertThat(service.summary(result.batch(), 3, 0).excludedAlreadyExported()).isEqualTo(3);
    }

    @Test
    @DisplayName("单批超过 500 张：只出前 500 张并回显剩余，不再拒绝（SF-02）")
    void createTruncatesToMaxBatchAndReportsRemaining() {
        List<SettlementBill> bills = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            bills.add(bill());
        }
        when(repo.countConfirmedBillsPending(any(), any())).thenReturn(700L);
        when(repo.listConfirmedBills(any(), any(), anyInt())).thenReturn(bills);
        when(repo.nextExportBatchNo()).thenReturn("EB2026110100001");

        SettlementExportService.ExportCreateResult result =
                service.create(LocalDate.now(), LocalDate.now(), "yb01");

        assertThat(result.batch().billCount()).isEqualTo(500);
        assertThat(result.remainingPending()).isEqualTo(200);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(repo).listConfirmedBills(any(), any(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(500);
    }

    @Test
    @DisplayName("期间非法：结束早于开始、超过 92 天、晚于今天都拒绝")
    void createRejectsInvalidPeriod() {
        LocalDate today = LocalDate.now();
        assertThatThrownBy(() -> service.create(today, today.minusDays(1), "yb01"))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create(today.minusDays(100), today, "yb01"))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create(today, today.plusDays(1), "yb01"))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).listConfirmedBills(any(), any(), anyInt());
    }

    @Test
    @DisplayName("生成批次：XML 含转义后的项目名、哈希 64 位、逐张落 item 并写审计")
    void createBuildsPayloadAndAudits() {
        when(repo.countConfirmedBillsPending(any(), any())).thenReturn(1L);
        when(repo.listConfirmedBills(any(), any(), anyInt())).thenReturn(List.of(bill()));
        when(repo.nextExportBatchNo()).thenReturn("EB2026110100001");

        SettlementExportService.ExportCreateResult result =
                service.create(LocalDate.now(), LocalDate.now(), "yb01");

        ExportBatch batch = result.batch();
        assertThat(batch.payload()).contains("<settlementBatch").contains("A&amp;B")
                .contains("<line chargeNo=\"CH1\"")
                // MF-15：省局要求主诊断 + 其他诊断，报文必须带出
                .contains("primaryDiagnosisCode=\"J18.900\"")
                .contains("primaryDiagnosisName=\"肺炎\"")
                .contains("diagnosisCount=\"1\"")
                .contains("<diagnosis code=\"J18.900\"")
                .contains("primary=\"true\"");
        assertThat(batch.payloadHash()).hasSize(64);
        assertThat(batch.billCount()).isEqualTo(1);
        assertThat(batch.totalAmount()).isEqualByComparingTo("10.00");
        ArgumentCaptor<ExportBatch> captor = ArgumentCaptor.forClass(ExportBatch.class);
        verify(repo).insertExportBatch(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ExportBatch.GENERATED);
        verify(repo).insertExportItem(captor.getValue().id(), billId, "SB001");
    }

    @Test
    @DisplayName("明细 JSON 损坏：显式失败，不导出残缺报文")
    void createRejectsCorruptDetail() {
        SettlementBill broken = new SettlementBill(billId, "SB002", UUID.randomUUID(),
                UUID.randomUUID(), "P002", UUID.randomUUID(), new BigDecimal("10.00"),
                new BigDecimal("10.00"), new BigDecimal("0.00"), new BigDecimal("10.00"),
                new BigDecimal("0.00"), SettlementBill.CONFIRMED, "not-json", "admin01", "yb01",
                Instant.now(), null, null, null, BigDecimal.ONE, new BigDecimal("300000"), 1L,
                Instant.now(), null, null, 0, "[]");
        when(repo.countConfirmedBillsPending(any(), any())).thenReturn(1L);
        when(repo.listConfirmedBills(any(), any(), anyInt())).thenReturn(List.of(broken));

        assertThatThrownBy(() -> service.create(LocalDate.now(), LocalDate.now(), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_EXPORT_INVALID);
        verify(repo, never()).insertExportBatch(any());
    }

    @Test
    @DisplayName("回执：已登记过的批次再次登记 409")
    void receiptRejectsDuplicate() {
        ExportBatch accepted = batch(ExportBatch.ACCEPTED, 1L);
        when(repo.findExportBatch(accepted.id())).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.receipt(accepted.id(),
                new ExportReceiptReq("ACCEPTED", "R-1", null), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_EXPORT_INVALID);
        verify(repo, never()).updateExportReceipt(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("回执：REJECTED 必须填写原因")
    void receiptRejectedRequiresMessage() {
        ExportBatch generated = batch(ExportBatch.GENERATED, 0L);
        when(repo.findExportBatch(generated.id())).thenReturn(Optional.of(generated));

        assertThatThrownBy(() -> service.receipt(generated.id(),
                new ExportReceiptReq("REJECTED", "R-2", "  "), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("回执：CAS 失败转 409")
    void receiptCasConflict() {
        ExportBatch generated = batch(ExportBatch.GENERATED, 0L);
        when(repo.findExportBatch(generated.id())).thenReturn(Optional.of(generated));
        when(repo.updateExportReceipt(generated.id(), "ACCEPTED", "R-3", null, 0L)).thenReturn(false);

        assertThatThrownBy(() -> service.receipt(generated.id(),
                new ExportReceiptReq("ACCEPTED", "R-3", null), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("回执：成功登记并写 before/after 审计")
    void receiptSuccessWritesAudit() {
        ExportBatch generated = batch(ExportBatch.GENERATED, 0L);
        ExportBatch accepted = batch(ExportBatch.ACCEPTED, 1L);
        when(repo.findExportBatch(generated.id()))
                .thenReturn(Optional.of(generated)).thenReturn(Optional.of(accepted));
        when(repo.updateExportReceipt(generated.id(), "ACCEPTED", "R-4", "ok", 0L)).thenReturn(true);

        ExportBatch result = service.receipt(generated.id(),
                new ExportReceiptReq("ACCEPTED", "R-4", "ok"), "yb01");

        assertThat(result.status()).isEqualTo(ExportBatch.ACCEPTED);
        verify(repo).logChange(eq("yb01"), eq("EXPORT_RECEIPT"), eq("EXPORT_BATCH"),
                eq("EB2026110100001"), anyString());
    }

    // ---------------------------------------------------------------- 撤销（SF-01）

    @Test
    @DisplayName("撤销：未回执批次成功，落 CANCELLED 并写审计")
    void cancelGeneratedBatch() {
        ExportBatch generated = batch(ExportBatch.GENERATED, 0L);
        ExportBatch cancelled = batch(ExportBatch.CANCELLED, 1L);
        when(repo.findExportBatch(generated.id()))
                .thenReturn(Optional.of(generated)).thenReturn(Optional.of(cancelled));
        when(repo.cancelExportBatch(generated.id(), "period wrong", "yb01", 0L)).thenReturn(true);

        ExportBatch result = service.cancel(generated.id(), "period wrong", "yb01");

        assertThat(result.status()).isEqualTo(ExportBatch.CANCELLED);
        verify(repo).logChange(eq("yb01"), eq("EXPORT_BATCH_CANCEL"), eq("EXPORT_BATCH"),
                eq("EB2026110100001"), anyString());
    }

    @Test
    @DisplayName("撤销：已回执批次拒绝（只能走省局冲销）")
    void cancelRejectsReceiptedBatch() {
        ExportBatch accepted = batch(ExportBatch.ACCEPTED, 1L);
        when(repo.findExportBatch(accepted.id())).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.cancel(accepted.id(), "wrong", "yb01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能撤销")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_EXPORT_INVALID);
        verify(repo, never()).cancelExportBatch(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("撤销：原因为空拒绝")
    void cancelRequiresReason() {
        ExportBatch generated = batch(ExportBatch.GENERATED, 0L);
        when(repo.findExportBatch(generated.id())).thenReturn(Optional.of(generated));

        assertThatThrownBy(() -> service.cancel(generated.id(), "  ", "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 报文下载（MF-02）

    @Test
    @DisplayName("报文下载：写 EXPORT_PAYLOAD_DOWNLOAD 审计（含批次号与哈希）")
    void payloadDownloadWritesAudit() {
        ExportBatch generated = batch(ExportBatch.GENERATED, 0L);
        when(repo.findExportBatch(generated.id())).thenReturn(Optional.of(generated));

        ExportBatch result = service.payload(generated.id(), "yb01");

        assertThat(result.payload()).isEqualTo("<x/>");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repo).logChange(eq("yb01"), eq("EXPORT_PAYLOAD_DOWNLOAD"), eq("EXPORT_BATCH"),
                eq("EB2026110100001"), detail.capture());
        assertThat(detail.getValue()).contains("EB2026110100001", "hash", "billCount");
    }

    @Test
    @DisplayName("XML 转义：五个特殊字符全部处理")
    void escapeHandlesXmlSpecialChars() {
        assertThat(SettlementExportService.escape("a&b<c>\"d\"'e'"))
                .isEqualTo("a&amp;b&lt;c&gt;&quot;d&quot;&apos;e&apos;");
        assertThat(SettlementExportService.escape(null)).isEmpty();
    }
}
