package com.medassist.common.security;

/**
 * 敏感字段掩码（P1-S4）。
 *
 * <p>为什么单独抽出来：权限层的 {@link DataScope} 只解决"响应体要不要脱敏"，
 * 但**审计明细**是另一条出口——它落库且长期留存，此前多处把患者号/姓名原样写进
 * {@code detail}，等于在审计表里复制了一份 PHI。审计只需要能定位对象（target_id 已是
 * 业务主键），不需要明文身份信息，因此统一走这里。
 *
 * <p>算法与 {@link DataScope} 的响应脱敏保持一致，避免"响应里是 {@code 123****89}、
 * 审计里是原文"的口径分裂。
 */
public final class Masks {

    private Masks() {
    }

    /**
     * 患者号掩码：保留前 3 后 2，中间用 {@code *} 补足，<b>输出长度恒等于输入长度</b>。
     *
     * <p>G51 修正两处：① 原实现中间位数取 {@code max(2, len-5)}，长度 6 的编号会输出
     * 7 个字符（{@code 123456 → 123**56}），掩码后比原文更长，下游按长度校验或做等值
     * 比对时出现莫名不一致；② 阈值由 6 提到 8，长度 <8 一律全掩——6~7 位编号保留
     * 前 3 后 2 等于只遮 1~2 位，穷举成本极低。
     */
    public static String patientNo(String patientNo) {
        if (patientNo == null || patientNo.isBlank()) {
            return patientNo;
        }
        String value = patientNo.trim();
        if (value.length() < 8) {
            return "*".repeat(value.length());
        }
        int masked = value.length() - 5;
        return value.substring(0, 3) + "*".repeat(masked) + value.substring(value.length() - 2);
    }

    /** 姓名掩码：只留首字，其余用 {@code *}。单字姓名整体掩码。 */
    public static String patientName(String patientName) {
        if (patientName == null || patientName.isBlank()) {
            return patientName;
        }
        String value = patientName.trim();
        if (value.length() <= 1) {
            return "*";
        }
        return value.charAt(0) + "*".repeat(value.length() - 1);
    }
}
