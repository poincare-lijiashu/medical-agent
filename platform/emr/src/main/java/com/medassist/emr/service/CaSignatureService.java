package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.emr.domain.EmrModel.CaSignature;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.service.CaSignatureProvider.CaSignCommand;
import com.medassist.emr.service.CaSignatureProvider.CaSignResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CA 电子签名（G41）。
 *
 * <p>签名在病历签名事务内完成：CA 失败则整笔签名回滚，避免"系统签名成功但无 CA 证书"。
 * 验签记录每次结果；撤销保留原因与撤签人。
 */
@Service
public class CaSignatureService {

    private static final int LIST_LIMIT = 200;
    private static final Set<String> STATUSES = Set.of(CaSignature.VALID, CaSignature.REVOKED);

    private final EmrRepository repo;
    private final CaSignatureProvider provider;
    private final ObjectMapper objectMapper;

    public CaSignatureService(EmrRepository repo, CaSignatureProvider provider,
                              ObjectMapper objectMapper) {
        this.repo = repo;
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    public String mode() {
        return provider.mode();
    }

    /**
     * 锁外预签（G75）：只外呼 CA 厂商，不写库、不占行锁。
     *
     * <p>调用方必须先持有"无锁读取的病历快照"，拿到结果后在自己的短事务里
     * 复核哈希/版本一致再落库。这样 CA 厂商抖动时阻塞的是本请求，而不是
     * 该病历的整行（原先锁内调 CA，厂商慢 2 秒=同一病历 2 秒内所有写操作排队）。
     */
    public CaSignResult preSign(MedicalRecord record, String signer, String signType,
                                String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            throw new BusinessException(ErrorCode.CA_SIGNATURE_INVALID, "病历签名缺少内容哈希");
        }
        return provider.sign(new CaSignCommand(record.recordNo(), contentHash, signer, signType));
    }

    /**
     * 锁内落库（G75）：把 {@link #preSign} 的结果与病历签名写在同一事务。
     *
     * <p>调用方必须已持有该病历行锁，并已复核内容哈希与预签时一致。
     */
    public CaSignature persist(MedicalRecord record, RecordSignature signature,
                               CaSignResult result, String actor) {
        if (repo.findCaSignatureBySignature(signature.id()).isPresent()) {
            throw new BusinessException(ErrorCode.CA_SIGNATURE_EXISTS);
        }
        CaSignature ca = new CaSignature(UUID.randomUUID(), signature.id(), record.id(),
                signature.revision(), signature.signer(), signature.signType(),
                signature.contentHash(), provider.mode(), result.certificateSerial(),
                result.algorithm(), result.signatureValue(), result.signedAt(),
                CaSignature.VALID, 0, null, null, null, null, null, Instant.now());
        repo.insertCaSignature(ca);
        audit(actor, "CA_SIGN", ca.id().toString(), Map.of(
                "recordNo", record.recordNo(), "revision", signature.revision(),
                "signType", signature.signType(), "certificateSerial",
                result.certificateSerial(), "algorithm", result.algorithm()));
        return ca;
    }

    /**
     * 兼容入口：预签 + 落库合并在一个事务里。
     *
     * <p>只保留给"调用方已经不需要控制锁边界"的场景；病历签名必须走
     * {@link #preSign} + {@link #persist}，否则会退化成锁内调下游 HTTP（G75）。
     */
    @Transactional
    public CaSignature sign(MedicalRecord record, RecordSignature signature, String actor) {
        CaSignResult result = preSign(record, signature.signer(), signature.signType(),
                signature.contentHash());
        return persist(record, signature, result, actor);
    }

    /**
     * 验签：先落验签结果，再以业务错误返回失败（便于台账保留失败次数）。
     *
     * <p>该方法刻意不加事务：验签失败抛异常时，失败计数仍需提交。
     */
    public CaSignature verify(UUID id, String actor) {
        CaSignature signature = repo.findCaSignature(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CA_SIGNATURE_NOT_FOUND));
        if (!signature.valid()) {
            throw new BusinessException(ErrorCode.CA_SIGNATURE_STATUS_INVALID,
                    "CA 签名状态为 " + signature.status() + "，不能验签");
        }
        boolean valid = provider.verify(signature);
        repo.recordCaVerify(id, valid);
        CaSignature refreshed = repo.findCaSignature(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CA_SIGNATURE_NOT_FOUND));
        audit(actor, valid ? "CA_VERIFY" : "CA_VERIFY_FAILED", id.toString(),
                Map.of("signer", signature.signer(), "recordId",
                        signature.recordId().toString(), "valid", valid));
        if (!valid) {
            throw new BusinessException(ErrorCode.CA_VERIFY_FAILED);
        }
        return refreshed;
    }

    @Transactional
    public CaSignature revoke(UUID id, String reason, String actor) {
        String operator = requireActor(actor);
        CaSignature signature = repo.findCaSignature(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CA_SIGNATURE_NOT_FOUND));
        if (CaSignature.REVOKED.equals(signature.status())) {
            return signature;
        }
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.CA_REVOKE_REASON_REQUIRED);
        }
        if (!repo.revokeCaSignature(id, normalized, operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "CA 签名已被他人撤销，请刷新后重试");
        }
        audit(operator, "CA_REVOKE", id.toString(),
                Map.of("reason", normalized, "signer", signature.signer()));
        return repo.findCaSignature(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CA_SIGNATURE_NOT_FOUND));
    }

    /**
     * 签名所属病历（G51）：验签/撤销前先做病历归属校验，越权与不存在同码。
     */
    public UUID recordIdOf(UUID signatureId) {
        return repo.findCaSignature(signatureId)
                .map(CaSignature::recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CA_SIGNATURE_NOT_FOUND));
    }

    public List<CaSignature> list(UUID recordId, String status, String signer, int limit) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "CA 签名状态只能是 VALID / REVOKED");
        }
        int size = limit <= 0 ? 100 : Math.min(limit, LIST_LIMIT);
        return repo.listCaSignatures(recordId, normalized, blankToNull(signer), size);
    }

    public Board board() {
        LocalDate today = BusinessTime.today();
        OffsetDateTime from = today.atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        OffsetDateTime to = today.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        EmrRepository.CaBoardRow row = repo.caBoard(from, to);
        return new Board(row.signedToday(), row.validTotal(), row.revokedTotal(),
                row.verifyFailuresToday(), provider.mode());
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行 CA 操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "CA_SIGNATURE", targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "CA_SIGNATURE", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }

    public record Board(long signedToday, long validTotal, long revokedTotal,
                        long verifyFailuresToday, String providerMode) {
    }
}
