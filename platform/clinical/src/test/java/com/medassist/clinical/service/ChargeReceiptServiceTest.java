package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeReceipt;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ReceiptDtos.VoidReceiptReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
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
 * 收费票据：只对已结算收费单开票、单收费单单有效票、重打累加、作废留原因。
 */
class ChargeReceiptServiceTest {

    private ClinicalRepository repo;
    private ChargeReceiptService service;

    private final UUID chargeId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID receiptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new ChargeReceiptService(repo, JsonMapper.builder().build());
        when(repo.nextReceiptNo()).thenReturn("RP20261104000001");
        when(repo.findReceipt(any())).thenReturn(Optional.of(receipt(ChargeReceipt.ISSUED, 0)));
        when(repo.findReceipt(receiptId)).thenReturn(Optional.of(receipt(ChargeReceipt.ISSUED, 0)));
    }

    @Test
    @DisplayName("开票：收费单不存在 → 404")
    void issueRejectsMissingCharge() {
        when(repo.findCharge(chargeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(chargeId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHARGE_NOT_FOUND);
    }

    @Test
    @DisplayName("开票：未结算收费单 → 409")
    void issueRejectsUnpaidCharge() {
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(charge(Charge.UNPAID)));

        assertThatThrownBy(() -> service.issue(chargeId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHARGE_RECEIPT_CHARGE_NOT_PAID);
        verify(repo, never()).insertReceipt(any());
    }

    @Test
    @DisplayName("开票：已有有效票据 → 409")
    void issueRejectsExistingReceipt() {
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(charge(Charge.PAID)));
        when(repo.findActiveReceiptByCharge(chargeId))
                .thenReturn(Optional.of(receipt(ChargeReceipt.ISSUED, 0)));

        assertThatThrownBy(() -> service.issue(chargeId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHARGE_RECEIPT_EXISTS);
        verify(repo, never()).insertReceipt(any());
    }

    @Test
    @DisplayName("开票：金额与支付方式取收费单快照")
    void issueSnapshotsAmountAndMethod() {
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(charge(Charge.PAID)));

        var view = service.issue(chargeId, "cashier01");

        assertThat(view.receiptNo()).isEqualTo("RP20261104000001");
        verify(repo).insertReceipt(any(ChargeReceipt.class));
    }

    @Test
    @DisplayName("重打：作废票据不可重打 → 409")
    void reprintRejectsVoided() {
        when(repo.lockReceipt(receiptId)).thenReturn(Optional.of(receipt(ChargeReceipt.VOID, 1)));

        assertThatThrownBy(() -> service.reprint(receiptId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHARGE_RECEIPT_STATUS_INVALID);
        verify(repo, never()).countReprint(any(), anyLong());
    }

    @Test
    @DisplayName("重打：累加次数（不换号）")
    void reprintIncrementsCount() {
        when(repo.lockReceipt(receiptId)).thenReturn(Optional.of(receipt(ChargeReceipt.ISSUED, 0)));
        when(repo.countReprint(receiptId, 3L)).thenReturn(true);
        when(repo.findReceipt(receiptId))
                .thenReturn(Optional.of(receipt(ChargeReceipt.ISSUED, 1)));

        var view = service.reprint(receiptId, "cashier01");

        assertThat(view.reprintCount()).isEqualTo(1);
        verify(repo).countReprint(receiptId, 3L);
    }

    @Test
    @DisplayName("作废：必须写原因")
    void voidRequiresReason() {
        when(repo.lockReceipt(receiptId)).thenReturn(Optional.of(receipt(ChargeReceipt.ISSUED, 0)));

        assertThatThrownBy(() -> service.voidReceipt(receiptId, new VoidReceiptReq(" "),
                "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHARGE_RECEIPT_VOID_REASON_REQUIRED);
        verify(repo, never()).voidReceipt(any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("作废：已作废票据不能再作废 → 409")
    void voidRejectsVoided() {
        when(repo.lockReceipt(receiptId)).thenReturn(Optional.of(receipt(ChargeReceipt.VOID, 1)));

        assertThatThrownBy(() -> service.voidReceipt(receiptId,
                new VoidReceiptReq("打印错误"), "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHARGE_RECEIPT_STATUS_INVALID);
    }

    @Test
    @DisplayName("作废：写原因并记录操作人")
    void voidSucceedsWithReason() {
        when(repo.lockReceipt(receiptId)).thenReturn(Optional.of(receipt(ChargeReceipt.ISSUED, 0)));
        when(repo.voidReceipt(receiptId, 3L, "cashier01", "打印错误")).thenReturn(true);
        ChargeReceipt voided = new ChargeReceipt(receiptId, "RP20261104000001", chargeId,
                visitId, new BigDecimal("100.00"), "CASH", ChargeReceipt.VOID, "cashier01",
                Instant.now(), "cashier01", Instant.now(), "打印错误", 0, 4L, Instant.now());
        when(repo.findReceipt(receiptId)).thenReturn(Optional.of(voided));

        var view = service.voidReceipt(receiptId, new VoidReceiptReq("打印错误"), "cashier01");

        assertThat(view.status()).isEqualTo(ChargeReceipt.VOID);
        verify(repo).voidReceipt(receiptId, 3L, "cashier01", "打印错误");
    }

    @Test
    @DisplayName("列表：必须指定就诊 → 422")
    void listRequiresVisit() {
        assertThatThrownBy(() -> service.list(null, 50))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private Charge charge(String status) {
        return new Charge(chargeId, "C20261104000001", visitId, new BigDecimal("100.00"),
                status, "CASH", Charge.PAID.equals(status) ? Instant.now() : null, 3L);
    }

    private ChargeReceipt receipt(String status, int reprintCount) {
        return new ChargeReceipt(receiptId, "RP20261104000001", chargeId, visitId,
                new BigDecimal("100.00"), "CASH", status, "cashier01", Instant.now(),
                ChargeReceipt.VOID.equals(status) ? "cashier01" : null,
                ChargeReceipt.VOID.equals(status) ? Instant.now() : null,
                ChargeReceipt.VOID.equals(status) ? "打印错误" : null,
                reprintCount, 3L, Instant.now());
    }
}
