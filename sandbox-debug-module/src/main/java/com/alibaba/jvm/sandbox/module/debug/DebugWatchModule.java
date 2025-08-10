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
import com.alibaba.jvm.sandbox.module.debug.util.Express;
import org.apache.commons.lang3.EnumUtils;
import org.kohsuke.MetaInfServices;

import javax.annotation.Resource;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.jvm.sandbox.module.debug.DebugWatchModule.Trigger.*;

/**
 * 模仿Greys的watch命令
 * <p>测试用模块</p>
 *
 * @author luanjia@taobao.com
 */
@MetaInfServices(Module.class)
@Information(id = "debug-watch", version = "0.0.2", author = "luanjia@taobao.com")
public class DebugWatchModule extends ParamSupported implements Module {

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    @Command("watch")
    public void watch(final Map<String, String> param, final Map<String, String[]> params, final PrintWriter writer) {
        final String cnPattern = getParameter(param, "class");
        final String mnPattern = getParameter(param, "method");
        final String watchExpress = getParameter(param, "watch");  // 观察表达式
        // 获取用户所需要观察的触点：BEFORE, RETURN, THROWS
        final List<Trigger> triggers = getParameters(
                params,
                "at",
                string -> EnumUtils.getEnum(Trigger.class, string),
                Trigger.BEFORE);
        // 打印者，会定期从队列中获取文本，并通过writer来输出在控制台上
        final Printer printer = new ConcurrentLinkedQueuePrinter(writer);
        // 进行增强
        final EventWatcher watcher = new EventWatchBuilder(moduleEventWatcher)
                // 匹配用户给定类
                .onClass(cnPattern)
                // 需要匹配子类
                .includeSubClasses()
                // 需要匹配被引导类加载器所加载的类
                .includeBootstrap()
                // 匹配用户给定的方法
                .onBehavior(mnPattern)
                // 通过onWatching方法来获取一个观察者构造器，以方便我们构造观察者
                .onWatching()
                // 进度输出器，通过Progress可以观察到当前的渲染进度
                .withProgress(new ProgressPrinter(printer))
                // 通过onWatch方法来指定Advice监听器 AdviceListener
                .onWatch(new AdviceListener() {

                    @Override
                    public void before(final Advice advice) {
                        if (!triggers.contains(BEFORE)) {
                            return; // 如果用户没有指定BEFORE触点，则不进行处理
                        }
                        // binding方法用于将Advice中携带的class、method、params等信息提取出来并放置到Bind对象中（Bind是一个Map）
                        // Bind用于存储表达式绑定的变量，用于后续的表达式解析
                        Bind binding = binding(advice);
                        printlnByExpress(binding);  // 解析表达式并进行打印
                    }

                    @Override
                    public void afterReturning(final Advice advice) {
                        if (!triggers.contains(RETURN)) {
                            return;
                        }
                        // binding方法用于将Advice中携带的class、method、params等信息提取出来并放置到Bind对象中（Bind是一个Map）
                        // Bind用于存储表达式绑定的变量，用于后续的表达式解析
                        Bind binding = binding(advice);
                        binding.bind("return", advice.getReturnObj());
                        printlnByExpress(binding); // 解析表达式并进行打印
                    }

                    @Override
                    public void afterThrowing(final Advice advice) {
                        if (!triggers.contains(THROWS)) {
                            return;
                        }
                        // binding方法用于将Advice中携带的class、method、params等信息提取出来并放置到Bind对象中（Bind是一个Map）
                        // Bind用于存储表达式绑定的变量，用于后续的表达式解析
                        Bind binding = binding(advice);
                        binding.bind("throws", advice.getThrowable());
                        printlnByExpress(binding); // 解析表达式并进行打印
                    }

                    private Bind binding(Advice advice) {
                        return new Bind()
                                .bind("class", advice.getBehavior().getDeclaringClass())
                                .bind("method", advice.getBehavior())
                                .bind("params", advice.getParameterArray())
                                .bind("target", advice.getTarget());
                    }

                    private void printlnByExpress(final Bind bind) {
                        try {
                            // 通过OgnlExpress来解析表达式
                            final Object watchObject = Express.ExpressFactory.newExpress(bind).get(watchExpress);
                            printer.println(watchObject == null ? "null" : watchObject.toString());
                        } catch (Express.ExpressException e) {
                            printer.println(String.format("express: %s was wrong! msg:%s.", watchExpress, e.getMessage()));
                        }

                    }

                });
        try {
            // 用户输入CTRL_C来中断观察
            printer.println(String.format(
                    "watching on [%s#%s], at %s, watch:%s.\nPress CTRL_C abort it!",
                    cnPattern,
                    mnPattern,
                    triggers,
                    watchExpress
            ));
            printer.waitingForBroken();
        } finally {
            // // 删除事件观察者（在这个过程中会对已增强的类进行还原）
            watcher.onUnWatched();
        }
    }

    /**
     * 观察触点
     */
    enum Trigger {
        BEFORE,
        RETURN,
        THROWS
    }

    static class Bind extends HashMap<String, Object> {
        Bind bind(final String name, final Object value) {
            put(name, value);
            return this;
        }
    }
}
