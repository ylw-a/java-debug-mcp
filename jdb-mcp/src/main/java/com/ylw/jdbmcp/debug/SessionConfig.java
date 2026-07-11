package com.ylw.jdbmcp.debug;

import com.ylw.jdbmcp.Defaults;
import com.ylw.jdbmcp.StartParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 可变会话配置：capture 限制、Mode-B 预算、allowEval。
 *
 * <p>{@link ExprCapture.Limits} 采用 copy-on-write（volatile 引用指向不可变对象）：handler 线程通过
 * {@link #configure} 替换引用，事件循环线程每次 capture 读取 {@link #limits()}，无锁安全。
 *
 * <p>默认值来自 {@link Defaults}；start_session 入参与 configure 均可覆盖。
 * 防沉迷：抬限高于默认值时由 {@link #configure} 返回 raised 列表，供 DebugSession 加提示 + 计数。
 */
public final class SessionConfig {

    private volatile ExprCapture.Limits limits;
    private volatile int exploreBudget;
    private volatile int evalBudget;
    private volatile int stepBudget;
    private volatile int modeBTimeoutSec;
    private volatile boolean allowEval;
    private volatile boolean safeMode = false;

    public SessionConfig() {
        this.limits = new ExprCapture.Limits(
                Defaults.PATH_DEPTH, Defaults.RENDER_DEPTH,
                Defaults.TO_STRING_LIMIT, Defaults.COLLECTION_LIMIT, Defaults.MAX_FIELDS);
        this.exploreBudget = Defaults.EXPLORE_BUDGET;
        this.evalBudget = Defaults.EVAL_BUDGET;
        this.stepBudget = Defaults.STEP_BUDGET;
        this.modeBTimeoutSec = Defaults.MODEB_TIMEOUT_SEC;
        this.allowEval = Defaults.ALLOW_EVAL;
    }

    public ExprCapture.Limits limits() { return limits; }
    public int exploreBudget() { return exploreBudget; }
    public int evalBudget() { return evalBudget; }
    public int stepBudget() { return stepBudget; }
    public int modeBTimeoutSec() { return modeBTimeoutSec; }
    public boolean allowEval() { return allowEval; }
    public boolean safeMode() { return safeMode; }

    /** 应用 start_session 的初始覆盖（null 字段保留默认）。 */
    public void applyStart(StartParams p) {
        if (p == null) return;
        if (p.maxDepth != null || p.maxStrLen != null || p.maxCollSize != null || p.maxFields != null || p.safeMode != null) {
            limits = buildLimits(limits, p.maxDepth, p.maxStrLen, p.maxCollSize, p.maxFields, p.safeMode);
        }
        if (p.exploreBudget != null) exploreBudget = p.exploreBudget;
        if (p.evalBudget != null) evalBudget = p.evalBudget;
        if (p.stepBudget != null) stepBudget = p.stepBudget;
        if (p.modeBTimeoutSec != null) modeBTimeoutSec = p.modeBTimeoutSec;
        if (p.allowEval != null) allowEval = p.allowEval;
        if (p.safeMode != null) safeMode = p.safeMode;
    }

    /**
     * 应用会话进行中的覆盖；返回被抬升至"高于默认值"的字段描述列表（供防沉迷提示）。
     * 降低值无提示。只传需要改的字段，其余保持。
     */
    public List<String> configure(Integer maxDepth, Integer maxStrLen, Integer maxCollSize,
                                  Integer maxFields, Boolean safeMode, Integer exploreBudget,
                                  Integer evalBudget, Integer stepBudget, Integer modeBTimeoutSec,
                                  Boolean allowEval) {
        List<String> raised = new ArrayList<>();
        if (maxDepth != null || maxStrLen != null || maxCollSize != null || maxFields != null || safeMode != null) {
            limits = buildLimits(limits, maxDepth, maxStrLen, maxCollSize, maxFields, safeMode);
            if (limits.pathDepth > Defaults.PATH_DEPTH) raised.add("maxDepth=" + limits.pathDepth);
            if (limits.toStringLimit > Defaults.TO_STRING_LIMIT) raised.add("maxStrLen=" + limits.toStringLimit);
            if (limits.collectionLimit > Defaults.COLLECTION_LIMIT) raised.add("maxCollSize=" + limits.collectionLimit);
            if (limits.maxFields > Defaults.MAX_FIELDS) raised.add("maxFields=" + limits.maxFields);
            if (safeMode != null && safeMode) raised.add("safeMode=true");
        }
        if (exploreBudget != null) {
            this.exploreBudget = exploreBudget;
            if (exploreBudget > Defaults.EXPLORE_BUDGET) raised.add("exploreBudget=" + exploreBudget);
        }
        if (evalBudget != null) {
            this.evalBudget = evalBudget;
            if (evalBudget > Defaults.EVAL_BUDGET) raised.add("evalBudget=" + evalBudget);
        }
        if (stepBudget != null) {
            this.stepBudget = stepBudget;
            if (stepBudget > Defaults.STEP_BUDGET) raised.add("stepBudget=" + stepBudget);
        }
        if (modeBTimeoutSec != null) {
            this.modeBTimeoutSec = modeBTimeoutSec;
            if (modeBTimeoutSec > Defaults.MODEB_TIMEOUT_SEC) raised.add("modeBTimeoutSec=" + modeBTimeoutSec);
        }
        if (allowEval != null) {
            this.allowEval = allowEval;
            if (allowEval) raised.add("allowEval=true");
        }
        return raised;
    }

    /** maxDepth 同时控制 pathDepth 与 renderDepth（与原 captureDepth 语义一致）。 */
    private static ExprCapture.Limits buildLimits(ExprCapture.Limits old,
                                                  Integer maxDepth, Integer maxStrLen,
                                                  Integer maxCollSize, Integer maxFields,
                                                  Boolean safeMode) {
        int depth = maxDepth != null ? maxDepth : old.pathDepth;
        return new ExprCapture.Limits(
                depth,
                maxDepth != null ? maxDepth : old.renderDepth,
                maxStrLen != null ? maxStrLen : old.toStringLimit,
                maxCollSize != null ? maxCollSize : old.collectionLimit,
                maxFields != null ? maxFields : old.maxFields,
                safeMode != null ? safeMode : old.safeMode);
    }
}
