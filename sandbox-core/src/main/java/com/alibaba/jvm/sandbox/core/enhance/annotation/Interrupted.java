package com.alibaba.jvm.sandbox.core.enhance.annotation;

import com.alibaba.jvm.sandbox.api.listener.EventListener;

import java.lang.annotation.*;

/**
 * 中断式事件监听器
 * <p>
 * 当事件监听器{@link EventListener}处理事件抛出异常时,将会中断原有方法调用
 *
 * @author luanjia@taobao.com
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Interrupted {
}
