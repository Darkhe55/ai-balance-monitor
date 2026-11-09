package com.aibalance.ui;

/**
 * 展示条目的形式常量与辅助解析（主卡片、悬浮球、展示条目对话框共用）：
 * <ul>
 *   <li>形式：进度条（BAR）/ 文字（TEXT）；未配置时按默认（metric 带百分比 → 进度条，其余 → 文字）。</li>
 *   <li>从明细值中提取第一个「剩余 X%」的百分比，用于判断明细能否渲染为进度条。</li>
 * </ul>
 */
final class EntryFormat {

    /** 进度条形式。 */
    static final String MODE_BAR = "BAR";
    /** 文字形式。 */
    static final String MODE_TEXT = "TEXT";

    private EntryFormat() {
    }

    /** 提取明细值中第一个「剩余 X%」的 X（窗口/模型行的剩余百分比）；无法解析返回 null。 */
    static Double parseRemainPercent(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("剩余\\s*([\\d.]+)\\s*%").matcher(value);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 该明细值能否渲染为进度条（含可解析的「剩余 X%」）。 */
    static boolean canRenderBar(String value) {
        return parseRemainPercent(value) != null;
    }

    /** 条目 key 的展示形式：返回 "BAR"/"TEXT"；未配置返回 null（默认形式）。 */
    static String modeOf(java.util.Map<String, String> entryModes, String key) {
        return entryModes == null ? null : entryModes.get(key);
    }

    /** 指定明细是否应渲染为进度条：用户显式选择 BAR，或未配置但值可作进度条（兼容旧行为）。 */
    static boolean detailWantsBar(java.util.Map<String, String> entryModes, String key, String value) {
        String mode = modeOf(entryModes, key);
        if (MODE_TEXT.equals(mode)) {
            return false;
        }
        if (MODE_BAR.equals(mode)) {
            return canRenderBar(value);
        }
        return false;
    }
}