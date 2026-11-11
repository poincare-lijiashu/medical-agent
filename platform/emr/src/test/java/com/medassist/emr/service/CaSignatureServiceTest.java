package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.CaSignature;
import com.medassist.emr.domain.EmrModel.CaTimestamp;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.service.CaSignatureProvider.CaSignResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CA 电子签名：签署、验签成功/失败、撤销与台账。
 */
class CaSignatureServiceTest {

    private EmrRepository repo;
    private CaSignatureProvider provider;
    private CaTimestampClient timestampClient;
    private CaSignatureService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID recordId = UUID.randomUUID();
    private final UUID signatureId = UUID.randomUUID();
    private final UUID caId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        provider = mock(CaSignatureProvider.class);
        timestampClient = mock(CaTimestampClient.class);
        when(timestampClient.enabled()).thenReturn(false);
        when(timestampClient.required()).thenReturn(false);
        when(repo.findCaTimestamp(any())).thenReturn(Optional.empty());
        service = new CaSignatureService(repo, provider, objectMapper, timestampClient);
        when(provider.mode()).thenReturn("MOCK");
    }

    private MedicalRecord record() {
        Instant now = Instant.now();
        return new MedicalRecord(recordId, "MR20261104000001", UUID.randomUUID(), "P001",
                UUID.randomUUID(), "V001", UUID.randomUUID(), "ADMISSION", "TPL-1", "入院记录",
                "{}", "主诉：胸痛", "doctor01", MedicalRecord.SUBMITTED, 1, null, null, null,
                null, 0, now);
    }

    private RecordSignature signature() {
        return new RecordSignature(signatureId, recordId, 1, "doctor01",
                RecordSignature.AUTHOR, Instant.now(), "HASH-001");
    }

    private CaSignature ca(String status, Boolean lastResult, int verifyCount) {
        return new CaSignature(caId, signatureId, recordId, 1, "doctor01",
                RecordSignature.AUTHOR, "HASH-001", "MOCK", "MOCK-doctor01", "HMAC-SHA256",
                "SIG-001", Instant.now(), status, verifyCount, null, lastResult, null, null,
                null, Instant.now());
    }

    @Test
    @DisplayName("签署：写入证书序列号/签名值；同一签名行不能重复")
    void signWritesCaSignature() {
        when(repo.findCaSignatureBySignature(signatureId)).thenReturn(Optional.empty());
        when(provider.sign(any())).thenReturn(new CaSignResult("MOCK-doctor01", "SIG-001",
                "HMAC-SHA256", Instant.now()));

        var result = service.sign(record(), signature(), "doctor01");
        assertThat(result.certificateSerial()).isEqualTo("MOCK-doctor01");
        assertThat(result.status()).isEqualTo(CaSignature.VALID);
        verify(repo).insertCaSignature(any());

        when(repo.findCaSignatureBySignature(signatureId)).thenReturn(Optional.of(ca(
                CaSignature.VALID, null, 0)));
        assertThatThrownBy(() -> service.sign(record(), signature(), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CA_SIGNATURE_EXISTS);
    }

    @Test
    @DisplayName("TSA 故障：未强制时签名继续且不伪造时间戳；强制时整笔失败")
    void timestampOutageIsBestEffortUnlessRequired() {
        when(repo.findCaSignatureBySignature(signatureId)).thenReturn(Optional.empty());
        when(provider.sign(any())).thenReturn(new CaSignResult("MOCK-doctor01", "SIG-001",
                "HMAC-SHA256", Instant.now()));
        when(timestampClient.enabled()).thenReturn(true);
        when(timestampClient.timestamp(any())).thenThrow(new BusinessException(
                ErrorCode.CA_PROVIDER_UNAVAILABLE, "TSA down"));

        var result = service.sign(record(), signature(), "doctor01");
        assertThat(result.status()).isEqualTo(CaSignature.VALID);
        verify(repo, never()).insertCaTimestamp(any());

        when(timestampClient.required()).thenReturn(true);
        assertThatThrownBy(() -> service.sign(record(), signature(), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CA_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("验签成功：计数 +1 并返回最新记录")
    void verifySuccess() {
        CaSignature original = ca(CaSignature.VALID, null, 0);
        CaSignature verified = ca(CaSignature.VALID, true, 1);
        when(repo.findCaSignature(caId)).thenReturn(Optional.of(original), Optional.of(verified));
        when(provider.verify(original)).thenReturn(true);
        when(repo.recordCaVerify(caId, true)).thenReturn(true);

        var result = service.verify(caId, "qc01");
        assertThat(result.lastVerifyResult()).isTrue();
        assertThat(result.verifyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("验签失败：先落失败结果，再返回 409")
    void verifyFailureRecordsThenThrows() {
        CaSignature original = ca(CaSignature.VALID, null, 0);
        CaSignature failed = ca(CaSignature.VALID, false, 1);
        when(repo.findCaSignature(caId)).thenReturn(Optional.of(original), Optional.of(failed));
        when(provider.verify(original)).thenReturn(false);
        when(repo.recordCaVerify(caId, false)).thenReturn(true);

        assertThatThrownBy(() -> service.verify(caId, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CA_VERIFY_FAILED);
        verify(repo).recordCaVerify(caId, false);
    }

    @Test
    @DisplayName("强制时间戳：签名缺令牌时验签判失败并留痕")
    void verifyRequiresTimestampWhenConfigured() {
        CaSignature original = ca(CaSignature.VALID, null, 0);
        CaSignature failed = ca(CaSignature.VALID, false, 1);
        when(repo.findCaSignature(caId)).thenReturn(Optional.of(original), Optional.of(failed));
        when(provider.verify(original)).thenReturn(true);
        when(timestampClient.required()).thenReturn(true);
        when(repo.recordCaVerify(caId, false)).thenReturn(true);

        assertThatThrownBy(() -> service.verify(caId, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CA_VERIFY_FAILED);
        verify(repo).recordCaVerify(caId, false);
    }

    @Test
    @DisplayName("时间戳证据：有令牌返回原文；无令牌 404")
    void timestampEvidence() {
        when(repo.findCaSignature(caId)).thenReturn(Optional.of(ca(CaSignature.VALID, null, 0)));
        when(repo.findCaTimestamp(caId)).thenReturn(Optional.of(new CaTimestamp(caId,
                "https://tsa.example/tsr", "TOKEN", Instant.now(), Instant.now())));
        assertThat(service.timestampEvidence(caId).token()).isEqualTo("TOKEN");

        when(repo.findCaTimestamp(caId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.timestampEvidence(caId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("撤销：已撤销幂等；原因必填；状态 CAS 失败 409")
    void revokeRules() {
        when(repo.findCaSignature(caId)).thenReturn(Optional.of(
                ca(CaSignature.REVOKED, true, 1)));
        assertThat(service.revoke(caId, null, "admin01").status())
                .isEqualTo(CaSignature.REVOKED);

        CaSignature valid = ca(CaSignature.VALID, null, 0);
        when(repo.findCaSignature(caId)).thenReturn(Optional.of(valid));
        assertThatThrownBy(() -> service.revoke(caId, " ", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CA_REVOKE_REASON_REQUIRED);

        when(repo.revokeCaSignature(caId, "证书吊销", "admin01")).thenReturn(false);
        assertThatThrownBy(() -> service.revoke(caId, "证书吊销", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("台账：状态非法 422；看板映射 provider 模式")
    void listAndBoard() {
        assertThatThrownBy(() -> service.list(null, "UNKNOWN", null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).listCaSignatures(any(), any(), any(), anyInt());

        when(repo.caBoard(any(), any())).thenReturn(new EmrRepository.CaBoardRow(2, 5, 1, 0));
        var board = service.board();
        assertThat(board.signedToday()).isEqualTo(2);
        assertThat(board.providerMode()).isEqualTo("MOCK");
    }
}
