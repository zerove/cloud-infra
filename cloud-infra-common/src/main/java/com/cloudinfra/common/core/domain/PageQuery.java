package com.cloudinfra.common.core.domain;

import lombok.Data;

/**
 * 通用分页请求参数 (2026-10-18, 报告 A1「分页四套风格」收敛)。
 *
 * <p>历史上有四套分页风格并存: {@code pageNum/pageSize} (system/file)、
 * {@code page/size} (notification)、{@code q/limit} (search) 与对象绑定,
 * 且各自的默认值/上限不一致 (如 notification 默认 20 vs 10)。
 * 本类把「缺省值 + 越界钳制」这两条规则收敛到一处, 作为后续分页接口的
 * 统一请求形态。</p>
 *
 * <p><b>钳制语义</b> (承接 notification 控制器此前的内联实现
 * {@code Math.max(page, 1)} / {@code Math.min(Math.max(size, 1), 100)},
 * 保持行为不变):</p>
 * <ul>
 *   <li>未设置 (null) → 取默认值 (pageNum=1, pageSize=10);</li>
 *   <li>显式传入但小于 1 → 钳到 1;</li>
 *   <li>pageSize 超过 {@link #MAX_PAGE_SIZE} → 钳到上限 (防大分页拖垮 DB)。</li>
 * </ul>
 *
 * <p>钳制只发生在 {@code normalized*} 方法中, 原始 getter 始终返回客户端传入的值
 * (便于回显与排查)。</p>
 */
@Data
public class PageQuery {

    /** 默认页码 */
    public static final int DEFAULT_PAGE_NUM = 1;

    /** 默认每页大小 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 每页大小上限 (与既有控制器钳制值一致) */
    public static final int MAX_PAGE_SIZE = 100;

    /** 当前页码, 从 1 开始 */
    private Integer pageNum = DEFAULT_PAGE_NUM;

    /** 每页大小, 上限 {@link #MAX_PAGE_SIZE} */
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    /** 钳制后的页码: null → 默认值; < 1 → 1 */
    public int normalizedPageNum() {
        return pageNum == null ? DEFAULT_PAGE_NUM : Math.max(pageNum, 1);
    }

    /** 钳制后的每页大小: null → 默认值; < 1 → 1; > {@link #MAX_PAGE_SIZE} → 上限 */
    public int normalizedPageSize() {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }
}
