package com.alibaba.jvm.sandbox.module.debug;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.LoadCompleted;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.event.BeforeEvent;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;

import static com.alibaba.jvm.sandbox.api.event.Event.Type.BEFORE;
import static com.alibaba.jvm.sandbox.api.util.GaStringUtils.getJavaClassName;

/**
 * 异常类创建日志
 *
 * @author luanjia@taobao.com
 */
@MetaInfServices(Module.class)
@Information(id = "debug-exception-logger", version = "0.0.2", author = "luanjia@taobao.com")
public class DebugLogExceptionModule implements Module, LoadCompleted {

    private final Logger exLogger = LoggerFactory.getLogger("DEBUG-EXCEPTION-LOGGER");

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    @Override
    public void loadCompleted() {
        new EventWatchBuilder(moduleEventWatcher)
                // 匹配特定的类，在这里需要进行匹配的类是: java.lang.Exception
                .onClass(Exception.class)
                // 是否需要包含被引导类加载器所加载的类，由于我们这里匹配的类是Exception, 自然需要包含
                .includeBootstrap()
                // 匹配特定的行为<方法>, 在这里我们需要进行匹配的方法是<init>方法，也就是Exception类的构造器
                .onBehavior("<init>")
                // 通过onWatch方法来指定事件监听器 EventListener, 并指定当前需要观察的事件
                // 当后续事件触发后，会回调此监听器的onWatch方法，以完成对事件的处理
                .onWatch(event -> {
                    final BeforeEvent bEvent = (BeforeEvent) event;
                    // 在这里的EventListener的处理逻辑中，只是简单打印了下日志信息
                    exLogger.info("{} occur an exception: {}",
                            getJavaClassName(bEvent.target.getClass()),
                            bEvent.target
                    );
                }, BEFORE);
    }
}
