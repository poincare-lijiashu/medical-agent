package com.medassist.clinical.client;

import java.util.Map;

/**
 * Python AI 中台客户端（评测「HIS 深度集成」：HIS → AI 反向调用）。
 *
 * <p>为什么需要它：此前只有 AI 中台单向读取/回写 HIS；医生在 HIS 界面里写病历时
 * 无法触发 AI 质控/审方，必须另开 AI 前端——这就是"两套系统"的体感来源。
 *
 * <p>认证采用**令牌透传**：调用方把医护端 JWT 原样转给 AI 中台（两端共享同一 HS256
 * 身份源），不新增服务账号、不做权限放大。
 */
public interface AiAssistClient {

    /** 病历质控（对应 AI 中台 {@code POST /api/v1/medical/inline/qc-check}）。 */
    Map<String, Object> qcCheck(String bearerToken, Map<String, Object> payload);

    /** 医嘱审方（对应 AI 中台 {@code POST /api/v1/medical/inline/rx-check}）。 */
    Map<String, Object> rxCheck(String bearerToken, Map<String, Object> payload);
}
