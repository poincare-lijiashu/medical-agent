package com.medassist.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码契约（G78）。
 *
 * <p>错误码是对外契约：客户端按码分支，改号/撞号都是可观测的破坏性变更。
 * 这里把三条底线做成构建期门禁，而不是靠评审时的目测：
 * <ol>
 *   <li>号唯一——新增码撞上既有号会立刻失败（此前 material 与 pharmacy 的
 *       43xxx 段交错，靠人工记住哪些号用过）；</li>
 *   <li>号段合法——{@code OK=0}，其余落在 4xxxx/5xxxx；</li>
 *   <li>文案是给医护看的——非空，且不透出英文异常类型。</li>
 * </ol>
 *
 * <p>注意：这里不校验"码 → HTTP 状态"，因为那由各服务的
 * {@code GlobalExceptionHandler#statusOf} 决定（同一码在不同服务可以是不同状态）。
 */
class ErrorCodeTest {

    @Test
    @DisplayName("错误码唯一：新增码不会与既有码撞号")
    void codesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(seen.add(code.code()))
                    .as("重复错误码：%s -> %d", code.name(), code.code())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("号段合法：OK=0，其余为 4xxxx（客户端错误）或 5xxxx（服务端错误）")
    void codesFallInReservedRanges() {
        assertThat(ErrorCode.OK.code()).isZero();
        for (ErrorCode code : ErrorCode.values()) {
            if (code == ErrorCode.OK) {
                continue;
            }
            assertThat(code.code())
                    .as("%s 的编码 %d 不在 4xxxx–5xxxx", code.name(), code.code())
                    .isBetween(40000, 59999);
        }
    }

    @Test
    @DisplayName("文案面向医护：非空，且不透出英文异常类型")
    void messagesAreUserFacing() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.defaultMessage()).as(code.name()).isNotBlank();
            assertThat(code.defaultMessage()).as(code.name())
                    .doesNotContain("Exception")
                    .doesNotContain("java.");
        }
    }
}
