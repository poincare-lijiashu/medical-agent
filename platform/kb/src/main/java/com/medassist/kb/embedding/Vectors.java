package com.medassist.kb.embedding;

import java.util.Locale;

/** 向量与 SQL 数组字面量互转（{@code {0.1,0.2}} 形式，避免 JDBC 数组绑定方言问题）。 */
public final class Vectors {

    private Vectors() {
    }

    /** float[] → PostgreSQL real[] 字面量；null 返回 null（写入 SQL 的 NULL）。 */
    public static String literal(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 10).append('{');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        return sb.append('}').toString();
    }
}
