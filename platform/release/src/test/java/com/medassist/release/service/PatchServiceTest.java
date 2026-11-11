package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.config.ReleaseProperties;
import com.medassist.release.domain.ReleaseModel.ChangeWindow;
import com.medassist.release.domain.ReleaseModel.Patch;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 补丁：提交人不能自审、修复版本必须先发布、冻结窗口拦截。
 */
class PatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private static final UUID PATCH_ID = UUID.randomUUID();
    private static final UUID FIXED_VERSION = UUID.randomUUID();

    private ReleaseRepository repo;
    private ReleaseCatalogService catalog;
    private PatchService service;

    @BeforeEach
    void setUp() {
        repo = mock(ReleaseRepository.class);
        catalog = mock(ReleaseCatalogService.class);
        service = new PatchService(repo, catalog, new ReleaseProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("审批：提交人不能审批自己的补丁")
    void selfReviewRejected() {
        when(repo.findPatch(PATCH_ID)).thenReturn(Optional.of(patch("REVIEW")));

        assertThatThrownBy(() -> service.approve(PATCH_ID, "release01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_PATCH_SELF_REVIEW);
    }

    @Test
    @DisplayName("发布：修复版本还没有 RELEASED 时拒绝")
    void releaseRequiresPublishedFix() {
        when(repo.findPatch(PATCH_ID)).thenReturn(Optional.of(patch("APPROVED")));
        when(catalog.requireVersion(FIXED_VERSION)).thenReturn(new Version(FIXED_VERSION,
                "platform-clinical", "1.0.1", "abc", "sha256:x", null, "DRAFT", false,
                "fix", null, "release01", 0, NOW, NOW));

        assertThatThrownBy(() -> service.release(PATCH_ID, "manager01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_RELEASE_INVALID);
    }

    @Test
    @DisplayName("发布：冻结窗口内拒绝发布补丁")
    void releaseBlockedByFreeze() {
        when(repo.findPatch(PATCH_ID)).thenReturn(Optional.of(patch("APPROVED")));
        when(catalog.requireVersion(FIXED_VERSION)).thenReturn(new Version(FIXED_VERSION,
                "platform-clinical", "1.0.1", "abc", "sha256:x", null, "RELEASED", false,
                "fix", NOW, "release01", 0, NOW, NOW));
        when(repo.activeFreezes(any(), eq("platform-clinical"))).thenReturn(List.of(
                new ChangeWindow(UUID.randomUUID(), "freeze", "FREEZE", NOW.minusSeconds(60),
                        NOW.plusSeconds(60), List.of(), "year end", "ACTIVE", "release01", 0)));

        assertThatThrownBy(() -> service.release(PATCH_ID, "manager01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_FREEZE_ACTIVE);
    }

    private static Patch patch(String status) {
        return new Patch(PATCH_ID, "PAT2026111200001", "Fix pool exhaustion", "HIGH",
                "platform-clinical", List.of("1.0.0"), FIXED_VERSION, "advisory", null, status,
                "release01", null, null, null, 1, NOW, NOW);
    }
}
