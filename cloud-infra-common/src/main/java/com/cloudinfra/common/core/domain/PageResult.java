package com.cloudinfra.common.core.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

/**
 * 通用分页结果 (2026-10-18, 报告 A1「分页四套风格」收敛)。
 *
 * <p>此前分页接口返回裸 {@code List}, 客户端拿不到总数、必须逐服务特判;
 * 本类统一携带 {@code records + total + 页面窗口}, 由 {@link Result} 包裹后
 * 跨服务传输。</p>
 *
 * @param <T> 记录类型
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页记录 */
    private List<T> records = List.of();

    /** 满足条件的总记录数 (非当前页条数) */
    private long total;

    /** 当前页码 */
    private long pageNum;

    /** 每页大小 */
    private long pageSize;

    /** 由查询结果构造分页结果 */
    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        return result;
    }

    /**
     * 空分页结果 (records 空, total 与页面窗口均为 0)。
     *
     * <p>pageNum/pageSize 的 0 表示「未指定请求窗口」—— 用于服务降级/兜底
     * (fallback) 等拿不到分页上下文的场景; 正常查询路径应使用
     * {@link #of(List, long, long, long)} 回显真实窗口。</p>
     */
    public static <T> PageResult<T> empty() {
        return of(List.of(), 0L, 0L, 0L);
    }

    /** 转换记录类型, 保留 total 与页面窗口 */
    public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
        // 显式类型见证: Stream.toList() 非泛型方法, 无法把目标类型传导给 map(),
        // 不加 <R> 时 javac 会把通配捕获当元素类型 (List<capture of ? extends R>)。
        List<R> mapped = this.records.stream().<R>map(mapper).toList();
        return PageResult.of(mapped, total, pageNum, pageSize);
    }
}
