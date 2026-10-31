package com.medassist.tech.hl7;

import java.util.ArrayList;
import java.util.List;

/**
 * HL7 v2 ORU^R01 最小解析器（离网自实现，不引入 HAPI 等第三方依赖）。
 *
 * <p>只解析本平台需要的字段：
 * <ul>
 *   <li>MSH-3 发送应用、MSH-9 消息类型、MSH-10 消息控制 ID（幂等键）；</li>
 *   <li>OBR-2 / ORC-2 申请号（回写目标）；</li>
 *   <li>OBX 段：OBX-3 指标编码^名称、OBX-5 结果值、OBX-6 单位、OBX-7 参考范围、OBX-8 异常标志。</li>
 * </ul>
 *
 * <p>转义：HL7 用 \F\ \S\ \R\ \E\ \T\ 表示 | ^ ~ \ &，解析时必须还原，
 * 否则含这些字符的指标名会把字段切错。
 *
 * <p>分隔符按 MSH-1/MSH-2 读取（标准允许自定义），不写死。
 */
public final class HL7OruParser {

    /** 单条消息最大字符数：正常 ORU 报文远小于此；防止超长报文打爆内存。 */
    public static final int MAX_MESSAGE_LENGTH = 512 * 1024;

    private HL7OruParser() {
    }

    /** 一个 OBX 观察结果。 */
    public record Observation(String itemCode, String itemName, String value, String unit,
                              String referenceRange, String abnormalFlag) {

        public boolean abnormal() {
            return abnormalFlag != null && !abnormalFlag.isBlank()
                    && !"N".equalsIgnoreCase(abnormalFlag);
        }

        /** HH/LL 视为真危急值（H/L 仅普通异常）。 */
        public boolean critical() {
            return "HH".equalsIgnoreCase(abnormalFlag) || "LL".equalsIgnoreCase(abnormalFlag);
        }
    }

    /** 解析结果：{@code errors} 非空表示消息不可用。 */
    public record Oru(String messageType, String sendingApp, String controlId,
                      String applicationNo, List<Observation> observations, List<String> errors) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Oru parse(String raw) {
        List<String> errors = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return new Oru("", "", "", "", List.of(), List.of("消息为空"));
        }
        if (raw.length() > MAX_MESSAGE_LENGTH) {
            return new Oru("", "", "", "", List.of(),
                    List.of("消息长度超过上限 " + MAX_MESSAGE_LENGTH + " 字符"));
        }
        // 行分隔：标准用 \r，实际也常见 \n 或 \r\n
        String[] segments = raw.replace("\r\n", "\r").replace('\n', '\r').split("\\r");

        String fieldSep = "|";
        String componentSep = "^";
        String escapeChar = "\\";
        String repSep = "~";
        String subSep = "&";
        String messageType = "";
        String sendingApp = "";
        String controlId = "";
        String applicationNo = "";
        List<Observation> observations = new ArrayList<>();

        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (segment.startsWith("MSH")) {
                if (segment.length() < 8) {
                    return new Oru("", "", "", "", List.of(), List.of("MSH 段过短，无法读取分隔符"));
                }
                fieldSep = String.valueOf(segment.charAt(3));
                componentSep = String.valueOf(segment.charAt(4));
                repSep = String.valueOf(segment.charAt(5));
                escapeChar = String.valueOf(segment.charAt(6));
                subSep = String.valueOf(segment.charAt(7));
                String[] msh = segment.split(java.util.regex.Pattern.quote(fieldSep), -1);
                // MSH-1 就是字段分隔符本身，因此下标 = 字段号 - 1
                sendingApp = component(msh, 3, componentSep, true);
                // MSH-9 = 消息类型^触发事件（ORU^R01），保留整体便于日志与报错
                messageType = value(msh, 9, true);
                controlId = value(msh, 10, true);
                if (messageType == null) {
                    messageType = "";
                }
                if (sendingApp == null) {
                    sendingApp = "";
                }
                if (controlId == null) {
                    controlId = "";
                }
            }
        }
        if (messageType == null || !messageType.toUpperCase().startsWith("ORU")) {
            errors.add("不支持的消息类型："
                    + (messageType == null || messageType.isBlank() ? "（MSH-9 缺失）" : messageType)
                    + "，本接口只接收 ORU^R01");
        }
        if (controlId == null || controlId.isBlank()) {
            errors.add("MSH-10 消息控制 ID 缺失（幂等键不能为空）");
        }

        for (String segment : segments) {
            if (segment == null) {
                continue;
            }
            String[] fields = segment.split(java.util.regex.Pattern.quote(fieldSep), -1);
            if (segment.startsWith("OBR") && applicationNo.isBlank()) {
                applicationNo = value(fields, 2, false);
            } else if (segment.startsWith("ORC") && applicationNo.isBlank()) {
                applicationNo = value(fields, 2, false);
            } else if (segment.startsWith("OBX")) {
                String item = value(fields, 3, false);
                String code = component(fields, 3, componentSep, false);
                String name = componentIndex(fields, 3, componentSep, 1, false);
                String observationValue = value(fields, 5, false);
                String unit = component(fields, 6, componentSep, false);
                String reference = value(fields, 7, false);
                String flag = value(fields, 8, false);
                observations.add(new Observation(
                        unescape(code, escapeChar, fieldSep, componentSep, repSep, subSep),
                        unescape(name, escapeChar, fieldSep, componentSep, repSep, subSep),
                        unescape(observationValue, escapeChar, fieldSep, componentSep, repSep, subSep),
                        unescape(unit, escapeChar, fieldSep, componentSep, repSep, subSep),
                        unescape(reference, escapeChar, fieldSep, componentSep, repSep, subSep),
                        flag == null ? null : flag.trim()));
                if (item == null || item.isBlank()) {
                    errors.add("存在缺少 OBX-3 指标编码的观察结果");
                }
            }
        }
        if (applicationNo == null || applicationNo.isBlank()) {
            errors.add("OBR-2/ORC-2 申请号缺失，无法定位要回写的申请");
        }
        if (observations.isEmpty()) {
            errors.add("消息中没有 OBX 观察结果");
        }
        return new Oru(messageType, sendingApp == null ? "" : sendingApp,
                controlId == null ? "" : controlId,
                applicationNo == null ? "" : applicationNo.trim(), observations, errors);
    }

    /**
     * 取字段值。
     *
     * @param mshSegment MSH 段特殊：MSH-1 是分隔符本身，数组下标 = 字段号 - 1；
     *                   其他段数组下标 = 字段号（下标 0 是段名，如 OBR-2 → index 2）
     */
    private static String value(String[] fields, int fieldNo, boolean mshSegment) {
        int index = mshSegment ? fieldNo - 1 : fieldNo;
        return index >= 0 && index < fields.length ? fields[index] : null;
    }

    /** 取字段的第 0 个组件（如 OBR-2 = 申请号^...）。 */
    private static String component(String[] fields, int fieldNo, String componentSep,
                                    boolean mshSegment) {
        return componentIndex(fields, fieldNo, componentSep, 0, mshSegment);
    }

    private static String componentIndex(String[] fields, int fieldNo, String componentSep,
                                         int index, boolean mshSegment) {
        String value = value(fields, fieldNo, mshSegment);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(java.util.regex.Pattern.quote(componentSep), -1);
        return index < parts.length ? parts[index] : null;
    }

    /** HL7 转义还原（\F\ \S\ \R\ \E\ \T\ 及 \X..\ 十六进制）。 */
    static String unescape(String value, String escapeChar, String fieldSep, String componentSep,
                           String repSep, String subSep) {
        if (value == null || value.isEmpty() || !value.contains(escapeChar)) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            if (!value.startsWith(escapeChar, i)) {
                sb.append(value.charAt(i++));
                continue;
            }
            int end = value.indexOf(escapeChar, i + escapeChar.length());
            if (end < 0) {
                // 未闭合的转义符：按普通字符处理，不吞内容
                sb.append(value.charAt(i++));
                continue;
            }
            String token = value.substring(i + escapeChar.length(), end);
            switch (token) {
                case "F" -> sb.append(fieldSep);
                case "S" -> sb.append(componentSep);
                case "R" -> sb.append(repSep);
                case "E" -> sb.append(escapeChar);
                case "T" -> sb.append(subSep);
                case ".br" -> sb.append('\n');
                default -> sb.append(token);
            }
            i = end + escapeChar.length();
        }
        return sb.toString();
    }
}
