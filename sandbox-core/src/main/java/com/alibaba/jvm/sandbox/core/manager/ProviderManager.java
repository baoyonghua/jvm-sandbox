package com.alibaba.jvm.sandbox.core.manager;

import com.alibaba.jvm.sandbox.provider.api.ModuleJarLoadingChain;
import com.alibaba.jvm.sandbox.provider.api.ModuleLoadingChain;

/**
 * 服务提供管理器
 *
 * <p>
 * 通过SPI机制来加载所有的ModuleJarLoadingChain & ModuleLoadingChain，
 * 以便于在加载模块jar文件以及具体的模块时，能够以链的方式对模块jar文件/模块进行处理
 * </p>
 *
 * @author luanjia@taobao.com
 */
public interface ProviderManager extends ModuleJarLoadingChain, ModuleLoadingChain {


}
