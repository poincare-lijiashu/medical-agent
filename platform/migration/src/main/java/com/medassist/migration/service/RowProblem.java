package com.medassist.migration.service;

/**
 * 单行问题（预检阶段即可读地告诉实施人员"第几行、哪个字段、为什么"）。
 *
 * <p>继承 RuntimeException 但不带堆栈：逐行校验失败是正常业务结果，
 * 每行都抓一次堆栈会让上万行预检变得又慢又吵。
 */
public class RowProblem extends RuntimeException {

    public RowProblem(String message) {
        super(message, null, false, false);
    }
}
