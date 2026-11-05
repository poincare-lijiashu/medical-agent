package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.emr.domain.EmrModel.CaSignature;
import com.medassist.emr.service.CaSignatureService;
import com.medassist.emr.service.EmrAccessGuard;
import com.medassist.emr.web.CaDtos.BoardView;
import com.medassist.emr.web.CaDtos.CaSignatureView;
import com.medassist.emr.web.CaDtos.ModeView;
import com.medassist.emr.web.CaDtos.RevokeReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CA 电子签名台账 API（G41）。
 *
 * <p>权限点：{@code emr:ca:read} 台账、{@code emr:ca:verify} 验签、
 * {@code emr:ca:manage} 撤销；签名在病历签名接口内通过 {@code caSign=true} 触发。
 */
@RestController
@RequestMapping("/emr/ca")
public class CaController {

    private static final String READ = "emr:ca:read";
    private static final String VERIFY = "emr:ca:verify";
    private static final String MANAGE = "emr:ca:manage";

    private final CaSignatureService service;
    private final EmrAccessGuard guard;

    public CaController(CaSignatureService service, EmrAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/mode")
    public ApiResponse<ModeView> mode(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(new ModeView(service.mode()));
    }

    /**
     * 签名台账（G51）。
     *
     * <p>原实现无任何归属校验：不传 recordId 即返回全院签名台账（含 signatureValue
     * 与 contentHash）。现在按病历收敛——传 recordId 走病历归属校验；不传时只有
     * 跨院区角色可以查，单院区账号必须指定病历。
     */
    @GetMapping("/signatures")
    public ApiResponse<List<CaSignatureView>> signatures(
            @RequestParam(required = false) UUID recordId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String signer,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        if (recordId != null) {
            guard.requireRecord(scope, recordId);
        } else if (!scope.allCampuses()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "查询 CA 签名台账必须指定病历（recordId）");
        }
        return ApiResponse.ok(service.list(recordId, status, signer, limit).stream()
                .map(CaController::view).toList());
    }

    @PostMapping("/signatures/{id}/verify")
    public ApiResponse<CaSignatureView> verify(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, VERIFY);
        guard.requireRecord(scope, service.recordIdOf(id));
        return ApiResponse.ok(view(service.verify(id, actor)));
    }

    @PostMapping("/signatures/{id}/revoke")
    public ApiResponse<CaSignatureView> revoke(
            @PathVariable UUID id,
            @Valid @RequestBody RevokeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, MANAGE);
        guard.requireRecord(scope, service.recordIdOf(id));
        return ApiResponse.ok(view(service.revoke(id, req.reason(), actor)));
    }

    @GetMapping("/board")
    public ApiResponse<BoardView> board(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        var board = service.board();
        return ApiResponse.ok(new BoardView(board.signedToday(), board.validTotal(),
                board.revokedTotal(), board.verifyFailuresToday(), board.providerMode()));
    }

    private static CaSignatureView view(CaSignature s) {
        return new CaSignatureView(s.id(), s.signatureId(), s.recordId(), s.revision(),
                s.signer(), s.signType(), s.contentHash(), s.provider(), s.certificateSerial(),
                s.algorithm(), s.signatureValue(), s.signedAt(), s.status(), s.verifyCount(),
                s.lastVerifiedAt(), s.lastVerifyResult(), s.revokeReason(), s.revokedBy(),
                s.revokedAt(), s.createdAt());
    }
}
