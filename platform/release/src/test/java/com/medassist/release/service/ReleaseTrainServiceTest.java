package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.config.ReleaseProperties;
import com.medassist.release.domain.ReleaseModel.ChangeWindow;
import com.medassist.release.domain.ReleaseModel.Compatibility;
import com.medassist.release.domain.ReleaseModel.Train;
import com.medassist.release.domain.ReleaseModel.TrainItem;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
 * 发布列车：车厢校验、兼容冲突、冻结窗口与就绪报告。
 */
class ReleaseTrainServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private static final UUID TRAIN_ID = UUID.randomUUID();
    private static final UUID CLINICAL_VERSION = UUID.randomUUID();
    private static final UUID AUTH_VERSION = UUID.randomUUID();

    private ReleaseRepository repo;
    private ReleaseCatalogService catalog;
    private ReleaseTrainService service;

    @BeforeEach
    void setUp() {
        repo = mock(ReleaseRepository.class);
        catalog = mock(ReleaseCatalogService.class);
        service = new ReleaseTrainService(repo, catalog, new ReleaseProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repo.findTrain(TRAIN_ID)).thenReturn(Optional.of(train("PLANNED")));
    }

    @Test
    @DisplayName("加车厢：版本必须属于该组件且已发布")
    void addItemValidatesVersion() {
        Version other = version(CLINICAL_VERSION, "platform-pharmacy", "1.0.0", "RELEASED");
        when(catalog.requireVersion(CLINICAL_VERSION)).thenReturn(other);

        assertThatThrownBy(() -> service.addItem(TRAIN_ID,
                new ReleaseDtos.TrainItemReq("platform-clinical", CLINICAL_VERSION),
                "release01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_TRAIN_ITEM_INVALID);
        verify(repo, never()).insertTrainItem(any(), any(), any(), any());
    }

    @Test
    @DisplayName("冻结：兼容矩阵 REQUIRES 缺少组件时拒绝")
    void freezeRejectsMissingDependency() {
        Version clinical = version(CLINICAL_VERSION, "platform-clinical", "2.0.0", "RELEASED");
        Version auth = version(AUTH_VERSION, "platform-auth", "3.0.0", "RELEASED");
        when(repo.listTrainItems(TRAIN_ID)).thenReturn(List.of(
                item("platform-clinical", CLINICAL_VERSION)));
        when(repo.findVersion(CLINICAL_VERSION)).thenReturn(Optional.of(clinical));
        when(repo.findVersion(AUTH_VERSION)).thenReturn(Optional.of(auth));
        when(repo.listCompatibilityForVersions(List.of(CLINICAL_VERSION))).thenReturn(List.of(
                new Compatibility(UUID.randomUUID(), CLINICAL_VERSION, "REQUIRES", AUTH_VERSION,
                        "needs new auth", "release01", NOW)));

        assertThatThrownBy(() -> service.freeze(TRAIN_ID, "release01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_COMPATIBILITY_CONFLICT);
    }

    @Test
    @DisplayName("发布：冻结窗口内禁止发布")
    void releaseBlockedByFreeze() {
        when(repo.findTrain(TRAIN_ID)).thenReturn(Optional.of(train("FROZEN")));
        Version clinical = version(CLINICAL_VERSION, "platform-clinical", "2.0.0", "RELEASED");
        when(repo.listTrainItems(TRAIN_ID)).thenReturn(List.of(
                item("platform-clinical", CLINICAL_VERSION)));
        when(repo.findVersion(CLINICAL_VERSION)).thenReturn(Optional.of(clinical));
        when(repo.listCompatibilityForVersions(List.of(CLINICAL_VERSION))).thenReturn(List.of());
        when(repo.activeFreezes(any(), eq("platform-clinical"))).thenReturn(List.of(window()));

        assertThatThrownBy(() -> service.release(TRAIN_ID, "release01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_FREEZE_ACTIVE);
    }

    @Test
    @DisplayName("就绪报告：空列车不能发布")
    void readinessOnEmptyTrain() {
        when(repo.listTrainItems(TRAIN_ID)).thenReturn(List.of());

        Map<String, Object> readiness = service.readiness(TRAIN_ID);

        assertThat(readiness).containsEntry("ready", false);
    }

    private static Train train(String status) {
        return new Train(TRAIN_ID, "2026.11", "November train", LocalDate.of(2026, 11, 20),
                NOW.minusSeconds(86400), status, null, null, "release01", 1, NOW, NOW, List.of());
    }

    private static TrainItem item(String component, UUID versionId) {
        return new TrainItem(UUID.randomUUID(), TRAIN_ID, component, versionId, "release01", "2.0.0");
    }

    private static Version version(UUID id, String component, String no, String status) {
        return new Version(id, component, no, "abc", "sha256:x", null, status, false,
                "changelog text", status.equals("RELEASED") ? NOW : null, "release01", 0, NOW, NOW);
    }

    private static ChangeWindow window() {
        return new ChangeWindow(UUID.randomUUID(), "Year-end freeze", "FREEZE",
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), List.of(), "year end", "ACTIVE",
                "release01", 0);
    }
}
