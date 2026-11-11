package com.medassist.migration.service;

import java.util.Map;
import java.util.Set;

/**
 * 归一化后的一行。
 *
 * @param values        规范字符串值（字段 → 值）；仅包含源文件提供或映射规则产出的字段
 * @param createValues  新建时使用的值 = values + 字段默认值
 * @param provided      源文件/映射实际提供的字段（更新时只改这些字段，避免把未提供的列清空）
 * @param rawProjection 脱敏后的原始行（落 mig_row.raw，供人工核对）
 * @param normalizedProjection 脱敏后的归一化行（落 mig_row.normalized）
 */
public record NormalizedRow(
        Map<String, String> values,
        Map<String, String> createValues,
        Set<String> provided,
        Map<String, Object> rawProjection,
        Map<String, Object> normalizedProjection) {
}
