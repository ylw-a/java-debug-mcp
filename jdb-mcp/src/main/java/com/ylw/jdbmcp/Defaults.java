package com.ylw.jdbmcp;

/**
 * 默认配置常量 - 会话级配置的"推荐起点"（非硬上限，可通过 configure / start_session 入参覆盖）。
 *
 * <p>防沉迷原则：默认值偏保守，AI 有正当需求时显式抬限（每次抬限留痕、返回提示）。
 * 这些值不是天花板，是起点。
 */
public final class Defaults {

    private Defaults() {}

    // ---- capture 限制 ----
    /** 表达式最大解引用次数（root 之后）。 */
    public static final int PATH_DEPTH = 5;
    /** 对象字段展开最大深度。 */
    public static final int RENDER_DEPTH = 5;
    /** toString / 字符串截断字符数。 */
    public static final int TO_STRING_LIMIT = 500;
    /** 数组 / 集合展开元素数。 */
    public static final int COLLECTION_LIMIT = 20;
    /** 单对象展开字段数上限。 */
    public static final int MAX_FIELDS = 50;

    // ---- Mode-B 预算（每次挂起）----
    public static final int EXPLORE_BUDGET = 5;
    public static final int EVAL_BUDGET = 2;
    public static final int STEP_BUDGET = 5;
    public static final int MODEB_TIMEOUT_SEC = 60;

    // ---- 危险操作开关 ----
    public static final boolean ALLOW_EVAL = false;

    // ---- 发现 / 枚举 ----
    public static final int LIST_CLASSES_LIMIT = 100;
    public static final int MAX_FRAMES = 200;
}
