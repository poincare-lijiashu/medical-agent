package com.medassist.auth.web.dto;

/**
 * 审计动作字典项（第 243 批）。
 *
 * <p>`count` 是所选时间窗内该动作出现的次数：用来把高频动作排在前面，
 * 也让人一眼看出“这个动作最近到底有没有在写”。中文名由前端按口径翻译，
 * 服务端只回原始 action（审计是证据，译文不进库）。
 */
public record AuditActionOption(String action, long count) {
}
