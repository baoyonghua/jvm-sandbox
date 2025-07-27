package com.alibaba.jvm.sandbox.core;

import com.alibaba.jvm.sandbox.core.manager.CoreModuleManager;
import com.alibaba.jvm.sandbox.core.manager.impl.DefaultCoreLoadedClassDataSource;
import com.alibaba.jvm.sandbox.core.manager.impl.DefaultCoreModuleManager;
import com.alibaba.jvm.sandbox.core.manager.impl.DefaultProviderManager;
import com.alibaba.jvm.sandbox.core.util.SandboxProtector;
import com.alibaba.jvm.sandbox.core.util.SpyUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱
 */
public class JvmSandbox {

    /**
     * 需要提前加载的sandbox工具类
     */
    private final static List<String> earlyLoadSandboxClassNameList = new ArrayList<>();

    static {
        earlyLoadSandboxClassNameList.add("com.alibaba.jvm.sandbox.core.util.SandboxClassUtils");
        earlyLoadSandboxClassNameList.add("com.alibaba.jvm.sandbox.core.util.matcher.structure.ClassStructureImplByAsm");
        earlyLoadSandboxClassNameList.add("com.alibaba.jvm.sandbox.core.enhance.weaver.EventListenerHandler");
    }

    /**
     * Sandbox核心配置, 承载着沙箱的配置信息
     */
    private final CoreConfigure cfg;

    /**
     * 沙箱模块管理器, 用于管理当前沙箱的所有模块
     */
    private final CoreModuleManager coreModuleManager;

    /**
     * 判断当前是否允许对 native 方法进行增强
     *
     * @param inst
     * @return
     */
    private boolean isNativeSupported(Instrumentation inst) {
        /*
        这段代码的作用是判断当前 Java 运行环境的版本号是否在支持的范围内（1.8 到 12 之间），以决定是否支持对 native 方法的增强：
            - `javaSpecVersion` 获取当前 JVM 的规范版本号（如 "1.8"、"11"、"17"）。
            - `NumberUtils.toFloat(javaSpecVersion, 999f) <= 12f` 检查版本号是否小于等于 12
            - `NumberUtils.toFloat(javaSpecVersion, -1f) >= 1.8f` 检查版本号是否大于等于 1.8
         即：只有在版本号在 1.8 到 12 之间时，`isSupportedJavaSpecVersion` 才为 true
         */
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final boolean isSupportedJavaSpecVersion = StringUtils.isNotBlank(javaSpecVersion)
                && NumberUtils.toFloat(javaSpecVersion, 999f) <= 12f
                && NumberUtils.toFloat(javaSpecVersion, -1f) >= 1.8f;

        // 最终判断是否启用Native
        return isSupportedJavaSpecVersion && inst.isNativeMethodPrefixSupported();
    }

    public JvmSandbox(final CoreConfigure cfg, final Instrumentation inst) {
        this.cfg = cfg;

        // 是否支持Native方法增强
        cfg.setNativeSupported(isNativeSupported(inst));

        // 创建模块管理器，它用于管理所有模块{@link Module}
        this.coreModuleManager = SandboxProtector.instance.protectProxy(CoreModuleManager.class, new DefaultCoreModuleManager(
                cfg,
                inst,
                new DefaultCoreLoadedClassDataSource(inst, cfg.isEnableUnsafe(), cfg.isNativeSupported()),
                new DefaultProviderManager(cfg)
        ));

        // 完成对Jvm Sandbox的初始化操作
        init();
    }

    private void init() {
        // 提交加载一些必要的类
        doEarlyLoadSandboxClass();
        // 初始化Spy
        SpyUtils.init(cfg.getNamespace());
    }

    /**
     * 提前加载某些必要的类
     */
    private void doEarlyLoadSandboxClass() {
        for (String className : earlyLoadSandboxClassNameList) {
            try {
                Class.forName(className);
            } catch (ClassNotFoundException e) {
                //加载sandbox内部的类，不可能加载不到
            }
        }
    }

    /**
     * 获取模块管理器
     *
     * @return 模块管理器
     */
    public CoreModuleManager getCoreModuleManager() {
        return coreModuleManager;
    }

    /**
     * 销毁沙箱
     */
    public void destroy() {

        // 卸载所有的模块
        coreModuleManager.unloadAll();

        // 清理Spy
        SpyUtils.clean(cfg.getNamespace());

    }

}
