package com.aibalance.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 防御式 JSON/数值解析工具：官方接口字段可能为字符串、数字或缺失，
 * 统一转换为可展示形式，避免一次异常拖垮整张卡片。
 */
final class JsonSupport {

    private JsonSupport() {
    }

    /** 安全取字符串字段；缺失时返回 null */
    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 安全取布尔字段；缺失时返回默认值 */
    static boolean bool(JsonNode node, String field, boolean dflt) {
        if (node == null) {
            return dflt;
        }
        JsonNode v = node.get(field);
        return v == null || !v.isValueNode() ? dflt : v.asBoolean(dflt);
    }

    /** 安全取数字字段：兼容数值类型与字符串数字；无法解析返回 null */
    static Double number(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        if (v.isTextual()) {
            try {
                return Double.parseDouble(v.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 安全取长整数字段：兼容数值类型与字符串数字；无法解析返回 null */
    static Long longNumber(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.longValue();
        }
        if (v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 从任意路径取数字：兼容 null 节点、缺失路径 */
    static Double numberAt(JsonNode root, String... path) {
        JsonNode cur = root;
        for (String p : path) {
            if (cur == null || !cur.isObject()) {
                return null;
            }
            cur = cur.get(p);
        }
        if (cur == null) {
            return null;
        }
        if (cur.isNumber()) {
            return cur.doubleValue();
        }
        if (cur.isTextual()) {
            try {
                return Double.parseDouble(cur.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 字符串转 Double，失败返回 null */
    static Double parseDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** JSON 字符串转义（用于拼接 JSON body 时安全嵌入用户输入）。 */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}