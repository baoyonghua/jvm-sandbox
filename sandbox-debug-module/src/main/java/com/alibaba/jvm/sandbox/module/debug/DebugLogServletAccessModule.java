package com.alibaba.jvm.sandbox.module.debug;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.LoadCompleted;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.listener.ext.Advice;
import com.alibaba.jvm.sandbox.api.listener.ext.AdviceListener;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.alibaba.jvm.sandbox.module.debug.util.InterfaceProxyUtils;
import com.alibaba.jvm.sandbox.module.debug.util.InterfaceProxyUtils.ProxyMethod;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.alibaba.jvm.sandbox.module.debug.util.InterfaceProxyUtils.puppet;
import static org.apache.commons.lang3.ArrayUtils.contains;

/**
 * 基于HTTP-SERVLET(v2.4)规范的HTTP访问日志
 *
 * @author luanjia@taobao.com
 */
@MetaInfServices(Module.class)
@Information(id = "debug-servlet-access", version = "0.0.2", author = "luanjia@taobao.com")
public class DebugLogServletAccessModule implements Module, LoadCompleted {

    private final Logger logger = LoggerFactory.getLogger("DEBUG-SERVLET-ACCESS");

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    /**
     * HTTP接入信息
     */
    static class HttpAccess {
        final long beginTimestamp = System.currentTimeMillis();
        final String from;
        final String method;
        final String uri;
        final Map<String, String[]> parameterMap;
        final String userAgent;
        int status = 200;

        HttpAccess(final String from,
                   final String method,
                   final String uri,
                   final Map<String, String[]> parameterMap,
                   final String userAgent) {
            this.from = from;
            this.method = method;
            this.uri = uri;
            this.parameterMap = parameterMap;
            this.userAgent = userAgent;
        }

        void setStatus(int status) {
            this.status = status;
        }

    }

    interface IHttpServletRequest {

        @ProxyMethod(name = "getRemoteAddr")
        String getRemoteAddress();

        String getMethod();

        String getRequestURI();

        Map<String, String[]> getParameterMap();

        String getHeader(String name);

    }

    /**
     * 该方法在Module加载完成后进行调度
     */
    @Override
    public void loadCompleted() {
        new EventWatchBuilder(moduleEventWatcher)
                // 匹配javax.servlet.http.HttpServlet类
                .onClass("javax.servlet.http.HttpServlet")
                // 是否需要匹配子类
                .includeSubClasses()
                // 匹配service方法
                .onBehavior("service")
                // 需要匹配的方法的方法参数类型，这适用于重载的方法
                .withParameterTypes(
                        "javax.servlet.http.HttpServletRequest",
                        "javax.servlet.http.HttpServletResponse"
                )
                // 通过onWatch方法来指定Advice监听器 AdviceListener
                // 如果我们指定了AdviceListener，那么JVM-SANDBOX在底层会将Event转换为Advice后调用AdviceListener
                // Advice相较于Event更加友好
                .onWatch(new AdviceListener() {

                    /**
                     * 在方法调用之前进行通知
                     * <p>
                     *     在这里也就是在调用{@code HttpServlet#service}方法前进行通知
                     * </p>
                     * @param advice 通知信息
                     * @throws Throwable
                     */
                    @Override
                    protected void before(Advice advice) throws Throwable {
                        if (!advice.isProcessTop()) {
                            return; // 对于这个场景，我们只关心顶层调用
                        }
                        // HttpAccess代表着一次HTTP访问，在HttpAccess中记录了访问的起始时间、来源IP、HTTP方法、URI、参数Map等信息
                        final HttpAccess httpAccess = wrapperHttpAccess(advice);
                        // 附加到advice上，以便在onReturning()和onThrowing()中取出
                        advice.attach(httpAccess);
                        // advice.getBehavior() -> 获取触发当前事件的行为，在这里的行为也就是service方法
                        final Class<?> classOfHttpServletResponse = advice.getBehavior()
                                .getDeclaringClass()
                                .getClassLoader()
                                .loadClass("javax.servlet.http.HttpServletResponse");

                        // 通过Advice#changeParamter()方法来改变方法的入参，
                        // 在这里也就是替换service方法的第2个参数HttpServletResponse为一个JDK代理对象
                        advice.changeParameter(
                                1,
                                // InterfaceProxyUtils.intercept: 创建一个JDK动态代理对象，该代理对象的作用就是拦截方法的调用
                                InterfaceProxyUtils.intercept(
                                        classOfHttpServletResponse,  // 目标接口
                                        advice.getTarget().getClass().getClassLoader(), // 目标类的类加载器
                                        advice.getParameterArray()[1],  // 目标实例，在这里也就是HttpServletResponse实例
                                        // 拦截方法调用
                                        methodInvocation -> {
                                            // 如果调用的方法为setStatus、sendError则变更HttpAccess对象的状态<例如：400、500、...>
                                            String methodName = methodInvocation.getMethod().getName();
                                            String[] expected = {"setStatus", "sendError"};
                                            if (contains(expected, methodName)) {
                                                httpAccess.setStatus((Integer) methodInvocation.getArguments()[0]);
                                            }
                                            // 调用目标方法
                                            return methodInvocation.proceed();
                                        }));
                    }

                    /**
                     * 在方法调用返回后进行通知
                     * <p>
                     *     在这里也就是在调用{@code HttpServlet#service}方法返回后进行通知
                     * </p>
                     * @param advice 通知信息
                     */
                    @Override
                    protected void afterReturning(Advice advice) {
                        // 只关心顶层调用
                        if (!advice.isProcessTop()) {
                            return;
                        }
                        // 获取attach在Advice上的附件，这里的附件是在 AdviceListener#before 方法中进行attach的
                        final HttpAccess httpAccess = advice.attachment();
                        if (null == httpAccess) {
                            return;
                        }
                        // 记录HTTP访问日志
                        long cost = System.currentTimeMillis() - httpAccess.beginTimestamp;
                        logAccess(httpAccess, cost, null);
                    }

                    /**
                     * 在方法抛出异常后进行通知
                     * <p>
                     *     在这里也就是在调用{@code HttpServlet#service}方法抛出异常后进行通知
                     * </p>
                     * @param advice 通知信息
                     */
                    @Override
                    protected void afterThrowing(Advice advice) {
                        // 只关心顶层调用
                        if (!advice.isProcessTop()) {
                            return;
                        }
                        // 获取attach在Advice上的附件，这里的附件是在 AdviceListener#before 方法中进行attach的
                        final HttpAccess httpAccess = advice.attachment();
                        if (null == httpAccess) {
                            return;
                        }
                        // 记录HTTP访问日志
                        long cost = System.currentTimeMillis() - httpAccess.beginTimestamp;
                        logAccess(httpAccess, cost, advice.getThrowable());
                    }
                });
    }

    /**
     * 根据http请求包装一个HttpAccess模型
     * <p>
     * 通过代理模式{@link com.alibaba.jvm.sandbox.module.debug.util.InterfaceProxyUtils#puppet(Class, Object)} }调用
     * 需要封装很多傀儡接口对象进行调用，对一些复杂场景（例如：序列化/反序列化一些业务模型）使用会有局限
     *
     * @param advice 事件行为通知
     * @return 包装HttpAccess
     */
    protected HttpAccess wrapperHttpAccess(Advice advice) {

        // 俘虏HttpServletRequest参数为傀儡
        final IHttpServletRequest httpServletRequest = puppet(
                IHttpServletRequest.class,
                advice.getParameterArray()[0]  // HttpServletRequest
        );

        // 初始化HttpAccess
        return new HttpAccess(
                httpServletRequest.getRemoteAddress(),
                httpServletRequest.getMethod(),
                httpServletRequest.getRequestURI(),
                httpServletRequest.getParameterMap(),
                httpServletRequest.getHeader("User-Agent")
        );
    }

    // 格式化ParameterMap
    private static String formatParameterMap(final Map<String, String[]> parameterMap) {
        if (MapUtils.isEmpty(parameterMap)) {
            return StringUtils.EMPTY;
        }
        final Set<String> kvPairs = new LinkedHashSet<>();
        for (final Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            kvPairs.add(String.format("%s=%s",
                    entry.getKey(),
                    StringUtils.join(entry.getValue(), ",")
            ));
        }
        return StringUtils.join(kvPairs, "&");
    }


    /*
     * 记录access日志
     */
    private void logAccess(final HttpAccess ha,
                           final long costMs,
                           final Throwable cause) {
        logger.info("{};{};{};{}ms;{};[{}];{};",
                ha.from,
                ha.status,
                ha.method,
                costMs,
                ha.uri,
                formatParameterMap(ha.parameterMap),
                ha.userAgent,
                cause
        );
    }
}
