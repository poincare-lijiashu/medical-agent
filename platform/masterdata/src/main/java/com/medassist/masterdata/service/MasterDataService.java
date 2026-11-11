package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.api.PageResult;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.config.MasterDataProperties;
import com.medassist.masterdata.domain.MasterDataModel.AuditLog;
import com.medassist.masterdata.domain.MasterDataModel.ChangeRequest;
import com.medassist.masterdata.domain.MasterDataModel.Domain;
import com.medassist.masterdata.domain.MasterDataModel.DomainQuality;
import com.medassist.masterdata.domain.MasterDataModel.DomainSummary;
import com.medassist.masterdata.domain.MasterDataModel.ExternalMap;
import com.medassist.masterdata.domain.MasterDataModel.ExternalSystem;
import com.medassist.masterdata.domain.MasterDataModel.Item;
import com.medassist.masterdata.domain.MasterDataModel.ItemDetail;
import com.medassist.masterdata.domain.MasterDataModel.ItemVersion;
import com.medassist.masterdata.domain.MasterDataModel.MappingLookup;
import com.medassist.masterdata.domain.MasterDataModel.QualityIssue;
import com.medassist.masterdata.domain.MasterDataModel.QualityReport;
import com.medassist.masterdata.domain.MasterDataModel.VersionChainResult;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.repo.MasterDataRepository.ItemFilter;
import com.medassist.masterdata.support.CanonicalJson;
import com.medassist.masterdata.support.Csv;
import com.medassist.masterdata.support.Snapshots;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.medassist.masterdata.support.FieldChecks.attributes;
import static com.medassist.masterdata.support.FieldChecks.bool;
import static com.medassist.masterdata.support.FieldChecks.checkDateOrder;
import static com.medassist.masterdata.support.FieldChecks.iso;
import static com.medassist.masterdata.support.FieldChecks.longValue;
import static com.medassist.masterdata.support.FieldChecks.optionalDate;
import static com.medassist.masterdata.support.FieldChecks.optionalEnum;
import static com.medassist.masterdata.support.FieldChecks.optionalText;
import static com.medassist.masterdata.support.FieldChecks.parseDate;
import static com.medassist.masterdata.support.FieldChecks.requireCode;
import static com.medassist.masterdata.support.FieldChecks.requireText;

/**
 * 主数据查询、变更申请/审批、映射解析、版本链校验与质量看板。
 *
 * <p>三条硬规则（也是与"演示系统"的分界线）：
 * <ol>
 *   <li><b>单条变更必须审批</b>：提交人不能审批自己的申请，审批时还要校验条目/映射版本
 *       仍是提交时的版本——否则"审的是 A、批的是 B"；</li>
 *   <li><b>版本可追溯</b>：每次落库都追加版本快照 + 字段级 diff + 原因 + 操作人 + 检验和链；</li>
 *   <li><b>解析不猜</b>：一个外部编码对应多个本地编码时返回全部候选并标记 ambiguous，
 *       绝不让业务系统"随便挑一个"。</li>
 * </ol>
 */
@Service
public class MasterDataService {

    private static final Set<String> ITEM_STATUSES = Set.of(Item.ACTIVE, Item.INACTIVE);
    private static final Set<String> MAP_TYPES = Set.of(
            ExternalMap.EXACT, ExternalMap.EQUIVALENT, ExternalMap.GROUP);
    private static final Set<String> REQUEST_STATUSES = Set.of(
            ChangeRequest.SUBMITTED, ChangeRequest.APPLIED,
            ChangeRequest.REJECTED, ChangeRequest.CANCELLED);
    private static final Set<String> CHANGE_TYPES = Set.of(
            ChangeRequest.ITEM_CREATE, ChangeRequest.ITEM_UPDATE, ChangeRequest.ITEM_STATUS,
            ChangeRequest.MAPPING_UPSERT, ChangeRequest.MAPPING_DELETE);
    private static final Set<String> AUDIT_ACTIONS = Set.of(
            "SYNC", "IMPORT_PREVIEW", "IMPORT_APPLY", "REQUEST_SUBMIT", "REQUEST_APPROVE",
            "REQUEST_REJECT", "REQUEST_CANCEL", "MAPPING_RESOLVE", "VERSION_VERIFY");
    private static final String BASE_VERSION = "_baseVersion";
    private static final int MAX_EXPORT_ROWS = 20_000;
    private static final int DETAIL_VERSION_LIMIT = 20;

    private final MasterDataRepository repo;
    private final MasterDataWriter writer;
    private final MasterDataProperties properties;

    public MasterDataService(MasterDataRepository repo, MasterDataWriter writer,
                             MasterDataProperties properties) {
        this.repo = repo;
        this.writer = writer;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 查询

    /** 域清单：注册信息 + 规模与映射覆盖统计。 */
    public List<DomainSummary> listDomains() {
        Map<String, DomainQuality> stats = new HashMap<>();
        repo.listDomainQuality().forEach(q -> stats.put(q.domainCode(), q));
        return repo.listDomains().stream().map(domain -> {
            DomainQuality q = stats.get(domain.domainCode());
            if (q == null) {
                return new DomainSummary(domain, 0, 0, 0, 0, 0, 0, 0);
            }
            return new DomainSummary(domain, q.total(), q.active(), q.inactive(), q.missingInSource(),
                    q.mappedActive(), q.unmappedActive(), q.missingRequiredMapping());
        }).toList();
    }

    public List<ExternalSystem> listExternalSystems() {
        return repo.listExternalSystems();
    }

    public PageResult<Item> listItems(String domainCode, String keyword, String status,
                                      Boolean hasMapping, Integer page, Integer size) {
        if (domainCode != null && !domainCode.isBlank()) {
            requireDomain(domainCode);
        }
        if (status != null && !status.isBlank() && !ITEM_STATUSES.contains(status)
                && !Item.MISSING_IN_SOURCE.equals(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "状态只能是 ACTIVE / INACTIVE / MISSING_IN_SOURCE");
        }
        int pageNo = properties.page(page);
        int pageSize = properties.pageSize(size);
        ItemFilter filter = new ItemFilter(
                domainCode == null ? null : domainCode.trim(),
                keyword,
                status == null ? null : status.trim(),
                hasMapping);
        long total = repo.countItems(filter);
        List<Item> items = total == 0
                ? List.of()
                : repo.listItems(filter, pageSize, properties.offset(pageNo, pageSize));
        return PageResult.of(pageNo, pageSize, total, items);
    }

    public ItemDetail itemDetail(String domainCode, String localCode) {
        Item item = requireItem(domainCode, localCode);
        return new ItemDetail(item, repo.listMappings(domainCode, localCode),
                repo.listItemVersions(item.id(), DETAIL_VERSION_LIMIT, 0));
    }

    public PageResult<ItemVersion> listVersions(String domainCode, String localCode,
                                                Integer page, Integer size) {
        Item item = requireItem(domainCode, localCode);
        int pageNo = properties.page(page);
        int pageSize = Math.min(properties.pageSize(size), properties.getMaxVersionPageSize());
        long total = repo.countItemVersions(item.id());
        List<ItemVersion> versions = total == 0 ? List.of()
                : repo.listItemVersions(item.id(), pageSize, properties.offset(pageNo, pageSize));
        return PageResult.of(pageNo, pageSize, total, versions);
    }

    /**
     * 版本链校验：逐条重算 checksum，并检查 prev_checksum 与版本号连续性。
     *
     * <p>能发现：快照被改、校验和被改、中间版本被删、版本被重排。
     * 不能发现：整条链被一起重写（需要外部锚定，如每日把链尾哈希写入独立审计/WORM）。
     */
    public VersionChainResult verifyChain(String domainCode, String localCode, String actor,
                                          String clientIp, String traceId) {
        requireActor(actor);
        Item item = requireItem(domainCode, localCode);
        List<ItemVersion> versions = repo.allItemVersionsAsc(item.id());
        if (versions.isEmpty()) {
            VersionChainResult result = new VersionChainResult(domainCode, localCode, 0, false, null,
                    "该条目没有任何版本记录，无法证明可追溯");
            audit(actor, "VERSION_VERIFY", domainCode, localCode, null,
                    Map.of("ok", false, "checked", 0), clientIp, traceId);
            return result;
        }
        String expectedPrev = null;
        long expectedNo = 1;
        VersionChainResult broken = null;
        for (ItemVersion version : versions) {
            if (version.versionNo() != expectedNo) {
                broken = new VersionChainResult(domainCode, localCode, expectedNo - 1, false,
                        version.versionNo(), "版本号不连续：期望 " + expectedNo + "，实际 "
                        + version.versionNo());
                break;
            }
            if (!Objects.equals(expectedPrev, version.prevChecksum())) {
                broken = new VersionChainResult(domainCode, localCode, expectedNo - 1, false,
                        version.versionNo(), "版本链断开：prev_checksum 与上一条不一致");
                break;
            }
            String recomputed = CanonicalJson.checksum(version.prevChecksum(), version.snapshot());
            if (!recomputed.equals(version.checksum())) {
                broken = new VersionChainResult(domainCode, localCode, expectedNo - 1, false,
                        version.versionNo(), "版本内容校验失败：快照或校验和被改动");
                break;
            }
            expectedPrev = version.checksum();
            expectedNo++;
        }
        if (broken != null) {
            audit(actor, "VERSION_VERIFY", domainCode, localCode, null,
                    Map.of("ok", false, "checked", broken.checked(), "brokenAt", broken.brokenAtVersion()),
                    clientIp, traceId);
            return broken;
        }
        VersionChainResult result = new VersionChainResult(domainCode, localCode, versions.size(), true,
                null, "版本链完整");
        audit(actor, "VERSION_VERIFY", domainCode, localCode, null,
                Map.of("ok", true, "checked", versions.size()), clientIp, traceId);
        return result;
    }

    /** 外部编码反查：返回全部候选（多义时 ambiguous=true）。 */
    public MappingLookup resolveMapping(String systemCode, String externalCode, String actor,
                                        String clientIp, String traceId) {
        requireActor(actor);
        requireText(systemCode, "外部系统编码", 64);
        requireText(externalCode, "外部编码", 128);
        requireActiveSystem(systemCode);
        List<ExternalMap> candidates = repo.findMappingsByExternal(systemCode.trim(), externalCode.trim());
        long distinctLocal = candidates.stream().map(ExternalMap::localCode).distinct().count();
        boolean ambiguous = distinctLocal > 1;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("systemCode", systemCode.trim());
        detail.put("externalCode", externalCode.trim());
        detail.put("candidates", candidates.size());
        detail.put("ambiguous", ambiguous);
        audit(actor, "MAPPING_RESOLVE", null, null, null, detail, clientIp, traceId);
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.MDM_MAPPING_NOT_FOUND,
                    "未找到 " + systemCode.trim() + " 外部编码 " + externalCode.trim() + " 的本地映射");
        }
        return new MappingLookup(systemCode.trim(), externalCode.trim(), candidates, ambiguous);
    }

    public List<ChangeRequest> listChangeRequests(String status, String domainCode, String changeType,
                                                  Integer limit) {
        if (status != null && !status.isBlank() && !REQUEST_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "变更申请状态不合法");
        }
        if (changeType != null && !changeType.isBlank() && !CHANGE_TYPES.contains(changeType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "变更类型不合法");
        }
        if (domainCode != null && !domainCode.isBlank()) {
            requireDomain(domainCode);
        }
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return repo.listChangeRequests(status, domainCode, changeType, size);
    }

    public ChangeRequest changeRequest(UUID id) {
        return requireRequest(id);
    }

    public List<AuditLog> listAudit(String domainCode, String actor, String action, Integer limit) {
        if (action != null && !action.isBlank() && !AUDIT_ACTIONS.contains(action)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审计动作不合法");
        }
        return repo.listAudit(domainCode, actor, action, properties.auditLimit(limit));
    }

    // ------------------------------------------------------------------ 变更申请

    /**
     * 提交变更申请。
     *
     * @param fields 业务字段（按变更类型解释）；内部键以下划线开头，由服务端补齐
     */
    @Transactional
    public ChangeRequest submit(String changeType, String domainCode, String localCode,
                                Map<String, Object> fields, String reason, String actor,
                                String clientIp, String traceId) {
        requireActor(actor);
        requireReason(reason);
        if (!CHANGE_TYPES.contains(changeType)) {
            throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID, "变更类型不合法");
        }
        requireDomain(domainCode);
        String code = requireCode(localCode);
        Map<String, Object> input = fields == null ? Map.of() : fields;

        if (isItemLevel(changeType)) {
            repo.findOpenItemRequest(domainCode, code).ifPresent(open -> {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该条目已有待审批申请 " + open.requestNo() + "，请先处理后再提交");
            });
        }

        Map<String, Object> payload = switch (changeType) {
            case ChangeRequest.ITEM_CREATE -> itemCreatePayload(domainCode, code, input);
            case ChangeRequest.ITEM_UPDATE -> itemUpdatePayload(domainCode, code, input);
            case ChangeRequest.ITEM_STATUS -> itemStatusPayload(domainCode, code, input);
            case ChangeRequest.MAPPING_UPSERT -> mappingUpsertPayload(domainCode, code, input);
            case ChangeRequest.MAPPING_DELETE -> mappingDeletePayload(domainCode, code, input);
            default -> throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID, "变更类型不合法");
        };

        Instant now = Instant.now();
        ChangeRequest request = new ChangeRequest(UUID.randomUUID(), requestNo(), changeType,
                domainCode, code, payload, reason.trim(), ChangeRequest.SUBMITTED, actor, now,
                null, null, null, null, 0, now, now);
        repo.insertChangeRequest(request);
        audit(actor, "REQUEST_SUBMIT", domainCode, code, request.requestNo(),
                Map.of("changeType", changeType), clientIp, traceId);
        return request;
    }

    /** 审批通过并应用变更（提交人不能审批自己的申请）。 */
    @Transactional
    public ChangeRequest approve(UUID id, String comment, String actor, String clientIp, String traceId) {
        requireActor(actor);
        ChangeRequest request = requireRequest(id);
        requireStatus(request, ChangeRequest.SUBMITTED);
        if (actor.equals(request.submittedBy())) {
            throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_SELF_REVIEW);
        }
        long appliedVersion = apply(request, actor);
        if (!repo.markApplied(id, actor, comment, appliedVersion, request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该变更申请已被他人处理，请刷新后查看");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changeType", request.changeType());
        detail.put("appliedVersion", appliedVersion);
        audit(actor, "REQUEST_APPROVE", request.domainCode(), request.localCode(),
                request.requestNo(), detail, clientIp, traceId);
        return requireRequest(id);
    }

    /** 驳回：必须写原因，且不能由提交人自己驳回（提交人应撤回）。 */
    @Transactional
    public ChangeRequest reject(UUID id, String comment, String actor, String clientIp, String traceId) {
        requireActor(actor);
        requireReason(comment);
        ChangeRequest request = requireRequest(id);
        requireStatus(request, ChangeRequest.SUBMITTED);
        if (actor.equals(request.submittedBy())) {
            throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_SELF_REVIEW,
                    "不能驳回自己提交的申请，如需终止请撤回");
        }
        if (!repo.markRejected(id, actor, comment.trim(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该变更申请已被他人处理，请刷新后查看");
        }
        audit(actor, "REQUEST_REJECT", request.domainCode(), request.localCode(),
                request.requestNo(), Map.of("changeType", request.changeType()), clientIp, traceId);
        return requireRequest(id);
    }

    /** 撤回：只有提交人本人可以撤回待审批的申请。 */
    @Transactional
    public ChangeRequest cancel(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        ChangeRequest request = requireRequest(id);
        requireStatus(request, ChangeRequest.SUBMITTED);
        if (!actor.equals(request.submittedBy())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有提交人可以撤回该变更申请");
        }
        if (!repo.markCancelled(id, actor, request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该变更申请已被他人处理，请刷新后查看");
        }
        audit(actor, "REQUEST_CANCEL", request.domainCode(), request.localCode(),
                request.requestNo(), Map.of("changeType", request.changeType()), clientIp, traceId);
        return requireRequest(id);
    }

    private long apply(ChangeRequest request, String actor) {
        requireDomain(request.domainCode());
        Map<String, Object> payload = request.payload();
        long baseVersion = longValue(payload.get(BASE_VERSION), 0);
        String domain = request.domainCode();
        String code = request.localCode();
        Item current = repo.findItem(domain, code).orElse(null);

        return switch (request.changeType()) {
            case ChangeRequest.ITEM_CREATE -> {
                if (current != null) {
                    throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                            "审批时该编码已被他人创建，请驳回后重新核对");
                }
                Item created = writer.create(domain, code, text(payload, "name"), text(payload, "alias"),
                        text(payload, "category"), text(payload, "spec"), text(payload, "unit"),
                        status(payload), Item.SOURCE_MANUAL, null, date(payload, "effectiveFrom"),
                        date(payload, "effectiveTo"), attributes(payload.get("attributes")),
                        "CREATE", MasterDataWriter.Ref.CHANGE_REQUEST, request.requestNo(),
                        request.reason(), actor);
                yield created.version() + 1;
            }
            case ChangeRequest.ITEM_UPDATE -> {
                Item item = requireItemForApproval(current, domain, code);
                if (item.version() != baseVersion) {
                    throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                            "条目在提交后已被修改，请驳回后重新提交申请");
                }
                yield writer.update(item, text(payload, "name"), text(payload, "alias"),
                        text(payload, "category"), text(payload, "spec"), text(payload, "unit"),
                        item.status(), item.sourceRef(), date(payload, "effectiveFrom"),
                        date(payload, "effectiveTo"), attributes(payload.get("attributes")),
                        "UPDATE", MasterDataWriter.Ref.CHANGE_REQUEST, request.requestNo(),
                        request.reason(), actor).version();
            }
            case ChangeRequest.ITEM_STATUS -> {
                Item item = requireItemForApproval(current, domain, code);
                if (item.version() != baseVersion) {
                    throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                            "条目在提交后已被修改，请驳回后重新提交申请");
                }
                String next = status(payload);
                if (next.equals(item.status())) {
                    throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                            "条目状态已是 " + next + "，无需变更");
                }
                String changeType = Item.ACTIVE.equals(next) ? "ENABLE" : "DISABLE";
                yield writer.changeStatus(item, next, changeType, request.reason(),
                        MasterDataWriter.Ref.CHANGE_REQUEST, request.requestNo(), actor).version();
            }
            case ChangeRequest.MAPPING_UPSERT -> {
                requireActiveSystem(text(payload, "systemCode"));
                ExternalMap existing = repo.findMappingByKey(domain, code,
                        text(payload, "systemCode"), text(payload, "externalCode")).orElse(null);
                long currentVersion = existing == null ? 0 : existing.version();
                if (currentVersion != baseVersion) {
                    throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                            "该映射在提交后已被修改，请驳回后重新提交申请");
                }
                ExternalMap saved = writer.upsertMapping(domain, code, text(payload, "systemCode"),
                        text(payload, "externalCode"), text(payload, "externalName"),
                        mapType(payload), bool(payload, "primary"), mappingStatus(payload),
                        date(payload, "effectiveFrom"), date(payload, "effectiveTo"),
                        text(payload, "remark"), MasterDataWriter.Ref.CHANGE_REQUEST,
                        request.requestNo(), request.reason(), actor);
                yield saved.version();
            }
            case ChangeRequest.MAPPING_DELETE -> {
                ExternalMap existing = repo.findMappingByKey(domain, code,
                        text(payload, "systemCode"), text(payload, "externalCode"))
                        .orElseThrow(() -> new BusinessException(ErrorCode.MDM_MAPPING_NOT_FOUND));
                if (!ExternalMap.ACTIVE.equals(existing.status())) {
                    throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                            "该映射已停用，无需再次停用");
                }
                if (existing.version() != baseVersion) {
                    throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                            "该映射在提交后已被修改，请驳回后重新提交申请");
                }
                yield writer.retireMapping(existing, request.reason(),
                        MasterDataWriter.Ref.CHANGE_REQUEST, request.requestNo(), actor).version();
            }
            default -> throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID, "变更类型不合法");
        };
    }

    private Item requireItemForApproval(Item current, String domain, String code) {
        if (current == null) {
            throw new BusinessException(ErrorCode.MDM_ITEM_NOT_FOUND);
        }
        if (Item.SOURCE_IN_HOUSE.equals(current.sourceSystem())) {
            throw new BusinessException(ErrorCode.MDM_ITEM_STATUS_INVALID,
                    "该条目由院内源字典同步维护，请改源表后重新同步，不能手工改");
        }
        return current;
    }

    // ---- 申请载荷（提交时固化"申请人看到的内容"，审批时不再重新解释） ----

    private Map<String, Object> itemCreatePayload(String domain, String code, Map<String, Object> f) {
        if (repo.findItem(domain, code).isPresent()) {
            throw new BusinessException(ErrorCode.MDM_ITEM_DUPLICATE,
                    "该域下已存在本地编码 " + code + "，如需修改请提交更新申请");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", requireText(f.get("name"), "名称", 200));
        payload.put("alias", optionalText(f.get("alias"), 64));
        payload.put("category", optionalText(f.get("category"), 64));
        payload.put("spec", optionalText(f.get("spec"), 200));
        payload.put("unit", optionalText(f.get("unit"), 32));
        payload.put("status", optionalEnum(f.get("status"), ITEM_STATUSES, Item.ACTIVE));
        String from = optionalDate(f.get("effectiveFrom"));
        String to = optionalDate(f.get("effectiveTo"));
        checkDateOrder(from, to);
        payload.put("effectiveFrom", from);
        payload.put("effectiveTo", to);
        payload.put("attributes", attributes(f.get("attributes")));
        return payload;
    }

    private Map<String, Object> itemUpdatePayload(String domain, String code, Map<String, Object> f) {
        Item current = requireItem(domain, code);
        requireManualOrImport(current);

        String name = f.containsKey("name")
                ? requireText(f.get("name"), "名称", 200) : current.name();
        String alias = f.containsKey("alias") ? optionalText(f.get("alias"), 64) : current.alias();
        String category = f.containsKey("category")
                ? optionalText(f.get("category"), 64) : current.category();
        String spec = f.containsKey("spec") ? optionalText(f.get("spec"), 200) : current.spec();
        String unit = f.containsKey("unit") ? optionalText(f.get("unit"), 32) : current.unit();
        String from = f.containsKey("effectiveFrom")
                ? optionalDate(f.get("effectiveFrom")) : iso(current.effectiveFrom());
        String to = f.containsKey("effectiveTo")
                ? optionalDate(f.get("effectiveTo")) : iso(current.effectiveTo());
        checkDateOrder(from, to);
        Map<String, Object> attrs = f.containsKey("attributes")
                ? attributes(f.get("attributes")) : current.attributes();

        String fingerprint = Snapshots.fingerprint(name, alias, category, spec, unit,
                current.status(), parseDate(from), parseDate(to), attrs);
        if (fingerprint.equals(current.fingerprint())) {
            throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                    "填写的目标内容与当前条目一致，没有可提交的变更");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("alias", alias);
        payload.put("category", category);
        payload.put("spec", spec);
        payload.put("unit", unit);
        payload.put("effectiveFrom", from);
        payload.put("effectiveTo", to);
        payload.put("attributes", attrs);
        payload.put(BASE_VERSION, current.version());
        return payload;
    }

    private Map<String, Object> itemStatusPayload(String domain, String code, Map<String, Object> f) {
        Item current = requireItem(domain, code);
        requireManualOrImport(current);
        if (!f.containsKey("status")) {
            throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID, "缺少目标状态 status");
        }
        String status = optionalEnum(f.get("status"), ITEM_STATUSES, null);
        if (status == null) {
            throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                    "目标状态只能是 ACTIVE 或 INACTIVE");
        }
        if (status.equals(current.status())) {
            throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                    "条目状态已是 " + status + "，无需变更");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put(BASE_VERSION, current.version());
        return payload;
    }

    private Map<String, Object> mappingUpsertPayload(String domain, String code, Map<String, Object> f) {
        requireItem(domain, code);
        String systemCode = requireText(f.get("systemCode"), "外部系统编码", 64).trim();
        requireActiveSystem(systemCode);
        String externalCode = requireText(f.get("externalCode"), "外部编码", 128).trim();
        String externalName = optionalText(f.get("externalName"), 200);
        String mapType = optionalEnum(f.get("mapType"), MAP_TYPES, ExternalMap.EXACT);
        boolean primary = bool(f, "primary");
        String status = optionalEnum(f.get("status"),
                Set.of(ExternalMap.ACTIVE, ExternalMap.INACTIVE), ExternalMap.ACTIVE);
        String from = optionalDate(f.get("effectiveFrom"));
        String to = optionalDate(f.get("effectiveTo"));
        checkDateOrder(from, to);
        String remark = optionalText(f.get("remark"), 500);

        ExternalMap existing = repo.findMappingByKey(domain, code, systemCode, externalCode)
                .orElse(null);
        long baseVersion = existing == null ? 0 : existing.version();
        if (existing != null) {
            Map<String, Object> before = Snapshots.of(existing);
            Map<String, Object> after = new LinkedHashMap<>(before);
            after.put("externalName", externalName);
            after.put("mapType", mapType);
            after.put("primary", primary);
            after.put("status", status);
            after.put("effectiveFrom", from);
            after.put("effectiveTo", to);
            after.put("remark", remark);
            if (Snapshots.diff(before, after).isEmpty()) {
                throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID,
                        "填写的目标映射与当前映射一致，没有可提交的变更");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemCode", systemCode);
        payload.put("externalCode", externalCode);
        payload.put("externalName", externalName);
        payload.put("mapType", mapType);
        payload.put("primary", primary);
        payload.put("status", status);
        payload.put("effectiveFrom", from);
        payload.put("effectiveTo", to);
        payload.put("remark", remark);
        payload.put(BASE_VERSION, baseVersion);
        return payload;
    }

    private Map<String, Object> mappingDeletePayload(String domain, String code, Map<String, Object> f) {
        requireItem(domain, code);
        String systemCode = requireText(f.get("systemCode"), "外部系统编码", 64).trim();
        String externalCode = requireText(f.get("externalCode"), "外部编码", 128).trim();
        ExternalMap existing = repo.findMappingByKey(domain, code, systemCode, externalCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_MAPPING_NOT_FOUND));
        if (!ExternalMap.ACTIVE.equals(existing.status())) {
            throw new BusinessException(ErrorCode.MDM_MAPPING_NOT_FOUND, "该映射已停用");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemCode", systemCode);
        payload.put("externalCode", externalCode);
        payload.put(BASE_VERSION, existing.version());
        return payload;
    }

    // ------------------------------------------------------------------ 质量看板

    public QualityReport quality() {
        int staleDays = properties.getSyncStaleDays();
        Instant threshold = Instant.now().minus(Duration.ofDays(staleDays));
        List<DomainQuality> domains = repo.listDomainQuality().stream()
                .map(q -> new DomainQuality(q.domainCode(), q.name(), q.total(), q.active(),
                        q.inactive(), q.missingInSource(), q.mappedActive(), q.unmappedActive(),
                        q.missingRequiredMapping(), q.duplicateNameGroups(),
                        q.ambiguousExternalCodes(), q.lastSyncedAt(), q.lastSyncStatus(),
                        q.lastSyncedAt() == null || q.lastSyncedAt().isBefore(threshold),
                        q.requiredSystems()))
                .toList();

        List<QualityIssue> issues = new ArrayList<>();
        issues.addAll(repo.missingRequiredIssues(50));
        issues.addAll(repo.ambiguousExternalIssues(50));
        issues.addAll(repo.duplicateNameIssues(50));
        issues.addAll(repo.missingInSourceIssues(50));
        issues.addAll(repo.unmappedIssues(50));
        for (DomainQuality domain : domains) {
            if (domain.syncStale()) {
                issues.add(new QualityIssue(QualityIssue.SYNC_STALE, domain.domainCode(), null, null,
                        domain.lastSyncedAt() == null
                                ? "从未执行源表同步"
                                : "最近同步时间 " + domain.lastSyncedAt()
                                  + " 已超过 " + staleDays + " 天"));
            }
        }
        List<QualityIssue> sorted = issues.stream()
                .sorted(Comparator.comparing(QualityIssue::domainCode)
                        .thenComparing(QualityIssue::type)
                        .thenComparing(issue -> issue.localCode() == null ? "" : issue.localCode()))
                .limit(200)
                .toList();
        return new QualityReport(staleDays, domains, sorted, Instant.now());
    }

    // ------------------------------------------------------------------ 导出

    /** 映射导出 CSV（含 BOM；单次最多 2 万行，避免把出口当全库倾倒）。 */
    public String exportMappingsCsv(String domainCode, String systemCode) {
        if (domainCode != null && !domainCode.isBlank()) {
            requireDomain(domainCode);
        }
        if (systemCode != null && !systemCode.isBlank()) {
            requireSystem(systemCode);
        }
        List<ExternalMap> mappings = repo.exportMappings(domainCode, systemCode, MAX_EXPORT_ROWS);
        List<List<String>> rows = mappings.stream().map(map -> List.of(
                map.domainCode(), map.localCode(), nullToEmpty(map.localName()), map.systemCode(),
                map.externalCode(), nullToEmpty(map.externalName()), map.mapType(),
                map.primary() ? "1" : "0", map.status(),
                map.effectiveFrom() == null ? "" : map.effectiveFrom().toString(),
                map.effectiveTo() == null ? "" : map.effectiveTo().toString(),
                nullToEmpty(map.remark()))).toList();
        List<String> header = List.of("域编码", "本地编码", "本地名称", "系统编码", "外部编码",
                "外部名称", "映射类型", "主映射", "状态", "生效起", "生效止", "备注");
        return Csv.write(header, rows);
    }

    // ------------------------------------------------------------------ 工具

    private Domain requireDomain(String domainCode) {
        if (domainCode == null || domainCode.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少主数据域编码");
        }
        return repo.findDomain(domainCode.trim())
                .filter(Domain::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_DOMAIN_NOT_FOUND));
    }

    private Item requireItem(String domainCode, String localCode) {
        String code = requireCode(localCode);
        return repo.findItem(domainCode, code)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_ITEM_NOT_FOUND,
                        "未找到主数据条目 " + domainCode + "/" + code));
    }

    private ChangeRequest requireRequest(UUID id) {
        return repo.findChangeRequest(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_NOT_FOUND));
    }

    private void requireStatus(ChangeRequest request, String status) {
        if (!status.equals(request.status())) {
            throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_STATUS_INVALID,
                    "当前状态为 " + request.status() + "，不能执行该操作");
        }
    }

    private ExternalSystem requireActiveSystem(String systemCode) {
        ExternalSystem system = requireSystem(systemCode);
        if (!ExternalSystem.ACTIVE.equals(system.status())) {
            throw new BusinessException(ErrorCode.MDM_EXTERNAL_SYSTEM_NOT_FOUND,
                    "外部系统 " + systemCode + " 已停用，不能建立映射");
        }
        return system;
    }

    /** 只校验存在（导出历史映射时允许系统已停用）。 */
    private ExternalSystem requireSystem(String systemCode) {
        return repo.findExternalSystem(systemCode.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_EXTERNAL_SYSTEM_NOT_FOUND));
    }

    private void requireManualOrImport(Item item) {
        if (Item.SOURCE_IN_HOUSE.equals(item.sourceSystem())) {
            throw new BusinessException(ErrorCode.MDM_ITEM_STATUS_INVALID,
                    "该条目由院内源字典同步维护，请改源表后重新同步，不能手工修改");
        }
    }

    private static boolean isItemLevel(String changeType) {
        return ChangeRequest.ITEM_CREATE.equals(changeType)
                || ChangeRequest.ITEM_UPDATE.equals(changeType)
                || ChangeRequest.ITEM_STATUS.equals(changeType);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || "system".equals(actor)) {
            throw new BusinessException(ErrorCode.MDM_ACTOR_REQUIRED);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 2) {
            throw new BusinessException(ErrorCode.MDM_REASON_REQUIRED);
        }
        if (reason.trim().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "原因最多 500 个字符");
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private static LocalDate date(Map<String, Object> payload, String key) {
        return parseDate(text(payload, key));
    }

    private static String status(Map<String, Object> payload) {
        String value = text(payload, "status");
        return value == null ? Item.ACTIVE : value;
    }

    private static String mapType(Map<String, Object> payload) {
        String value = text(payload, "mapType");
        return value == null ? ExternalMap.EXACT : value;
    }

    private static String mappingStatus(Map<String, Object> payload) {
        String value = text(payload, "status");
        return value == null ? ExternalMap.ACTIVE : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String requestNo() {
        return "MDM-" + LocalDate.now().toString().replace("-", "") + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private void audit(String actor, String action, String domainCode, String localCode,
                       String refId, Map<String, Object> detail, String clientIp, String traceId) {
        repo.insertAudit(actor, action, domainCode, localCode, refId, detail, clientIp, traceId);
    }
}
