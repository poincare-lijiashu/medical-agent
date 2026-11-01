package com.medassist.kb.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.kb.domain.KbModel.Member;
import com.medassist.kb.domain.KbModel.Space;
import com.medassist.kb.repo.KbRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 知识空间访问守卫（隔离与数据权限的唯一入口）。
 *
 * <p>规则：
 * <ol>
 *   <li>空间必须存在且为 ACTIVE（归档空间只读不到、写不进）；</li>
 *   <li>成员角色权重：OWNER(3) &gt; EDITOR(2) &gt; VIEWER(1)；</li>
 *   <li>PUBLIC 空间对具备检索权限的登录用户开放**只读**（写入仍需成员）；</li>
 *   <li>没有 {@code X-User-Id} 一律拒绝——知识库不做"匿名可读"。</li>
 * </ol>
 *
 * <p>所有读写服务都必须经过这里，避免"某个接口忘了加 space_id 过滤"导致的跨空间越权。
 */
@Service
public class SpaceAccessGuard {

    private final KbRepository repo;

    public SpaceAccessGuard(KbRepository repo) {
        this.repo = repo;
    }

    /** 校验空间存在且未归档。 */
    public Space requireActive(UUID spaceId) {
        Space space = repo.findSpace(spaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_SPACE_NOT_FOUND));
        if (!space.active()) {
            throw new BusinessException(ErrorCode.KB_SPACE_STATUS_INVALID,
                    "知识空间已归档，不能再访问");
        }
        return space;
    }

    /** 校验"可读"：成员，或 PUBLIC 空间的登录用户。 */
    public Space requireReadable(UUID spaceId, UUID userId) {
        Space space = requireActive(spaceId);
        if (userId == null) {
            throw new BusinessException(ErrorCode.KB_SPACE_ACCESS_DENIED);
        }
        if (space.openToAll() || roleWeight(spaceId, userId) >= 1) {
            return space;
        }
        throw new BusinessException(ErrorCode.KB_SPACE_ACCESS_DENIED);
    }

    /** 校验最低角色（写入为 EDITOR，管理为 OWNER）。 */
    public Space requireRole(UUID spaceId, UUID userId, String minRole) {
        Space space = requireActive(spaceId);
        if (userId == null) {
            throw new BusinessException(ErrorCode.KB_SPACE_ACCESS_DENIED);
        }
        int required = weightOf(minRole);
        int actual = roleWeight(spaceId, userId);
        if (actual >= required) {
            return space;
        }
        throw new BusinessException(ErrorCode.KB_SPACE_ACCESS_DENIED,
                "权限不足：该操作需要 " + minRole + " 及以上角色");
    }

    private int roleWeight(UUID spaceId, UUID userId) {
        return repo.findMember(spaceId, userId).map(Member::weight).orElse(0);
    }

    private static int weightOf(String role) {
        return switch (role) {
            case Member.OWNER -> 3;
            case Member.EDITOR -> 2;
            default -> 1;
        };
    }
}
