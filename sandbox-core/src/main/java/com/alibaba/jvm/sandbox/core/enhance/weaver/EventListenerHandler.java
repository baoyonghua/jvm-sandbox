package com.alibaba.jvm.sandbox.core.enhance.weaver;

import com.alibaba.jvm.sandbox.api.ProcessControlException;
import com.alibaba.jvm.sandbox.api.event.BeforeEvent;
import com.alibaba.jvm.sandbox.api.event.Event;
import com.alibaba.jvm.sandbox.api.event.InvokeEvent;
import com.alibaba.jvm.sandbox.api.listener.EventListener;
import com.alibaba.jvm.sandbox.core.CoreModule;
import com.alibaba.jvm.sandbox.core.manager.impl.DefaultCoreModuleManager;
import com.alibaba.jvm.sandbox.core.util.ObjectIDs;
import com.alibaba.jvm.sandbox.core.util.SandboxProtector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.com.alibaba.jvm.sandbox.spy.Spy;
import java.com.alibaba.jvm.sandbox.spy.SpyHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.jvm.sandbox.api.event.Event.Type.IMMEDIATELY_RETURN;
import static com.alibaba.jvm.sandbox.api.event.Event.Type.IMMEDIATELY_THROWS;
import static com.alibaba.jvm.sandbox.core.util.SandboxReflectUtils.isInterruptEventHandler;
import static java.com.alibaba.jvm.sandbox.spy.Spy.Ret.newInstanceForNone;
import static java.com.alibaba.jvm.sandbox.spy.Spy.Ret.newInstanceForThrows;
import static org.apache.commons.lang3.ArrayUtils.contains;
import static org.apache.commons.lang3.StringUtils.join;

/**
 * 事件处理，它是SpyHandler的实现类
 *
 * @author luanjia@taobao.com
 */
public class EventListenerHandler implements SpyHandler {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    // 调用id序列生成器
    private final AtomicInteger invokeIdSequencer = new AtomicInteger(1000);

    /**
     * 事件处理器映射表，key: 监听器id, value: 事件处理器
     * <p>
     * 在<code>事件处理器EventProcessor</code>中会维护一个{@code 事件监听器EventListener}的引用，以便于在事件触发时
     * 事件处理器能够将对应的事件委派给事件监听器进行处理。
     * </p>
     * <p>
     * 在模块激活{@link DefaultCoreModuleManager#active(CoreModule)}时,
     * 会调用{@link #active}方法来构造EventListener对应的事件处理器EventProcessor，并将EventProcessor放入到此映射表中。
     * </p>
     */
    private final Map<Integer/*LISTENER_ID*/, EventProcessor> mappingOfEventProcessor = new ConcurrentHashMap<>();

    /**
     * EventListenerHandler单例对象
     */
    private final static EventListenerHandler singleton = new EventListenerHandler();

    public static EventListenerHandler getSingleton() {
        return singleton;
    }


    /**
     * 注册事件处理器
     *
     * @param listenerId 事件监听器ID
     * @param listener   事件监听器
     * @param eventTypes 监听事件集合
     */
    public void active(final int listenerId,
                       final EventListener listener,
                       final Event.Type[] eventTypes) {
        // 构造事件处理器EventProcessor
        EventProcessor processor = new EventProcessor(listenerId, listener, eventTypes);
        // 将事件处理器添加到一个全局Map中，在后续事件触发时，会通过监听器id来从此Map中获取对应的EventProcessor
        // 如果没获取EventProcessor则代表该事件监听器EventListener没有被激活，到那么事件就会被直接丢弃
        mappingOfEventProcessor.put(listenerId, processor);
        logger.info("activated listener[id={};target={};] event={}",
                listenerId,
                listener,
                join(eventTypes, ",")
        );
    }

    /**
     * 取消事件处理器的监听
     *
     * @param listenerId 事件处理器ID
     */
    public void frozen(int listenerId) {
        final EventProcessor processor = mappingOfEventProcessor.remove(listenerId);
        if (null == processor) {
            logger.debug("ignore frozen listener={}, because not found.", listenerId);
            return;
        }

        logger.info("frozen listener[id={};target={};]",
                listenerId,
                processor.listener
        );

        // processor.clean();
    }

    /**
     * 调用出发事件处理&调用执行流程控制
     *
     * @param listenerId 处理器ID
     * @param processId  调用过程ID
     * @param invokeId   调用ID
     * @param event      调用事件
     * @param processor  事件处理器
     * @return 处理返回结果
     * @throws Throwable 当出现未知异常时,且事件处理器为中断流程事件时抛出
     */
    private Spy.Ret handleEvent(final int listenerId,
                                final int processId,
                                final int invokeId,
                                final Event event,
                                final EventProcessor processor) throws Throwable {
        // 获取到事件处理器中封装的事件监听器
        final EventListener listener = processor.listener;

        // 如果当前事件不在事件监听器处理列表中，则直接返回RET_NONE，不处理事件
        if (!contains(processor.eventTypes, event.type)) {
            return newInstanceForNone();
        }
        try {
            // 【核心】调用EventListener#onEvent来进行事件的处理
            listener.onEvent(event);
        }
        catch (ProcessControlException pce) {
            // 如果在EventListener#onEvent的执行过程中，抛出了ProcessControlException，则代表需要变更代码的执行流程

            final EventProcessor.Process process = processor.processRef.get(); // 获取当前的调用过程
            final ProcessControlException.State state = pce.getState();  // 获取流程控制的状态

            // 如果要求忽略后续处理所有事件，则需要在此处进行标记
            if (pce.isIgnoreProcessEvent()) {
                process.markIgnoreProcess();
            }

            // 根据流程控制状态来决定如何控制后续流程(是继续执行，还是立即返回，或是抛出异常)
            switch (state) {
                case RETURN_IMMEDIATELY: {  // 立即返回
                    // 如果已经禁止后续返回任何事件了，则不进行后续的操作
                    if (pce.isIgnoreProcessEvent()) {
                        logger.debug("on-event: event|{}|{}|{}|{}, ignore immediately-return-event, isIgnored.",
                                event.type,
                                processId,
                                invokeId,
                                listenerId
                        );
                    } else {
                        // 补偿立即返回事件，即回调EventListener#onEvent方法向其通知ImmediatelyReturnEvent<立即返回事件>
                        compensateProcessControlEvent(pce, processor, process, event);
                    }

                    // 如果是在BEFORE中立即返回，则后续不会再有RETURN事件产生，这里需要主动对齐堆栈
                    if (event.type == Event.Type.BEFORE) {
                        process.popInvokeId();
                    }

                    // 让流程立即返回一个预定的对象
                    return Spy.Ret.newInstanceForReturn(pce.getRespond());

                }
                case THROWS_IMMEDIATELY: {  // 立即抛出异常
                    final Throwable throwable = (Throwable) pce.getRespond();
                    // 如果已经禁止后续返回任何事件了，则不进行后续的操作
                    if (pce.isIgnoreProcessEvent()) {
                        logger.debug("on-event: event|{}|{}|{}|{}, ignore immediately-throws-event, isIgnored.",
                                event.type,
                                processId,
                                invokeId,
                                listenerId
                        );
                    } else {
                        // 如果是在BEFORE中立即抛出，则后续不会再有THROWS事件产生，这里需要主动对齐堆栈
                        if (event.type == Event.Type.BEFORE) {
                            process.popInvokeId();
                        }
                        // 标记本次异常由ImmediatelyException产生，让下次异常事件处理直接忽略
                        if (event.type != Event.Type.THROWS) {
                            process.markExceptionFromImmediately();
                        }

                        // 补偿立即抛出事件(即：回调EventListener#onEvent方法向其通知ImmediatelyThrowsEvent<立即抛出事件>
                        compensateProcessControlEvent(pce, processor, process, event);
                    }
                    // 让流程立即抛出异常
                    return Spy.Ret.newInstanceForThrows(throwable);

                }
                case NONE_IMMEDIATELY:  // 什么都不操作，继续执行原有方法
                default: {
                    return newInstanceForNone();
                }
            }
        }
        // BEFORE处理异常,打日志,并通知下游不需要进行处理
        catch (Throwable throwable) {
            // 如果当前事件监听器是可中断的事件监听器(即：EventListener上标注了Interrupted注解)，则直接抛出异常中断当前方法
            // 可中断的事件监听器: 当事件监听器处理事件抛出异常时,将会中断原有方法调用
            if (isInterruptEventHandler(listener.getClass())) {
                throw throwable;
            } else {
                // 普通事件监听器打个日志后,直接放行, 不会影响原有方法的调用
                logger.warn("on-event: event|{}|{}|{}|{} occur an error.",
                        event.type,
                        processId,
                        invokeId,
                        listenerId,
                        throwable
                );
            }
        }
        // 默认返回不进行任何流程变更，即：事件处理器不会对当前调用过程产生任何影响
        return newInstanceForNone();
    }

    // 补偿事件
    // 随着历史版本的演进，一些事件已经过期，但为了兼容API，需要在这里进行补偿
    private void compensateProcessControlEvent(ProcessControlException pce, EventProcessor processor, EventProcessor.Process process, Event event) {
        // 核对是否需要补偿，如果目标监听器没监听过这类事件，则不需要进行补偿
        if (!(event instanceof InvokeEvent) || !contains(processor.eventTypes, event.type)) {
            return;
        }

        final InvokeEvent iEvent = (InvokeEvent) event;
        final Event compensateEvent;

        // 补偿立即返回事件
        if (pce.getState() == ProcessControlException.State.RETURN_IMMEDIATELY
                && contains(processor.eventTypes, IMMEDIATELY_RETURN)) {
            compensateEvent = process
                    .getEventFactory()
                    .makeImmediatelyReturnEvent(iEvent.processId, iEvent.invokeId, pce.getRespond());
        }

        // 补偿立即抛出事件
        else if (pce.getState() == ProcessControlException.State.THROWS_IMMEDIATELY
                && contains(processor.eventTypes, IMMEDIATELY_THROWS)) {
            compensateEvent = process
                    .getEventFactory()
                    .makeImmediatelyThrowsEvent(iEvent.processId, iEvent.invokeId, (Throwable) pce.getRespond());
        }

        // 异常情况不补偿
        else {
            return;
        }

        try {
            // 回调EventListener#onEvent方法向其通知 ImmediatelyReturnEvent<立即返回事件> | ImmediatelyThrowsEvent<立即抛出事件>
            processor.listener.onEvent(compensateEvent);
        } catch (Throwable cause) {
            logger.warn("compensate-event: event|{}|{}|{}|{} when ori-event:{} occur error.",
                    compensateEvent.type,
                    iEvent.processId,
                    iEvent.invokeId,
                    processor.listenerId,
                    event.type,
                    cause
            );
        } finally {
            process.getEventFactory().returnEvent(compensateEvent);
        }
    }

    /*
     * 判断堆栈是否错位
     */
    private boolean checkProcessStack(final int processId,
                                      final int invokeId,
                                      final boolean isEmptyStack) {
        return (processId == invokeId && !isEmptyStack)
                || (processId != invokeId && isEmptyStack);
    }

    @Override
    public Spy.Ret handleOnBefore(int listenerId, int targetClassLoaderObjectID, Object[] argumentArray, String javaClassName, String javaMethodName, String javaMethodDesc, Object target) throws Throwable {

        // 在守护区内产生的事件不需要响应，直接返回RET_NONE即可
        if (SandboxProtector.instance.isInProtecting()) {
            logger.debug("listener={} is in protecting, ignore processing before-event", listenerId);
            return newInstanceForNone();
        }

        // 根据事件监听器id来获取到事件处理器
        final EventProcessor processor = mappingOfEventProcessor.get(listenerId);

        // 如果事件监听器尚未激活, 不做任何处理，直接返回RET_NONE即可
        if (null == processor) {
            logger.debug("listener={} is not activated, ignore processing before-event.", listenerId);
            return newInstanceForNone();
        }

        // 从ThreadLocal中获取对应的调用跟踪信息，如果不存在，则创建一个新的调用过程信息
        final EventProcessor.Process process = processor.processRef.get();

        // 当前调用过程所触发的事件是否需要被忽略，如果需要被忽略则立即返回
        if (process.isIgnoreProcess()) {
            logger.debug("listener={} is marked ignore process!", listenerId);
            return newInstanceForNone();
        }

        // 生成本次的调用ID，并将其压入调用过程中 -> 即: 一次调用过程中会有多个invokeId, 每次调用都会生成一个新的invokeId
        // 与processId不同的是，invokeId是针对每次调用的唯一标识，而processId是针对整个调用过程的唯一标识
        final int invokeId = invokeIdSequencer.getAndIncrement();
        process.pushInvokeId(invokeId);
        final int processId = process.getProcessId();  // 调用过程ID

        final ClassLoader javaClassLoader = ObjectIDs.instance.getObject(targetClassLoaderObjectID);
        // 构造BeforeEvent，并进行事件的处理
        final BeforeEvent event = process.getEventFactory().makeBeforeEvent(
                processId,
                invokeId,
                javaClassLoader,
                javaClassName,
                javaMethodName,
                javaMethodDesc,
                target,
                argumentArray
        );
        try {
            return handleEvent(listenerId, processId, invokeId, event, processor);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }

    @Override
    public Spy.Ret handleOnThrows(int listenerId, Throwable throwable) throws Throwable {
        return handleOnEnd(listenerId, throwable, false);
    }

    @Override
    public Spy.Ret handleOnReturn(int listenerId, Object object) throws Throwable {
        return handleOnEnd(listenerId, object, true);
    }


    private Spy.Ret handleOnEnd(final int listenerId,
                                final Object object,
                                final boolean isReturn) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (SandboxProtector.instance.isInProtecting()) {
            logger.debug("listener={} is in protecting, ignore processing {}-event", listenerId, isReturn ? "return" : "throws");
            return newInstanceForNone();
        }

        // 根据事件监听器id来从mappingOfEventProcessor中获取到事件处理器
        final EventProcessor wrap = mappingOfEventProcessor.get(listenerId);

        // 如果事件监听器尚未激活, 不做任何处理，直接返回RET_NONE即可
        if (null == wrap) {
            logger.debug("listener={} is not activated, ignore processing return-event|throws-event.", listenerId);
            return newInstanceForNone();
        }
        // 从ThreadLocal中获取对应的调用过程信息，如果不存在，则会创建一个新的调用过程信息
        // 按理来说，这里是一定能够获取到调用过程信息的，因为在handleOnBefore中已经创建了调用过程信息，并向调用过程Process中push了一个invokeId
        final EventProcessor.Process process = wrap.processRef.get();

        // 如果当前调用过程信息堆栈是空的,说明
        // 1. BEFORE/RETURN错位
        // 2. super.<init>
        // 这里统一的处理方式是直接返回, 不做任何事件的处理和代码流程的改变,放弃对super.<init>的观察，可惜了
        if (process.isEmptyStack()) {
            // 修复 #194 问题
            wrap.processRef.remove();
            return newInstanceForNone();
        }

        // 如果异常来自于 ImmediatelyException，则忽略处理直接返回抛异常
        final boolean isExceptionFromImmediately = !isReturn && process.rollingIsExceptionFromImmediately();
        if (isExceptionFromImmediately) {
            return newInstanceForThrows((Throwable) object);
        }

        // 继续异常处理
        final int processId = process.getProcessId();  // 调用过程id
        final int invokeId = process.popInvokeId();  // 在这里需要出栈invokeId，因为当前invoke已经结束了，需要对齐执行栈

        // 如果需要忽略事件处理，那么就不进行处理，直接返回RET_NONE即可，放在stack.popInvokeId()后边是为了对齐执行栈
        if (process.isIgnoreProcess()) {
            return newInstanceForNone();
        }

        // 如果processId==invokeId说明已经到栈顶，整个调用过程都已经结束
        // 此时需要核对堆栈是否为空。如果不为空需要输出日志进行告警
        if (checkProcessStack(processId, invokeId, process.isEmptyStack())) {
            logger.warn("ERROR process-stack. pid={};iid={};listener={};",
                    processId,
                    invokeId,
                    listenerId
            );
        }

        // 构造ReturnEvent 或 ThrowsEvent，并调用EventListenerHandler#handlerEvent进行事件的处理
        final Event event = isReturn
                ? process.getEventFactory().makeReturnEvent(processId, invokeId, object)
                : process.getEventFactory().makeThrowsEvent(processId, invokeId, (Throwable) object);
        try {
            return handleEvent(listenerId, processId, invokeId, event, wrap);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }


@Override
public void handleOnCallBefore(int listenerId, int lineNumber, String owner, String name, String desc) throws Throwable {

    // 在守护区内产生的事件不需要响应
    if (SandboxProtector.instance.isInProtecting()) {
        logger.debug("listener={} is in protecting, ignore processing call-before-event", listenerId);
        return;
    }

    // 根据事件监听器id来从mappingOfEventProcessor中获取到事件处理器
    final EventProcessor processor = mappingOfEventProcessor.get(listenerId);
    if (null == processor) {
        logger.debug("listener={} is not activated, ignore processing call-before-event.", listenerId);
        return;
    }

    // 从ThreadLocal中获取对应的调用过程信息，如果不存在，则会创建一个新的调用过程信息
    // 按理来说，这里是一定能够获取到调用过程信息的，因为在handleOnBefore中已经创建了调用过程信息，并向调用过程Process中push了一个invokeId
    final EventProcessor.Process process = processor.processRef.get();

    // 如果当前调用过程信息堆栈是空的,有两种情况
    // 1. CALL_BEFORE事件和BEFORE事件错位
    // 2. 当前方法是<init>，而CALL_BEFORE事件触发是当前方法在调用父类的<init>
    //    super.<init>会导致CALL_BEFORE事件优先于BEFORE事件
    // 但如果按照现在的架构要兼容这种情况，比较麻烦，所以暂时先放弃了这部分的消息，这里的统一的处理方式是直接返回, 不做任何事件的处理和代码流程的改变
    if (process.isEmptyStack()) {
        return;
    }

    final int processId = process.getProcessId();  // 调用过程ID
    final int invokeId = process.getInvokeId(); // 调用ID

    // 如果事件处理流被忽略，则直接返回，不产生后续事件
    if (process.isIgnoreProcess()) {
        return;
    }

    // 创建CallBeforeEvent，并调用EventListenerHandler#handleEvent进行事件的处理
    final Event event = process
            .getEventFactory()
            .makeCallBeforeEvent(processId, invokeId, lineNumber, owner, name, desc);
    try {
        handleEvent(listenerId, processId, invokeId, event, processor);
    } finally {
        process.getEventFactory().returnEvent(event);
    }
}

    @Override
    public void handleOnCallReturn(int listenerId) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (SandboxProtector.instance.isInProtecting()) {
            logger.debug("listener={} is in protecting, ignore processing call-return-event", listenerId);
            return;
        }

        // 根据事件监听器id来从mappingOfEventProcessor中获取到事件处理器
        final EventProcessor processor = mappingOfEventProcessor.get(listenerId);
        if (null == processor) {
            logger.debug("listener={} is not activated, ignore processing call-return-event.", listenerId);
            return;
        }

        // 从ThreadLocal中获取对应的调用过程信息，如果不存在，则会创建一个新的调用过程信息
        // 按理来说，这里是一定能够获取到调用过程信息的，因为在handleOnBefore中已经创建了调用过程信息，并向调用过程Process中push了一个invokeId
        final EventProcessor.Process process = processor.processRef.get();
        if (process.isEmptyStack()) {
            return;
        }

        final int processId = process.getProcessId();  // 调用过程ID
        final int invokeId = process.getInvokeId(); // 调用ID

        // 如果事件处理流被忽略，则直接返回，不产生后续事件
        if (process.isIgnoreProcess()) {
            return;
        }

        // 创建CallReturnEvent，并调用EventListenerHandler#handleEvent进行事件的处理
        final Event event = process
                .getEventFactory()
                .makeCallReturnEvent(processId, invokeId);
        try {
            handleEvent(listenerId, processId, invokeId, event, processor);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }

    @Override
    public void handleOnCallThrows(int listenerId, String throwException) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (SandboxProtector.instance.isInProtecting()) {
            logger.debug("listener={} is in protecting, ignore processing call-throws-event", listenerId);
            return;
        }

        // 根据事件监听器id来从mappingOfEventProcessor中获取到事件处理器
        final EventProcessor processor = mappingOfEventProcessor.get(listenerId);
        if (null == processor) {
            logger.debug("listener={} is not activated, ignore processing call-throws-event.", listenerId);
            return;
        }

        // 从ThreadLocal中获取对应的调用过程信息，如果不存在，则会创建一个新的调用过程信息
        // 按理来说，这里是一定能够获取到调用过程信息的，因为在handleOnBefore中已经创建了调用过程信息，并向调用过程Process中push了一个invokeId
        final EventProcessor.Process process = processor.processRef.get();
        if (process.isEmptyStack()) {
            return;
        }

        final int processId = process.getProcessId();  // 调用过程ID
        final int invokeId = process.getInvokeId(); // 调用ID

        // 如果事件处理流被忽略，则直接返回，不产生后续事件
        if (process.isIgnoreProcess()) {
            return;
        }

        // 创建CallThrowsEvent，并调用EventListenerHandler#handleEvent进行事件的处理
        final Event event = process
                .getEventFactory()
                .makeCallThrowsEvent(processId, invokeId, throwException);
        try {
            handleEvent(listenerId, processId, invokeId, event, processor);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }

    @Override
    public void handleOnLine(int listenerId, int lineNumber) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (SandboxProtector.instance.isInProtecting()) {
            logger.debug("listener={} is in protecting, ignore processing call-line-event", listenerId);
            return;
        }

        final EventProcessor wrap = mappingOfEventProcessor.get(listenerId);
        if (null == wrap) {
            logger.debug("listener={} is not activated, ignore processing line-event.", listenerId);
            return;
        }

        final EventProcessor.Process process = wrap.processRef.get();

        // 如果当前调用过程信息堆栈是空的,说明BEFORE/LINE错位
        // 处理方式是直接返回,不做任何事件的处理和代码流程的改变
        if (process.isEmptyStack()) {
            return;
        }

        final int processId = process.getProcessId();
        final int invokeId = process.getInvokeId();

        // 如果事件处理流被忽略，则直接返回，不产生后续事件
        if (process.isIgnoreProcess()) {
            return;
        }

        final Event event = process.getEventFactory().makeLineEvent(processId, invokeId, lineNumber);
        try {
            handleEvent(listenerId, processId, invokeId, event, wrap);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }

    // ---- 自检查
    public void checkEventProcessor(final int... listenerIds) {
        for (int listenerId : listenerIds) {
            final EventProcessor processor = mappingOfEventProcessor.get(listenerId);
            if (null == processor) {
                throw new IllegalStateException(String.format("listener=%s not existed.", listenerId));
            }
            processor.check();
        }
    }
}
