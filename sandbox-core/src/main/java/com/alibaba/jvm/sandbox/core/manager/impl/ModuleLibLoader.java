package com.alibaba.jvm.sandbox.core.manager.impl;

import com.alibaba.jvm.sandbox.api.Information;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;

import static org.apache.commons.io.FileUtils.convertFileCollectionToFileArray;
import static org.apache.commons.io.FileUtils.listFiles;
import static org.apache.commons.lang3.StringUtils.join;

/**
 * 模块目录加载器
 * 用于从${module.lib}中加载所有的沙箱模块
 * Created by luanjia@taobao.com on 2016/11/17.
 */
class ModuleLibLoader {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    // 模块加载目录
    private final File moduleLibDir;

    // 沙箱加载模式
    private final Information.Mode mode;

    ModuleLibLoader(final File moduleLibDir,
                    final Information.Mode mode) {
        this.moduleLibDir = moduleLibDir;
        this.mode = mode;
    }

    /**
     * 将moduleLibDir(模块lib目录)转换为模块jar文件数组
     *
     * @return
     */
    private File[] toModuleJarFileArray() {
        if (moduleLibDir.exists()
                && moduleLibDir.isFile()
                && moduleLibDir.canRead()
                && StringUtils.endsWith(moduleLibDir.getName(), ".jar")) {
            // 给定的moduleLibDir是一个jar文件，直接返回它就好了
            return new File[]{moduleLibDir};
        } else {
            // 给定的moduleLibDir是一个目录，则返回该目录下的所有jar文件
            return convertFileCollectionToFileArray(
                    listFiles(moduleLibDir, new String[]{"jar"}, false)
            );
        }
    }


    /**
     * 列出模块lib目录下的所有模块jar文件
     * <p>
     * 如果moduleLibDir是一个jar文件，则直接返回该jar文件
     * </p>
     *
     * @return 模块jar文件数组
     */
    private File[] listModuleJarFileInLib() {
        final File[] moduleJarFileArray = toModuleJarFileArray();
        Arrays.sort(moduleJarFileArray);
        logger.info("loading module-lib={}, found {} module-jar files : {}",
                moduleLibDir,
                moduleJarFileArray.length,
                join(moduleJarFileArray, ",")
        );
        return moduleJarFileArray;
    }

    /**
     * 加载Module
     *
     * @param mjCb 模块jar文件加载回调，当一个jar文件被加载时会回调该方法
     * @param mCb  模块加载回调, 当jar文件下具体的一个模块被加载时会回调该方法
     */
    void load(final ModuleJarLoadCallback mjCb, final ModuleJarLoader.ModuleLoadCallback mCb) {
        // 开始逐条加载
        for (final File moduleJarFile : listModuleJarFileInLib()) {
            try {
                // ModuleJarLoadCallback 加载回调 -> 一个模块jar包下可能有多个模块Module
                mjCb.onLoad(moduleJarFile);
                // 正式加载jar文件下的模块，每加载一个模块都会回调ModuleLoadCallback
                new ModuleJarLoader(moduleJarFile, mode).load(mCb);
            } catch (Throwable cause) {
                logger.warn("loading module-jar occur error! module-jar={};", moduleJarFile, cause);
            }
        }
    }

    /**
     * 模块文件加载回调
     */
    public interface ModuleJarLoadCallback {

        /**
         * 模块文件加载回调
         *
         * @param moduleJarFile 模块文件
         * @throws Throwable 加载回调异常
         */
        void onLoad(File moduleJarFile) throws Throwable;

    }

}
