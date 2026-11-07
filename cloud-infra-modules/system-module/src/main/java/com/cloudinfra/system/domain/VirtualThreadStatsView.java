package com.cloudinfra.system.domain;

/**
 * 虚拟线程运行时观测视图（调用瞬间采样）。
 *
 * <p>{@code blockedVirtual} 作为“钉住风险”的可观测代理：处于 {@link Thread.State#BLOCKED}
 * 的虚拟线程通常对应 synchronized/监视器等待。
 */
public record VirtualThreadStatsView(int activeVirtual, int blockedVirtual, long sampledAtEpochMs) {
}

