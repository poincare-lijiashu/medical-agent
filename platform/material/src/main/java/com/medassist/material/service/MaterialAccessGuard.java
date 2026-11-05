package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.material.repo.MaterialRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 物资单据院区访问守卫（G50）。
 *
 * <p>出库、退回、调拨三类单据在创建时固化院区；追溯记录挂在出库明细下，
 * 因此统一回溯到单据院区判断。科室二级库与中心批次是全院共享资源，不按院区收敛。
 *
 * <p>越权与不存在返回同一个错误码，避免调用方通过错误码差异探测单据是否存在。
 * 跨院区角色（{@code allCampuses}）直接放行，由服务层继续做自身的存在性校验。
 *
 * <p>G78：参数顺序统一为 {@code (scope, id)}，与其余 6 个守卫一致。
 */
@Component
public class MaterialAccessGuard {

    private final MaterialRepository repo;

    public MaterialAccessGuard(MaterialRepository repo) {
        this.repo = repo;
    }

    public void requireIssue(DataScope scope, UUID issueId) {
        require(issueId, scope, ErrorCode.CONSUMABLE_ISSUE_NOT_FOUND,
                repo::findIssueCampus);
    }

    /** 出库明细（追溯登记入口）。 */
    public void requireIssueItem(DataScope scope, UUID issueItemId) {
        require(issueItemId, scope, ErrorCode.CONSUMABLE_ISSUE_ITEM_NOT_FOUND,
                repo::findIssueItemCampus);
    }

    public void requireReturn(DataScope scope, UUID returnId) {
        require(returnId, scope, ErrorCode.CONSUMABLE_RETURN_NOT_FOUND,
                repo::findReturnCampus);
    }

    public void requireTransfer(DataScope scope, UUID transferId) {
        require(transferId, scope, ErrorCode.DEPT_TRANSFER_NOT_FOUND,
                repo::findDeptTransferCampus);
    }

    private void require(UUID id, DataScope scope, ErrorCode notFound,
                         java.util.function.Function<UUID, java.util.Optional<UUID>> campusLookup) {
        if (scope.allCampuses()) {
            return;
        }
        boolean visible = id != null && campusLookup.apply(id).map(scope::canSeeCampus).orElse(false);
        if (!visible) {
            throw new BusinessException(notFound);
        }
    }
}
