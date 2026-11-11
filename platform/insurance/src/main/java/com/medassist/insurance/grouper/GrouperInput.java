package com.medassist.insurance.grouper;

import java.util.List;

/**
 * 分组输入（已归一化）：主诊断 + 次要诊断 + 手术/操作。
 *
 * <p>主诊断必须存在：没有主诊断的"分组结果"只会制造假结论。
 */
public record GrouperInput(String principalDiagnosis, List<String> secondaryDiagnoses,
                           List<String> procedures) {
}
