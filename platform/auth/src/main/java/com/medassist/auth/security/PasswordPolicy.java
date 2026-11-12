package com.medassist.auth.security;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;

/**
 * 口令策略（第 285 批集中化）。
 *
 * <p>此前策略散落在 {@code UserService} 的私有方法里：新开户、管理员重置各走一遍，
 * 自助改密如果另写一份，早晚出现"某条入口能设出弱口令"。这里收敛成唯一实现，
 * <b>所有写入口令的路径都必须过它</b>。
 *
 * <p>规则（与计划书 D12 验收矩阵一致）：
 * <ul>
 *   <li>长度 8–64；</li>
 *   <li>必须同时包含字母与数字；</li>
 *   <li>不得与用户名相同，也不得包含用户名（用户名长度 ≥ 3 时判断，避免 2 字符用户名误伤）；</li>
 *   <li>不得是高频弱口令（Password1、admin123、hospital1 等医院现场常见默认口令）；</li>
 *   <li>不得是单一字符重复。</li>
 * </ul>
 *
 * <p>策略失败统一返回 {@link ErrorCode#VALIDATION_FAILED}（422）：这是表单校验，
 * 不是认证失败——401/403 会触发前端重新登录或跳转，把用户从改密对话框踢出去。
 */
@Component
public class PasswordPolicy {

    /** 与 DTO 的 {@code @Size(min/max)} 保持一致，避免两层规则各说各话。 */
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    /** 用户名参与"包含判断"的最小长度：短用户名（如 a1）会让绝大多数口令误报。 */
    private static final int USERNAME_CHECK_MIN_LENGTH = 3;

    /**
     * 常见弱口令（小写）。只收真正高频、且满足"8 位 + 字母数字"因而能绕过组合规则的：
     * 长度/字符类型规则拦不住 {@code Password1} 这类现场默认口令。
     */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "12345678", "123456789", "1234567890", "password", "password1", "passw0rd1",
            "abc12345", "a1234567", "qwerty12", "admin123", "administrator", "11111111",
            "88888888", "00000000", "hospital1", "doctor123", "nurse123", "medassist1",
            "medassist123", "hospital123", "welcome1", "changeme1");

    /** 临时口令字母表：去掉 0/O/1/I/l 等易混字符，便于电话/纸质交付时口述。 */
    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private static final int TEMP_PASSWORD_LENGTH = 20;

    private final SecureRandom random = new SecureRandom();

    /**
     * 校验口令是否符合策略。
     *
     * @param password 明文口令
     * @param username 账号名（用于"不得包含用户名"；为空时跳过该条）
     * @throws BusinessException 422，消息可直接展示给用户
     */
    public void validate(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "密码长度不能少于 " + MIN_LENGTH + " 位");
        }
        if (password.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "密码长度不能超过 " + MAX_LENGTH + " 位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码必须同时包含字母与数字");
        }

        String normalizedUser = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!normalizedUser.isEmpty() && password.equalsIgnoreCase(normalizedUser)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码不能与用户名相同");
        }
        if (normalizedUser.length() >= USERNAME_CHECK_MIN_LENGTH
                && password.toLowerCase(Locale.ROOT).contains(normalizedUser)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码不能包含用户名");
        }

        if (WEAK_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "密码过于常见（如 Password1、admin123），请更换为不易猜测的组合");
        }
        if (password.chars().distinct().count() == 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码不能是单一字符的重复");
        }
    }

    /**
     * 生成一次性临时口令。
     *
     * <p>固定以 {@code Aa1} 开头，确保满足字母 + 数字 + 长度规则，再补随机字符；
     * 不使用易混字符，方便交付时口述或抄写。
     */
    public String generateTemporary() {
        StringBuilder builder = new StringBuilder("Aa1");
        for (int i = builder.length(); i < TEMP_PASSWORD_LENGTH; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
