package com.alibaba.jvm.sandbox.api.listener.ext;

import com.alibaba.jvm.sandbox.api.event.*;
import com.alibaba.jvm.sandbox.api.listener.EventListener;
import com.alibaba.jvm.sandbox.api.util.BehaviorDescriptor;
import com.alibaba.jvm.sandbox.api.util.CacheGet;
import com.alibaba.jvm.sandbox.api.util.GaStringUtils;
import com.alibaba.jvm.sandbox.api.util.LazyGet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Stack;

/**
 * 通知监听器
 * <p>
 * 该监听器是一个适配器，用于将{@link EventListener}所发布的事件Event转换为Advice
 * 主要用于将{@link AdviceListener}转换为，
 * </p>
 *
 * @author luanjia@taobao.com
 * @since {@code sandbox-api:1.0.10}
 */
public class AdviceAdapterListener implements EventListener {

    /**
     * 通知监听器
     * <p>
     * 用于将Event转换为更友好的Advice
     * </p>
     */
    private final AdviceListener adviceListener;

    public AdviceAdapterListener(final AdviceListener adviceListener) {
        this.adviceListener = adviceListener;
    }

    /**
     * 通知操作堆栈的引用
     */
    private final ThreadLocal<OpStack> opStackRef = ThreadLocal.withInitial(OpStack::new);

    /**
     * 当事件触发时会回调该方法以完成对事件的处理
     *
     * @param event 触发事件
     * @throws Throwable
     */
    @Override
    final public void onEvent(final Event event) throws Throwable {
        // 获取操作堆栈
        final OpStack opStack = opStackRef.get();
        try {
            switchEvent(opStack, event);
        } finally {
            // 如果执行到TOP的最后一个事件，则需要主动清理占用的资源
            if (opStack.isEmpty()) {
                opStackRef.remove();
            }
        }

    }


    // 执行事件
    private void switchEvent(final OpStack opStack, final Event event) throws Throwable {

        switch (event.type) {
            // BEFORE事件: 在行为(方法)正式执行之前触发
            case BEFORE: {
                final Advice advice = convertEvent((BeforeEvent) event);
                final Advice top;
                final Advice parent;

                // 当前是顶层调用，即：在触发该行为之前没有任何触发过任何行为
                if (opStack.isEmpty()) {
                    top = parent = advice;
                }
                // 非顶层调用, 即: 在触发该行为之前已经触发过其他行为, 因此需要从栈中获取到其顶层Advice和上层Advice
                else {
                    parent = opStack.peek().advice;
                    top = parent.getProcessTop();
                }
                // 为当前Advice设置顶层调用和上层调用
                advice.applyBefore(top, parent);
                // 将当前的Advice压入操作堆栈(后进先出)
                opStackRef.get().pushForBegin(advice);

                // 将Event转换为Advice后, 就可以直接调用AdviceListener的before方法来让AdviceListener感知到事件的触发
                adviceListener.before(advice);
                break;
            }

            // 这里需要感知到IMMEDIATELY，修复#117
            case IMMEDIATELY_THROWS:
            case IMMEDIATELY_RETURN: {
                final InvokeEvent invokeEvent = (InvokeEvent) event;
                opStack.popByExpectInvokeId(invokeEvent.invokeId);
                // 修复#123
                break;
            }

            case RETURN: {
                // RETURN事件: 在行为(方法)执行完毕并返回结果之后触发
                final ReturnEvent rEvent = (ReturnEvent) event;
                // 从操作堆栈中弹出该行为所对应的Advice
                final WrapAdvice wrapAdvice = opStack.popByExpectInvokeId(rEvent.invokeId);
                if (null != wrapAdvice) {
                    // 将方法的返回值设置到Advice中
                    Advice advice = wrapAdvice.advice.applyReturn(rEvent.object);
                    try {
                        // 将Event转换为Advice后, 就可以直接调用AdviceListener的afterReturning方法来让AdviceListener感知到事件的触发
                        adviceListener.afterReturning(advice);
                    } finally {
                        adviceListener.after(advice);
                    }
                }
                break;
            }
            case THROWS: {
                // THROWS事件: 在行为(方法)执行过程中抛出异常之后触发
                final ThrowsEvent tEvent = (ThrowsEvent) event;
                final WrapAdvice wrapAdvice = opStack.popByExpectInvokeId(tEvent.invokeId);
                if (null != wrapAdvice) {
                    Advice advice = wrapAdvice.advice.applyThrows(tEvent.throwable);
                    try {
                        // 将Event转换为Advice后, 就可以直接调用AdviceListener的afterThrowing方法来让AdviceListener感知到事件的触发
                        adviceListener.afterThrowing(advice);
                    } finally {
                        adviceListener.after(advice);
                    }
                }
                break;
            }

            // CALL_BEFORE事件: 在一个方法内部调用其他方法之前触发
            case CALL_BEFORE: {
                final CallBeforeEvent cbEvent = (CallBeforeEvent) event;
                // 从操作堆栈中获取到对应的WrapAdvice
                final WrapAdvice wrapAdvice = opStack.peekByExpectInvokeId(cbEvent.invokeId);
                if (null == wrapAdvice) {
                    return;
                }
                final CallTarget target;
                // 创建一个CallTarget对象，用于存储调用目标的信息, 并将其Attach到Advice上
                wrapAdvice.attach(target = new CallTarget(
                        cbEvent.lineNumber,
                        toJavaClassName(cbEvent.owner),
                        cbEvent.name,
                        cbEvent.desc
                ));
                // 调用AdviceListener的beforeCall方法来让AdviceListener感知到事件的触发
                adviceListener.beforeCall(
                        wrapAdvice.advice,
                        target.callLineNum,
                        target.callJavaClassName,
                        target.callJavaMethodName,
                        target.callJavaMethodDesc
                );
                break;
            }

            // CALL_BEFORE事件: 在一个方法内部调用其他方法正常返回之后触发
            case CALL_RETURN: {
                final CallReturnEvent crEvent = (CallReturnEvent) event;
                // 从操作堆栈中获取到对应的WrapAdvice
                final WrapAdvice wrapAdvice = opStack.peekByExpectInvokeId(crEvent.invokeId);
                if (null == wrapAdvice) {
                    return;
                }
                final CallTarget target = wrapAdvice.attachment();
                if (null == target) {
                    // 这里做一个容灾保护，防止在callBefore()中发生什么异常导致beforeCall()之前失败
                    return;
                }
                // 调用AdviceListener的afterCallReturning方法来让AdviceListener感知到事件的触发
                try {
                    adviceListener.afterCallReturning(
                            wrapAdvice.advice,
                            target.callLineNum,
                            target.callJavaClassName,
                            target.callJavaMethodName,
                            target.callJavaMethodDesc
                    );
                } finally {
                    adviceListener.afterCall(
                            wrapAdvice.advice,
                            target.callLineNum,
                            target.callJavaClassName,
                            target.callJavaMethodName,
                            target.callJavaMethodDesc,
                            null
                    );
                }
                break;
            }

            // CALL_THROWS事件: 在一个方法内部调用其他方法抛出异常之后触发
            case CALL_THROWS: {
                final CallThrowsEvent ctEvent = (CallThrowsEvent) event;
                final WrapAdvice wrapAdvice = opStack.peekByExpectInvokeId(ctEvent.invokeId);
                if (null == wrapAdvice) {
                    return;
                }
                final CallTarget target = wrapAdvice.attachment();
                if (null == target) {
                    // 这里做一个容灾保护，防止在callBefore()中发生什么异常导致beforeCall()之前失败
                    return;
                }
                // 调用AdviceListener的afterCallThrowing方法来让AdviceListener感知到事件的触发
                try {
                    adviceListener.afterCallThrowing(
                            wrapAdvice.advice,
                            target.callLineNum,
                            target.callJavaClassName,
                            target.callJavaMethodName,
                            target.callJavaMethodDesc,
                            ctEvent.throwException
                    );
                } finally {
                    adviceListener.afterCall(
                            wrapAdvice.advice,
                            target.callLineNum,
                            target.callJavaClassName,
                            target.callJavaMethodName,
                            target.callJavaMethodDesc,
                            ctEvent.throwException
                    );
                }
                break;
            }

            // LINE事件: 在代码行被执行前触发
            case LINE: {
                final LineEvent lEvent = (LineEvent) event;
                // 从操作堆栈中获取到对应的WrapAdvice
                final WrapAdvice wrapAdvice = opStack.peekByExpectInvokeId(lEvent.invokeId);
                if (null == wrapAdvice) {
                    return;
                }
                // 调用AdviceListener的beforeLine方法来让AdviceListener感知到事件的触发
                adviceListener.beforeLine(wrapAdvice.advice, lEvent.lineNumber);
                break;
            }

            default:
                //ignore
        }//switch
    }

    private Advice convertEvent(BeforeEvent event) {
        final BeforeEvent bEvent = event;

        final ClassLoader loader = toClassLoader(bEvent.javaClassLoader);
        return new Advice(
                bEvent.processId,
                bEvent.invokeId,
                new LazyGet<Behavior>() {

                    private final ClassLoader _loader = loader;
                    private final String _javaClassName = bEvent.javaClassName;
                    private final String _javaMethodName = bEvent.javaMethodName;
                    private final String _javaMethodDesc = bEvent.javaMethodDesc;

                    @Override
                    protected Behavior initialValue() throws Throwable {
                        return toBehavior(
                                toClass(_loader, _javaClassName),
                                _javaMethodName,
                                _javaMethodDesc
                        );
                    }
                },
                loader,
                bEvent.argumentArray,
                bEvent.target
        );
    }


    // --- 以下为内部操作实现 ---


    /**
     * 通知操作堆栈
     */
    private static class OpStack {

        /**
         * 通知堆栈
         */
        private final Stack<WrapAdvice> adviceStack = new Stack<>();

        boolean isEmpty() {
            return adviceStack.isEmpty();
        }

        WrapAdvice peek() {
            return adviceStack.peek();
        }

        void pushForBegin(final Advice advice) {
            adviceStack.push(new WrapAdvice(advice));
        }

        /**
         * 在通知堆栈中，BEFORE:[RETURN/THROWS]的invokeId是配对的，
         * 如果发生错位则说明BEFORE的事件没有被成功压入堆栈，没有被正确的处理，外界没有正确感知BEFORE
         * 所以这里也要进行修正行的忽略对应的[RETURN/THROWS]
         *
         * @param expectInvokeId 期待的invokeId
         *                       必须要求和BEFORE的invokeId配对
         * @return 如果invokeId配对成功，则返回对应的Advice，否则返回null
         */
        WrapAdvice popByExpectInvokeId(final int expectInvokeId) {
            // 在通知堆栈中，BEFORE:[RETURN/THROWS]的invokeId是配对的，也就是说有BEFORE事件就一定会有[RETURN/THROWS]事件,
            return !adviceStack.isEmpty() && adviceStack.peek().advice.getInvokeId() == expectInvokeId
                    ? adviceStack.pop()
                    : null;
        }

        WrapAdvice peekByExpectInvokeId(final int expectInvokeId) {
            return !adviceStack.isEmpty()
                    && adviceStack.peek().advice.getInvokeId() == expectInvokeId
                    ? adviceStack.peek()
                    : null;
        }

    }

    // change internalClassName to javaClassName
    private String toJavaClassName(final String internalClassName) {
        if (GaStringUtils.isEmpty(internalClassName)) {
            return internalClassName;
        } else {

            // #302
            return internalClassName.replace('/', '.');
            // return internalClassName.replaceAll("/", ".");

        }
    }

    // 提取ClassLoader，从BeforeEvent中获取到的ClassLoader
    private ClassLoader toClassLoader(ClassLoader loader) {
        return null == loader
                // 如果此处为null，则说明遇到了来自Bootstrap的类，
                ? AdviceAdapterListener.class.getClassLoader()
                : loader;
    }

    // 根据JavaClassName从ClassLoader中提取出Class<?>对象
    private Class<?> toClass(ClassLoader loader, String javaClassName) throws ClassNotFoundException {
        return toClassLoader(loader).loadClass(javaClassName);
    }


    /**
     * 行为缓存KEY对象
     */
    private static class BehaviorCacheKey {
        private final Class<?> clazz;
        private final String javaMethodName;
        private final String javaMethodDesc;

        private BehaviorCacheKey(final Class<?> clazz,
                                 final String javaMethodName,
                                 final String javaMethodDesc) {
            this.clazz = clazz;
            this.javaMethodName = javaMethodName;
            this.javaMethodDesc = javaMethodDesc;
        }

        @Override
        public int hashCode() {
            return clazz.hashCode()
                    + javaMethodName.hashCode()
                    + javaMethodDesc.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BehaviorCacheKey)) {
                return false;
            }
            final BehaviorCacheKey key = (BehaviorCacheKey) o;
            return clazz.equals(key.clazz)
                    && javaMethodName.equals(key.javaMethodName)
                    && javaMethodDesc.equals(key.javaMethodDesc);
        }

    }

    // 行为缓存，为了增加性能，不要每次都从class通过反射获取行为
    private final CacheGet<BehaviorCacheKey, Behavior> toBehaviorCacheGet
            = new CacheGet<BehaviorCacheKey, Behavior>() {
        @Override
        protected Behavior load(BehaviorCacheKey key) {
            if ("<init>".equals(key.javaMethodName)) {
                for (final Constructor<?> constructor : key.clazz.getDeclaredConstructors()) {
                    if (key.javaMethodDesc.equals(new BehaviorDescriptor(constructor).getDescriptor())) {
                        return new Behavior.ConstructorImpl(constructor);
                    }
                }
            } else {
                for (final Method method : key.clazz.getDeclaredMethods()) {
                    if (key.javaMethodName.equals(method.getName())
                            && key.javaMethodDesc.equals(new BehaviorDescriptor(method).getDescriptor())) {
                        return new Behavior.MethodImpl(method);
                    }
                }
            }
            return null;
        }
    };

    /**
     * CALL目标对象
     */
    private static class CallTarget {

        final int callLineNum;
        final String callJavaClassName;
        final String callJavaMethodName;
        final String callJavaMethodDesc;

        CallTarget(int callLineNum, String callJavaClassName, String callJavaMethodName, String callJavaMethodDesc) {
            this.callLineNum = callLineNum;
            this.callJavaClassName = callJavaClassName;
            this.callJavaMethodName = callJavaMethodName;
            this.callJavaMethodDesc = callJavaMethodDesc;
        }
    }

    /**
     * 通知内部封装，主要是要封装掉attachment
     */
    private static class WrapAdvice implements Attachment {

        /**
         * 通知
         * <p>
         * Advice是一个核心类，它代表着一个行为的整个执行过程，随着行为的不断执行, Advice的状态也会不断变化。
         * </p>
         */
        final Advice advice;

        /**
         * 附件
         */
        Object attachment;

        WrapAdvice(Advice advice) {
            this.advice = advice;
        }

        @Override
        public void attach(Object attachment) {
            this.attachment = attachment;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T attachment() {
            return (T) attachment;
        }
    }

    /**
     * 根据提供的行为名称、行为描述从指定的Class中获取对应的行为
     *
     * @param clazz          指定的Class
     * @param javaMethodName 行为名称
     * @param javaMethodDesc 行为参数声明
     * @return 匹配的行为
     * @throws NoSuchMethodException 如果匹配不到行为，则抛出该异常
     */
    private Behavior toBehavior(final Class<?> clazz,
                                final String javaMethodName,
                                final String javaMethodDesc) throws NoSuchMethodException {
        final Behavior behavior = toBehaviorCacheGet.getFromCache(new BehaviorCacheKey(clazz, javaMethodName, javaMethodDesc));
        if (null == behavior) {
            throw new NoSuchMethodException(String.format("%s.%s(%s)", clazz.getName(), javaMethodName, javaMethodDesc));
        }
        return behavior;
    }

}
