package com.alibaba.jvm.sandbox.module.debug;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.annotation.Command;
import com.alibaba.jvm.sandbox.api.http.printer.ConcurrentLinkedQueuePrinter;
import com.alibaba.jvm.sandbox.api.http.printer.Printer;
import com.alibaba.jvm.sandbox.api.listener.ext.Advice;
import com.alibaba.jvm.sandbox.api.listener.ext.AdviceListener;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatcher;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.alibaba.jvm.sandbox.module.debug.textui.TTree;
import org.kohsuke.MetaInfServices;

import javax.annotation.Resource;
import java.io.PrintWriter;
import java.util.Map;

/**
 * 模仿Greys的trace命令
 * <p>测试用模块</p>
 */
@MetaInfServices(Module.class)
@Information(id = "debug-trace", version = "0.0.2", author = "luanjia@taobao.com")
public class DebugTraceModule extends ParamSupported implements Module {

    @Resource
    private ModuleEventWatcher moduleEventWatcher;


    @Command("trace")
    public void trace(final Map<String, String> param, final PrintWriter writer) {
        // 从HTTP请求参数中获取要进行追踪的类和方法的Pattern
        final String cnPattern = getParameter(param, "class");
        final String mnPattern = getParameter(param, "method");
        // 打印，会定期从队列中获取文本，并通过writer来输出在控制台上
        final Printer printer = new ConcurrentLinkedQueuePrinter(writer);

        final EventWatcher watcher = new EventWatchBuilder(moduleEventWatcher)
                // 匹配用户给定类
                .onClass(cnPattern)
                // 需要匹配子类
                .includeSubClasses()
                // 匹配用户给定的方法
                .onBehavior(mnPattern)
                // 通过onWatching方法来获取一个观察者构造器，以方便我们构造观察者
                .onWatching()
                // 需要进行方法调用的追踪，即：需要观察被观察方法内部的所有调用
                .withCall()
                // 进度输出器，通过Progress可以观察到当前的渲染进度
                .withProgress(new ProgressPrinter(printer))
                // 通过onWatch方法来指定Advice监听器 AdviceListener
                // 如果我们指定了AdviceListener，那么JVM-SANDBOX在底层会将Event转换为Advice后调用AdviceListener
                // Advice相较于Event更加友好
                .onWatch(new AdviceListener() {

                    /**
                     * 方法调用前的通知
                     * @param advice 通知信息
                     * @throws Throwable
                     */
                    @Override
                    protected void before(Advice advice) throws Throwable {
                        final TTree tTree;  // 树形打印组件，用于树状打印调用链
                        if (advice.isProcessTop()) {
                            // 当前是整个调用链路的最顶层通知Advice，因此打印顶层Title
                            String tracingTitle = "Tracing for : "
                                    + advice.getBehavior().getDeclaringClass().getName()
                                    + "."
                                    + advice.getBehavior().getName()
                                    + " by "
                                    + Thread.currentThread().getName();
                            tTree = new TTree(true, tracingTitle);
                            advice.attach(tTree);  // 将树形组件attach到Advice上，以便于在后续的内部方法调用中获取
                        } else {
                            // 非顶层通知，那么从顶层通知上获取树形打印组件
                            tTree = advice.getProcessTop().attachment();
                        }
                        // 打印Enter Title
                        String enterTitle = "Enter : "
                                + advice.getBehavior().getDeclaringClass().getName()
                                + "."
                                + advice.getBehavior().getName()
                                + "(...);";
                        tTree.begin(enterTitle);
                    }

                    /**
                     * 在方法调用返回后进行通知
                     * @param advice 通知信息
                     * @throws Throwable
                     */
                    @Override
                    protected void afterReturning(Advice advice) throws Throwable {
                        final TTree tTree = advice.getProcessTop().attachment();
                        tTree.end();
                        finish(advice);
                    }

                    /**
                     * 在方法抛出异常后进行通知
                     * @param advice 通知信息
                     * @throws Throwable
                     */
                    @Override
                    protected void afterThrowing(Advice advice) throws Throwable {
                        final TTree tTree = advice.getProcessTop().attachment();
                        tTree.begin("throw:" + advice.getThrowable().getClass().getName() + "()").end();
                        tTree.end();
                        finish(advice);
                    }

                    private void finish(Advice advice) {
                        if (advice.isProcessTop()) {
                            final TTree tTree = advice.attachment();
                            printer.println(tTree.rendering());
                        }
                    }

                    /**
                     * 在目标方法调用之前进行通知, 在这里的目标方法是方法内部所调用的方法，在一个方法调用过程中会调用其他的方法
                     * @param advice             Caller的行为通知
                     * @param callLineNum        调用发生的代码行(可能为-1，取决于目标编译代码的编译策略)
                     * @param callJavaClassName  调用目标类名
                     * @param callJavaMethodName 调用目标行为名称
                     * @param callJavaMethodDesc 调用目标行为描述
                     */
                    @Override
                    protected void beforeCall(final Advice advice,
                                              final int callLineNum,
                                              final String callJavaClassName,
                                              final String callJavaMethodName,
                                              final String callJavaMethodDesc) {
                        final TTree tTree = advice.getProcessTop().attachment();
                        tTree.begin(callJavaClassName + ":" + callJavaMethodName + "(@" + callLineNum + ")");
                    }

                    /**
                     * 在目标方法返回之后进行通知
                     * @param advice             Caller的行为通知
                     * @param callLineNum        调用发生的代码行(可能为-1，取决于目标编译代码的编译策略)
                     * @param callJavaClassName  调用目标类名
                     * @param callJavaMethodName 调用目标行为名称
                     * @param callJavaMethodDesc 调用目标行为描述
                     */
                    @Override
                    protected void afterCallReturning(final Advice advice,
                                                      final int callLineNum,
                                                      final String callJavaClassName,
                                                      final String callJavaMethodName,
                                                      final String callJavaMethodDesc) {
                        final TTree tTree = advice.getProcessTop().attachment();
                        tTree.end();
                    }

                    /**
                     * 在目标方法抛出异常后进行调用
                     * @param advice                 Caller的行为通知
                     * @param callLineNum            调用发生的代码行(可能为-1，取决于目标编译代码的编译策略)
                     * @param callJavaClassName      调用目标类名
                     * @param callJavaMethodName     调用目标行为名称
                     * @param callJavaMethodDesc     调用目标行为描述
                     * @param callThrowJavaClassName 调用目标异常类名
                     */
                    @Override
                    protected void afterCallThrowing(final Advice advice,
                                                     final int callLineNum,
                                                     final String callJavaClassName,
                                                     final String callJavaMethodName,
                                                     final String callJavaMethodDesc,
                                                     final String callThrowJavaClassName) {
                        final TTree tTree = advice.getProcessTop().attachment();
                        tTree.set(tTree.get() + "[throw " + callThrowJavaClassName + "]").end();
                    }

                });

        try {
            // 阻塞等待用户输入CTRL_C来中断追踪
            printer.println(String.format(
                    "tracing on [%s#%s].\nPress CTRL_C abort it!",
                    cnPattern,
                    mnPattern
            ));
            printer.waitingForBroken();
        } finally {
            watcher.onUnWatched();
        }

    }
}
