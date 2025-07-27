package com.alibaba.jvm.sandbox.core.manager.impl;

import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.resource.ConfigInfo;
import com.alibaba.jvm.sandbox.core.CoreConfigure;
import com.alibaba.jvm.sandbox.core.classloader.ProviderClassLoader;
import com.alibaba.jvm.sandbox.core.manager.ProviderManager;
import com.alibaba.jvm.sandbox.provider.api.ModuleJarLoadingChain;
import com.alibaba.jvm.sandbox.provider.api.ModuleLoadingChain;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ServiceLoader;

/**
 * 默认服务提供管理器实现
 * <p>
 * 基于Java SPI 来加载所有的{@link ModuleJarLoadingChain} 和 {@link ModuleLoadingChain} 实现，
 * 以便于在{@link #loading(File)} 和 {@link #loading(String, Class, Module, File, ClassLoader)} 方法中对加载到的模块jar和模块进行处理
 * </p>
 *
 * @author luanjia@taobao.com
 */
public class DefaultProviderManager implements ProviderManager {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 模块jar加载链
     */
    private final Collection<ModuleJarLoadingChain> moduleJarLoadingChains = new ArrayList<>();

    /**
     * 模块加载连
     */
    private final Collection<ModuleLoadingChain> moduleLoadingChains = new ArrayList<>();

    private final CoreConfigure cfg;

    public DefaultProviderManager(final CoreConfigure cfg) {
        this.cfg = cfg;
        try {
            init(cfg);
        } catch (Throwable cause) {
            logger.warn("loading sandbox's provider-lib[{}] failed.", cfg.getProviderLibPath(), cause);
        }
    }

    private void init(final CoreConfigure cfg) {
        final File providerLibDir = new File(cfg.getProviderLibPath());
        if (!providerLibDir.exists() || !providerLibDir.canRead()) {
            logger.warn("loading provider-lib[{}] was failed, doest existed or access denied.", providerLibDir);
            return;
        }

        for (final File providerJarFile : FileUtils.listFiles(providerLibDir, new String[]{"jar"}, false)) {

            try {
                // 创建ProviderClassLoader，该类加载器用于加载服务提供者的Jar文件
                final ProviderClassLoader providerClassLoader = new ProviderClassLoader(
                        providerJarFile,
                        // SandboxClassLoader
                        getClass().getClassLoader()
                );

                // load ModuleJarLoadingChain 通过SPI来获取到所有的ModuleJarLoadingChain实现，并将其添加到moduleJarLoadingChains中
                inject(moduleJarLoadingChains, ModuleJarLoadingChain.class, providerClassLoader, providerJarFile);

                // load ModuleLoadingChain 通过SPI来获取到所有的ModuleLoadingChain实现，并将其添加到moduleLoadingChains中
                inject(moduleLoadingChains, ModuleLoadingChain.class, providerClassLoader, providerJarFile);

                logger.info("loading provider-jar[{}] was success.", providerJarFile);
            } catch (IllegalAccessException cause) {
                logger.warn("loading provider-jar[{}] occur error, inject provider resource failed.", providerJarFile, cause);
            } catch (IOException ioe) {
                logger.warn("loading provider-jar[{}] occur error, ignore load this provider.", providerJarFile, ioe);
            }

        }

    }

    private <T> void inject(final Collection<T> collection,
                            final Class<T> clazz,
                            final ClassLoader providerClassLoader,
                            final File providerJarFile) throws IllegalAccessException {
        // 通过SPI来获取到所有的服务提供者实现
        final ServiceLoader<T> serviceLoader = ServiceLoader.load(clazz, providerClassLoader);
        for (final T provider : serviceLoader) {
            injectResource(provider);
            collection.add(provider);
            logger.info("loading provider[{}] was success from provider-jar[{}], impl={}",
                    clazz.getName(), providerJarFile, provider.getClass().getName());
        }
    }

    private void injectResource(final Object provider) throws IllegalAccessException {
        // 获取该提供者类中所有被@Resource注解标记的字段，然后进行注入
        final Field[] resourceFieldArray = FieldUtils.getFieldsWithAnnotation(provider.getClass(), Resource.class);
        if (ArrayUtils.isEmpty(resourceFieldArray)) {
            return;
        }
        for (final Field resourceField : resourceFieldArray) {
            final Class<?> fieldType = resourceField.getType();
            // ConfigInfo注入
            if (ConfigInfo.class.isAssignableFrom(fieldType)) {
                final ConfigInfo configInfo = new DefaultConfigInfo(cfg);
                FieldUtils.writeField(resourceField, provider, configInfo, true);
            }
        }
    }

    /**
     * 加载给定的模块jar文件
     *
     * @param moduleJarFile 期待被加载模块Jar文件
     * @throws Throwable
     */
    @Override
    public void loading(final File moduleJarFile) throws Throwable {
        // 以链的方式对加载到的模块Jar文件进行处理
        for (final ModuleJarLoadingChain chain : moduleJarLoadingChains) {
            chain.loading(moduleJarFile);
        }
    }

    /**
     * 加载模块
     *
     * @param uniqueId          模块ID
     * @param moduleClass       模块类
     * @param module            模块实例
     * @param moduleJarFile     模块所在Jar文件
     * @param moduleClassLoader 负责加载模块的ClassLoader
     * @throws Throwable
     */
    @Override
    public void loading(final String uniqueId,
                        final Class moduleClass,
                        final Module module,
                        final File moduleJarFile,
                        final ClassLoader moduleClassLoader) throws Throwable {
        // 以链的方式对加载到的模块进行处理
        for (final ModuleLoadingChain chain : moduleLoadingChains) {
            chain.loading(
                    uniqueId,
                    moduleClass,
                    module,
                    moduleJarFile,
                    moduleClassLoader
            );
        }
    }
}
