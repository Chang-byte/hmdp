package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    // 不涉及多线程问题的，作用域局部作用域，只要不逃逸，就线程安全。 实现动态刷新的类，一般是为了在刷新动态的时候使用的。
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
