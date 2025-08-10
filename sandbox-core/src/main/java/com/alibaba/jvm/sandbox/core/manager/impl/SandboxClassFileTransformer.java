package com.alibaba.jvm.sandbox.core.manager.impl;

import com.alibaba.jvm.sandbox.api.event.Event;
import com.alibaba.jvm.sandbox.api.event.Event.Type;
import com.alibaba.jvm.sandbox.api.listener.EventListener;
import com.alibaba.jvm.sandbox.core.enhance.EventEnhancer;
import com.alibaba.jvm.sandbox.core.util.ObjectIDs;
import com.alibaba.jvm.sandbox.core.util.SandboxClassUtils;
import com.alibaba.jvm.sandbox.core.util.SandboxProtector;
import com.alibaba.jvm.sandbox.core.util.matcher.Matcher;
import com.alibaba.jvm.sandbox.core.util.matcher.MatchingResult;
import com.alibaba.jvm.sandbox.core.util.matcher.UnsupportedMatcher;
import com.alibaba.jvm.sandbox.core.util.matcher.structure.ClassStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

import static com.alibaba.jvm.sandbox.core.util.matcher.structure.ClassStructureFactory.createClassStructure;

/**
 * 沙箱类形变器
 * <p>
 * 该类形变器用于增强类的字节码，它实现了ClassFileTransformer接口，
 * 因此可以通过{@link #transform(ClassLoader, String, Class, ProtectionDomain, byte[])}方法来在运行时对类进行修改
 * </p>
 *
 * @author luanjia@taobao.com
 */
public class SandboxClassFileTransformer implements ClassFileTransformer {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * SANDBOX限定前缀
     */
    public static final String SANDBOX_SPECIAL_PREFIX = "$$SANDBOX$";

    private final int watchId;

    private final String uniqueId;

    /**
     * 匹配器
     * <p>
     * 该匹配器用于匹配类结构，判断是否符合增强条件
     * </p>
     */
    private final Matcher matcher;

    /**
     * 事件监听器
     */
    private final EventListener eventListener;

    /**
     * 是否允许增强由BootstrapClassLoader加载的类
     */
    private final boolean isEnableUnsafe;

    /**
     * 当前事件监听器可监听的事件类型数组
     */
    private final Event.Type[] eventTypeArray;

    private final String namespace;

    /**
     * 事件监听器id
     */
    private final int listenerId;

    /**
     * 增强类的统计器
     */
    private final AffectStatistic affectStatistic = new AffectStatistic();

    /**
     * 是否支持对native方法进行增强
     */
    private final boolean isNativeSupported;

    /**
     * 增强后的native方法前缀
     */
    private final String nativePrefix;

    SandboxClassFileTransformer(final int watchId,
                                final String uniqueId,
                                final Matcher matcher,
                                final EventListener eventListener,
                                final boolean isEnableUnsafe,
                                final Type[] eventTypeArray,
                                final String namespace,
                                final boolean isNativeSupported) {
        this.watchId = watchId;
        this.uniqueId = uniqueId;
        this.matcher = matcher;
        this.eventListener = eventListener;
        this.isEnableUnsafe = isEnableUnsafe;
        this.eventTypeArray = eventTypeArray;
        this.namespace = namespace;
        this.listenerId = ObjectIDs.instance.identity(eventListener);
        this.isNativeSupported = isNativeSupported;
        this.nativePrefix = String.format("%s$%s$%s", SANDBOX_SPECIAL_PREFIX, namespace, watchId);
    }

    /**
     * 转换给定的类文件并返回新的替换类文件
     *
     * @param loader              将要被转换的类的类加载器，如果使用引导加载器，则可能为 {@code null}。
     * @param internalClassName   Java 虚拟机规范中定义的完全限定类和接口名称的内部形式的类名称。例如， "java/util/List".
     * @param classBeingRedefined 如果这是由redefine或retransform触发的，那么被redefined或retransformed的类；如果这是class load，则为 {@code null}。
     * @param protectionDomain    the protection domain of the class being defined or redefined
     * @param srcByteCodeArray    the input byte buffer in class file format - must not be modified
     * @return 如果类被转换，则返回一个新的字节数组，包含转换后的类文件；如果类未被转换，则返回 {@code null}。
     */
    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String internalClassName,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] srcByteCodeArray
    ) {

        SandboxProtector.instance.enterProtecting();
        try {

            // 这里过滤掉Sandbox所需要的类|来自SandboxClassLoader所加载的类|来自ModuleJarClassLoader加载的类
            // 防止ClassCircularityError的发生
            if (SandboxClassUtils.isComeFromSandboxFamily(internalClassName, loader)) {
                return null;
            }

            // 如果未开启unsafe开关，是不允许增强来自BootStrapClassLoader的类，因此这里直接返回null，不进行增强
            if (!isEnableUnsafe && null == loader) {
                logger.debug("transform ignore {}, class from bootstrap but unsafe.enable=false.", internalClassName);
                return null;
            }

            // 匹配当前类是否符合形变要求，如果类或者类中的一个行为(方法)都没匹配上也不用继续了，直接return null，不进行增强
            final MatchingResult result = new UnsupportedMatcher(loader, isEnableUnsafe, isNativeSupported)
                    .and(matcher)
                    .matching(getClassStructure(loader, classBeingRedefined, srcByteCodeArray));
            if (!result.isMatched()) {
                logger.debug("transform ignore {}, no behaviors matched in loader={}", internalClassName, loader);
                return null;
            }

            // 【核心】开始正式增强
            return _transform(result, loader, internalClassName, srcByteCodeArray);
        } catch (Throwable cause) {
            logger.warn("sandbox transform {} in loader={}; failed, module={} at watch={}, will ignore this transform.",
                    internalClassName,
                    loader,
                    uniqueId,
                    watchId,
                    cause
            );
            return null;
        } finally {
            SandboxProtector.instance.exitProtecting();
        }
    }

    /**
     * 获取给定类的类结构信息
     *
     * @param loader
     * @param classBeingRedefined
     * @param srcByteCodeArray
     * @return
     */
    private ClassStructure getClassStructure(
            final ClassLoader loader,
            final Class<?> classBeingRedefined,
            final byte[] srcByteCodeArray) {
        return null == classBeingRedefined
                ? createClassStructure(srcByteCodeArray, loader)
                : createClassStructure(classBeingRedefined);
    }

    /**
     * 进行类增强
     *
     * @param result            匹配结果
     * @param loader            类加载器
     * @param internalClassName 内部类名
     * @param srcByteCodeArray  源字节码数组
     * @return 增强后的字节码数组，如果没有变化则返回null
     */
    private byte[] _transform(
            final MatchingResult result,
            final ClassLoader loader,
            final String internalClassName,
            final byte[] srcByteCodeArray
    ) {
        // 通过 匹配结果MatchingResult 来获取匹配到的方法签名
        final Set<String> behaviorSignCodes = result.getBehaviorSignCodes();

        try {
            // 通过EventEnhancer#toByteCodeArray方法来进行类的增强，会基于ASM完成对字节码的增强
            // toByteCodeArray方法会返回一个新的字节码数组
            final byte[] toByteCodeArray = new EventEnhancer(nativePrefix).toByteCodeArray(
                    loader,
                    srcByteCodeArray,
                    behaviorSignCodes,
                    namespace,
                    listenerId,
                    eventTypeArray
            );
            if (srcByteCodeArray == toByteCodeArray) {
                logger.debug("transform ignore {}, nothing changed in loader={}", internalClassName, loader);
                return null;
            }

            // 统计本次增强的影响范围(即：统计增强了哪些方法，以及哪个类被增强了)
            affectStatistic.statisticAffect(loader, internalClassName, behaviorSignCodes);

            logger.info("transform {} finished, by module={} in loader={}", internalClassName, uniqueId, loader);
            return toByteCodeArray;
        } catch (Throwable cause) {
            logger.warn("transform {} failed, by module={} in loader={}", internalClassName, uniqueId, loader, cause);
            return null;
        }
    }


    /**
     * 获取观察ID
     *
     * @return 观察ID
     */
    int getWatchId() {
        return watchId;
    }

    /**
     * 获取事件监听器
     *
     * @return 事件监听器
     */
    EventListener getEventListener() {
        return eventListener;
    }

    /**
     * 获取事件监听器ID
     *
     * @return 事件监听器ID
     */
    int getListenerId() {
        return listenerId;
    }

    /**
     * 获取本次匹配器
     *
     * @return 匹配器
     */
    Matcher getMatcher() {
        return matcher;
    }

    /**
     * 获取本次监听事件类型数组
     *
     * @return 本次监听事件类型数组
     */
    Event.Type[] getEventTypeArray() {
        return eventTypeArray;
    }

    /**
     * 获取本次增强的影响统计
     *
     * @return 本次增强的影响统计
     */
    public AffectStatistic getAffectStatistic() {
        return affectStatistic;
    }

    /**
     * 获取本次增强的native方法前缀，
     * 根据JVM规范，每个ClassFileTransformer必须拥有自己的native方法前缀
     *
     * @return native方法前缀
     */
    public String getNativePrefix() {
        return nativePrefix;
    }

}
