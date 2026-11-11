package com.medassist.masterdata.support;

import com.medassist.masterdata.domain.MasterDataModel.ExternalMap;
import com.medassist.masterdata.domain.MasterDataModel.Item;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 主数据快照与差异。
 *
 * <p>快照只放<b>可比较的稳定内容</b>（编码/名称/分类/规格/状态/属性），
 * 不放 {@code created_at}/{@code updated_at} 这类时间戳——否则同步每次都"看起来变了"，
 * 版本链会被无意义的时间噪声塞满。
 */
public final class Snapshots {

    private Snapshots() {
    }

    /** 条目完整快照（写入版本表）。 */
    public static Map<String, Object> of(Item item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("domainCode", item.domainCode());
        snapshot.put("localCode", item.localCode());
        snapshot.put("name", item.name());
        snapshot.put("alias", item.alias());
        snapshot.put("category", item.category());
        snapshot.put("spec", item.spec());
        snapshot.put("unit", item.unit());
        snapshot.put("status", item.status());
        snapshot.put("sourceSystem", item.sourceSystem());
        snapshot.put("sourceRef", item.sourceRef());
        snapshot.put("effectiveFrom", item.effectiveFrom() == null ? null : item.effectiveFrom().toString());
        snapshot.put("effectiveTo", item.effectiveTo() == null ? null : item.effectiveTo().toString());
        snapshot.put("attributes", item.attributes());
        snapshot.put("version", item.version());
        return snapshot;
    }

    /** 映射完整快照（写入映射版本表）。 */
    public static Map<String, Object> of(ExternalMap map) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("domainCode", map.domainCode());
        snapshot.put("localCode", map.localCode());
        snapshot.put("systemCode", map.systemCode());
        snapshot.put("externalCode", map.externalCode());
        snapshot.put("externalName", map.externalName());
        snapshot.put("mapType", map.mapType());
        snapshot.put("primary", map.primary());
        snapshot.put("status", map.status());
        snapshot.put("effectiveFrom", map.effectiveFrom() == null ? null : map.effectiveFrom().toString());
        snapshot.put("effectiveTo", map.effectiveTo() == null ? null : map.effectiveTo().toString());
        snapshot.put("remark", map.remark());
        snapshot.put("version", map.version());
        return snapshot;
    }

    /**
     * 内容指纹：只覆盖"源表同步需要感知"的字段。
     *
     * <p>刻意不包含 {@code sourceRef}：源表主键变化（例如重建字典）不应触发业务内容版本；
     * 包含 {@code status}：启用→停用必须触发一次版本，否则同步永远发现不了变化。
     */
    public static String fingerprint(String name, String alias, String category, String spec,
                                     String unit, String status, LocalDate from, LocalDate to,
                                     Map<String, Object> attributes) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", name);
        content.put("alias", alias);
        content.put("category", category);
        content.put("spec", spec);
        content.put("unit", unit);
        content.put("status", status);
        content.put("effectiveFrom", from == null ? null : from.toString());
        content.put("effectiveTo", to == null ? null : to.toString());
        content.put("attributes", attributes == null ? Map.of() : attributes);
        return CanonicalJson.checksum("", content);
    }

    /** 计算字段级差异：{字段: {before, after}}；无差异返回空 Map。 */
    public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>();
        if (before != null) {
            keys.addAll(before.keySet());
        }
        if (after != null) {
            keys.addAll(after.keySet());
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        for (String key : keys) {
            Object oldValue = before == null ? null : before.get(key);
            Object newValue = after == null ? null : after.get(key);
            if (!CanonicalJson.canonical(oldValue).equals(CanonicalJson.canonical(newValue))) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", oldValue);
                change.put("after", newValue);
                diff.put(key, change);
            }
        }
        return diff;
    }
}
