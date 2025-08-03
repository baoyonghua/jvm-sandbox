package com.alibaba.jvm.sandbox.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sandbox守护者
 * <p>
 * 用来保护sandbox的操作所产生的事件不被响应
 * </p>
 *
 * @author oldmanpushcart@gamil.com
 */
public class SandboxProtector {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Sandbox守护者单例
     */
    public static final SandboxProtector instance = new SandboxProtector();

    private final ThreadLocal<AtomicInteger> isInProtectingThreadLocal = ThreadLocal.withInitial(() -> new AtomicInteger(0));

    /**
     * 进入守护区域
     *
     * @return 守护区域当前引用计数
     */
    public int enterProtecting() {
        final int referenceCount = isInProtectingThreadLocal.get().getAndIncrement();
        if (logger.isDebugEnabled()) {
            logger.debug("thread:{} enter protect:{}", Thread.currentThread(), referenceCount);
        }
        return referenceCount;
    }

    /**
     * 离开守护区域
     *
     * @return 守护区域当前引用计数
     */
    public int exitProtecting() {
        final int referenceCount = isInProtectingThreadLocal.get().decrementAndGet();
        // assert referenceCount >= 0;
        if (referenceCount == 0) {
            isInProtectingThreadLocal.remove();
            if (logger.isDebugEnabled()) {
                logger.debug("thread:{} exit protect:{} with clean", Thread.currentThread(), referenceCount);
            }
        } else if (referenceCount > 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("thread:{} exit protect:{}", Thread.currentThread(), referenceCount);
            }
        } else {
            logger.warn("thread:{} exit protect:{} with error!", Thread.currentThread(), referenceCount);
        }
        return referenceCount;
    }

    /**
     * 判断当前是否处于守护区域中
     *
     * @return TRUE:在守护区域中；FALSE：非守护区域中
     */
    public boolean isInProtecting() {
        // fix for #384
        final boolean res = isInProtectingThreadLocal.get().get() > 0;
        if (!res) {
            isInProtectingThreadLocal.remove();
        }
        return res;
    }

    /**
     * 守护接口定义的所有方法
     *
     * @param protectTargetInterface 保护目标接口类型
     * @param protectTarget          保护目标接口实现
     * @param <T>                    接口类型
     * @return 被保护的目标接口实现
     */
    @SuppressWarnings("unchecked")
    public <T> T protectProxy(final Class<T> protectTargetInterface, final T protectTarget) {
        // 创建一个JDK动态代理
        return (T) Proxy.newProxyInstance(
                // SandboxClassLoader
                getClass().getClassLoader(),
                new Class<?>[]{protectTargetInterface},
                (proxy, method, args) -> {
                    // 进入守护区域, 增加
                    final int enterReferenceCount = enterProtecting();
                    try {
                        // 调用目标方法
                        return method.invoke(protectTarget, args);
                    } finally {
                        // 退出守护区域
                        final int exitReferenceCount = exitProtecting();
                        // assert enterReferenceCount == exitReferenceCount;
                        if (enterReferenceCount != exitReferenceCount) {
                            logger.warn("thread:{} exit protecting with error!, expect:{} actual:{}",
                                    Thread.currentThread(),
                                    enterReferenceCount,
                                    exitReferenceCount
                            );
                        }
                    }
                });
    }

}
