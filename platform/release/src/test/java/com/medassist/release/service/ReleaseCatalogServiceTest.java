package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.config.ReleaseProperties;
import com.medassist.release.domain.ReleaseModel.Component;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 版本目录：重复版本、发布门槛、发布后不可改。
 */
class ReleaseCatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private static final String COMPONENT = "platform-clinical";

    private ReleaseRepository repo;
    private ReleaseCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(ReleaseRepository.class);
        service = new ReleaseCatalogService(repo, new ReleaseProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repo.findComponent(COMPONENT)).thenReturn(Optional.of(
                new Component(COMPONENT, "临床核心", "SERVICE", "CLINICAL_ONCALL",
                        "ACTIVE", 40)));
    }

    @Test
    @DisplayName("注册：同组件同版本号不能重复")
    void duplicateVersion() {
        when(repo.findVersionByKey(COMPONENT, "1.0.0")).thenReturn(Optional.of(version("1.0.0")));
        assertThatThrownBy(() -> service.registerVersion(
                new ReleaseDtos.VersionReq(COMPONENT, "1.0.0", "abc", "sha256:x",
                        null, false, "first release"), "release01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_VERSION_DUPLICATE);
        verify(repo, never()).insertVersion(any());
    }

    @Test
    @DisplayName("发布：变更说明太短或缺少镜像摘要都拒绝")
    void publishGuards() {
        Version shortLog = version("1.0.0");
        when(repo.findVersion(shortLog.id())).thenReturn(Optional.of(shortLog));
        assertThatThrownBy(() -> service.publishVersion(shortLog.id(), "release01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_RELEASE_INVALID);

        Version noDigest = new Version(shortLog.id(), COMPONENT, "1.0.0", "abc", null, null,
                "DRAFT", false, "this is a valid changelog", null, "release01", 0, NOW, NOW);
        when(repo.findVersion(shortLog.id())).thenReturn(Optional.of(noDigest));
        assertThatThrownBy(() -> service.publishVersion(shortLog.id(), "release01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_RELEASE_INVALID);
    }

    @Test
    @DisplayName("发布：写入 RELEASED 与发布时间")
    void publish() {
        Version draft = new Version(UUID.randomUUID(), COMPONENT, "1.0.0", "abc", "sha256:x",
                null, "DRAFT", false, "this is a valid changelog", null, "release01", 3,
                NOW.minusSeconds(60), NOW.minusSeconds(60));
        when(repo.findVersion(draft.id())).thenReturn(Optional.of(draft));
        when(repo.updateVersionFields(eq(draft.id()), eq(3L), anyMap())).thenReturn(true);
        when(repo.findVersion(draft.id())).thenReturn(Optional.of(draft));

        service.publishVersion(draft.id(), "release01", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(repo).updateVersionFields(eq(draft.id()), eq(3L), fields.capture());
        assertThat(fields.getValue()).containsEntry("status", "RELEASED")
                .containsEntry("released_at", NOW);
    }

    @Test
    @DisplayName("发布操作必须带真实操作人")
    void actorRequired() {
        assertThatThrownBy(() -> service.publishVersion(UUID.randomUUID(), " ", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_ACTOR_REQUIRED);
    }

    private static Version version(String no) {
        return new Version(UUID.randomUUID(), COMPONENT, no, "abc", "sha256:x", null, "DRAFT",
                false, "short", null, "release01", 0, NOW, NOW);
    }
}
