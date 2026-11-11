package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.config.MasterDataProperties;
import com.medassist.masterdata.domain.MasterDataModel.ChangeRequest;
import com.medassist.masterdata.domain.MasterDataModel.Domain;
import com.medassist.masterdata.domain.MasterDataModel.DomainQuality;
import com.medassist.masterdata.domain.MasterDataModel.ExternalMap;
import com.medassist.masterdata.domain.MasterDataModel.ExternalSystem;
import com.medassist.masterdata.domain.MasterDataModel.Item;
import com.medassist.masterdata.domain.MasterDataModel.ItemVersion;
import com.medassist.masterdata.domain.MasterDataModel.QualityIssue;
import com.medassist.masterdata.domain.MasterDataModel.QualityReport;
import com.medassist.masterdata.domain.MasterDataModel.VersionChainResult;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.support.CanonicalJson;
import com.medassist.masterdata.support.Snapshots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主数据变更：职责分离、版本快照、审批时点校验、映射解析不猜、版本链防篡改。
 */
class MasterDataServiceTest {

    private MasterDataRepository repo;
    private MasterDataWriter writer;
    private MasterDataService service;

    @BeforeEach
    void setUp() {
        repo = mock(MasterDataRepository.class);
        writer = mock(MasterDataWriter.class);
        service = new MasterDataService(repo, writer, new MasterDataProperties());
        when(repo.findDomain("DEPT")).thenReturn(Optional.of(domain()));
    }

    private Domain domain() {
        return new Domain("DEPT", "科室", "ORGANIZATION", "platform-clinical", "clinical_svc",
                "md_dept", "dept_code", "name", "CLINICAL_DEPT", true, List.of(),
                "科室主数据", 10, true, null, null, Map.of());
    }

    private Item item(String code, String name, String sourceSystem, long version) {
        return new Item(UUID.randomUUID(), "DEPT", code, name, null, null, null, null,
                Item.ACTIVE, sourceSystem, null, null, null, Map.of(),
                Snapshots.fingerprint(name, null, null, null, null, Item.ACTIVE, null, null,
                        Map.of()), version, Instant.now(), Instant.now());
    }

    private ChangeRequest request(UUID id, String type, Map<String, Object> payload,
                                  String submittedBy, long version) {
        return new ChangeRequest(id, "MDM-20261111-AAAA0001", type, "DEPT", "N1", payload,
                "初始化主数据", ChangeRequest.SUBMITTED, submittedBy, Instant.now(), null, null,
                null, null, version, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("提交新建申请：固定目标内容、状态 SUBMITTED，并写审计")
    void submitItemCreate() {
        when(repo.findItem("DEPT", "N1")).thenReturn(Optional.empty());
        when(repo.findOpenItemRequest("DEPT", "N1")).thenReturn(Optional.empty());

        ChangeRequest created = service.submit(ChangeRequest.ITEM_CREATE, "DEPT", "N1",
                Map.of("name", "心内科", "unit", "个"), "新开科室", "alice", "10.0.0.1", "trace-1");

        assertThat(created.status()).isEqualTo(ChangeRequest.SUBMITTED);
        assertThat(created.submittedBy()).isEqualTo("alice");
        assertThat(created.payload()).containsEntry("name", "心内科").containsEntry("status", "ACTIVE");
        verify(repo).insertChangeRequest(created);
        verify(repo).insertAudit(eq("alice"), eq("REQUEST_SUBMIT"), eq("DEPT"), eq("N1"),
                eq(created.requestNo()), any(), eq("10.0.0.1"), eq("trace-1"));
    }

    @Test
    @DisplayName("同一（域, 编码）已有待审批条目申请 → 409")
    void duplicateOpenRequest() {
        when(repo.findOpenItemRequest("DEPT", "N1")).thenReturn(Optional.of(
                request(UUID.randomUUID(), ChangeRequest.ITEM_CREATE, Map.of("name", "x"), "bob", 0)));

        assertThatThrownBy(() -> service.submit(ChangeRequest.ITEM_CREATE, "DEPT", "N1",
                Map.of("name", "心内科"), "新开科室", "alice", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).insertChangeRequest(any());
    }

    @Test
    @DisplayName("源同步条目（IN_HOUSE）不允许手工改：必须改源表后重新同步")
    void updateInHouseRejected() {
        when(repo.findItem("DEPT", "D1")).thenReturn(Optional.of(item("D1", "内科", Item.SOURCE_IN_HOUSE, 2)));
        when(repo.findOpenItemRequest("DEPT", "D1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(ChangeRequest.ITEM_UPDATE, "DEPT", "D1",
                Map.of("name", "内科一病区"), "改名", "alice", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MDM_ITEM_STATUS_INVALID);
    }

    @Test
    @DisplayName("目标内容与当前一致 → 422，不产生空变更")
    void updateWithoutChangeRejected() {
        when(repo.findItem("DEPT", "M1")).thenReturn(Optional.of(item("M1", "内科", Item.SOURCE_MANUAL, 1)));
        when(repo.findOpenItemRequest("DEPT", "M1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(ChangeRequest.ITEM_UPDATE, "DEPT", "M1",
                Map.of("name", "内科"), "改名", "alice", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MDM_CHANGE_REQUEST_INVALID);
    }

    @Test
    @DisplayName("提交人不能审批自己的申请")
    void selfReviewRejected() {
        UUID id = UUID.randomUUID();
        when(repo.findChangeRequest(id)).thenReturn(Optional.of(
                request(id, ChangeRequest.ITEM_CREATE, Map.of("name", "心内科"), "alice", 0)));

        assertThatThrownBy(() -> service.approve(id, "同意", "alice", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MDM_CHANGE_REQUEST_SELF_REVIEW);
    }

    @Test
    @DisplayName("审批新建：调用写入器落库并记录 appliedVersion=1")
    void approveItemCreate() {
        UUID id = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "心内科");
        payload.put("status", "ACTIVE");
        payload.put("attributes", Map.of());
        ChangeRequest submitted = request(id, ChangeRequest.ITEM_CREATE, payload, "alice", 0);
        ChangeRequest applied = new ChangeRequest(id, submitted.requestNo(), ChangeRequest.ITEM_CREATE,
                "DEPT", "N1", payload, submitted.reason(), ChangeRequest.APPLIED, "alice",
                submitted.submittedAt(), "bob", Instant.now(), "同意", 1L, 1, submitted.createdAt(),
                Instant.now());
        when(repo.findChangeRequest(id)).thenReturn(Optional.of(submitted), Optional.of(applied));
        when(repo.findItem("DEPT", "N1")).thenReturn(Optional.empty());
        when(writer.create(anyString(), anyString(), anyString(), any(), any(), any(), any(),
                anyString(), anyString(), any(), any(), any(), any(), anyString(), anyString(),
                any(), anyString(), anyString()))
                .thenReturn(item("N1", "心内科", Item.SOURCE_MANUAL, 0));
        when(repo.markApplied(eq(id), eq("bob"), eq("同意"), eq(1L), eq(0L))).thenReturn(true);

        ChangeRequest result = service.approve(id, "同意", "bob", "10.0.0.1", "trace-2");

        assertThat(result.status()).isEqualTo(ChangeRequest.APPLIED);
        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(writer).create(eq("DEPT"), eq("N1"), eq("心内科"), any(), any(), any(), any(),
                eq("ACTIVE"), eq(Item.SOURCE_MANUAL), any(), any(), any(), any(),
                eq("CREATE"), eq(MasterDataWriter.Ref.CHANGE_REQUEST), eq(submitted.requestNo()),
                anyString(), actor.capture());
        assertThat(actor.getValue()).isEqualTo("bob");
        verify(repo).insertAudit(eq("bob"), eq("REQUEST_APPROVE"), eq("DEPT"), eq("N1"),
                eq(submitted.requestNo()), any(), eq("10.0.0.1"), eq("trace-2"));
    }

    @Test
    @DisplayName("映射反查：无候选直接 404，不返回空列表冒充成功")
    void resolveMissingMapping() {
        when(repo.findExternalSystem("HIS")).thenReturn(Optional.of(system()));
        when(repo.findMappingsByExternal("HIS", "H-404")).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveMapping("HIS", "H-404", "alice", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MDM_MAPPING_NOT_FOUND);
    }

    @Test
    @DisplayName("映射反查：一源多码返回全部候选并标记 ambiguous")
    void resolveAmbiguousMapping() {
        when(repo.findExternalSystem("HIS")).thenReturn(Optional.of(system()));
        when(repo.findMappingsByExternal("HIS", "H-1")).thenReturn(List.of(
                mapping("D1"), mapping("D2")));

        var lookup = service.resolveMapping("HIS", "H-1", "alice", null, null);

        assertThat(lookup.candidates()).hasSize(2);
        assertThat(lookup.ambiguous()).isTrue();
    }

    @Test
    @DisplayName("版本链被改：校验定位到断点版本")
    void verifyChainDetectsTamper() {
        Item item = item("M1", "内科", Item.SOURCE_MANUAL, 2);
        when(repo.findItem("DEPT", "M1")).thenReturn(Optional.of(item));

        Map<String, Object> first = Map.of("localCode", "M1", "name", "内科", "version", 1);
        String firstChecksum = CanonicalJson.checksum(null, first);
        Map<String, Object> second = Map.of("localCode", "M1", "name", "内科一病区", "version", 2);
        String secondChecksum = CanonicalJson.checksum(firstChecksum, second);

        ItemVersion v1 = version(item.id(), 1, "CREATE", first, null, firstChecksum);
        ItemVersion v2 = version(item.id(), 2, "UPDATE", second, firstChecksum, "tampered");
        when(repo.allItemVersionsAsc(item.id())).thenReturn(List.of(v1, v2));

        VersionChainResult result = service.verifyChain("DEPT", "M1", "alice", null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.brokenAtVersion()).isEqualTo(2L);
        assertThat(result.message()).contains("校验失败");
        assertThat(secondChecksum).isNotEqualTo("tampered");
    }

    @Test
    @DisplayName("质量看板：从未同步的域标记 stale 并产出问题项")
    void qualityMarksStale() {
        when(repo.listDomainQuality()).thenReturn(List.of(new DomainQuality("DEPT", "科室",
                10, 8, 1, 1, 2, 6, 4, 1, 0, null, null, false, List.of())));
        when(repo.missingRequiredIssues(anyInt())).thenReturn(List.of());
        when(repo.ambiguousExternalIssues(anyInt())).thenReturn(List.of());
        when(repo.duplicateNameIssues(anyInt())).thenReturn(List.of());
        when(repo.missingInSourceIssues(anyInt())).thenReturn(List.of());
        when(repo.unmappedIssues(anyInt())).thenReturn(List.of(
                new QualityIssue(QualityIssue.UNMAPPED, "DEPT", "D1", "内科", "未建立任何外部编码映射")));

        QualityReport report = service.quality();

        assertThat(report.domains()).hasSize(1);
        assertThat(report.domains().get(0).syncStale()).isTrue();
        assertThat(report.issues()).extracting(QualityIssue::type)
                .contains(QualityIssue.SYNC_STALE, QualityIssue.UNMAPPED);
    }

    @Test
    @DisplayName("写操作缺少真实操作人 → 403，禁止匿名改主数据")
    void actorRequired() {
        assertThatThrownBy(() -> service.submit(ChangeRequest.ITEM_CREATE, "DEPT", "N1",
                Map.of("name", "心内科"), "新开科室", " ", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MDM_ACTOR_REQUIRED);
    }

    private ItemVersion version(UUID itemId, long no, String type, Map<String, Object> snapshot,
                                String prev, String checksum) {
        return new ItemVersion(no, itemId, "DEPT", "M1", no, type, snapshot, Map.of(), "原因",
                "MANUAL", null, "alice", prev, checksum, Instant.now());
    }

    private ExternalSystem system() {
        return new ExternalSystem("HIS", "院内 HIS", "HIS", null, null, null,
                ExternalSystem.ACTIVE, 10);
    }

    private ExternalMap mapping(String localCode) {
        return new ExternalMap(UUID.randomUUID(), "DEPT", localCode, "科室" + localCode, "HIS",
                "院内 HIS", "H-1", "HIS 科室", ExternalMap.EXACT, true, ExternalMap.ACTIVE,
                null, null, null, 0, Instant.now(), Instant.now());
    }
}
