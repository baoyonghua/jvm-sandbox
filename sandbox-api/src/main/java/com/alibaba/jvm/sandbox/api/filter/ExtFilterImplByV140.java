package com.alibaba.jvm.sandbox.api.filter;

/**
 * 增强过滤器V140实现，根据#292实现
 *
 * @since {@code sandbox-api:1.4.0}
 */
public class ExtFilterImplByV140 implements ExtFilter {

    /**
     * 被代理增强的目标过滤器
     */
    private final ExtFilter target;

    /**
     * 是否需要接口类型
     * <p>
     *     如果为true, 那么在过滤时会传入当前类所实现的所有接口以基于这些接口类型来进行过滤操作
     * </p>
     */
    private final boolean isHasInterfaceTypes;

    /**
     * 是否需要过滤注解类型
     * <p>
     *     如果为true, 那么在过滤时会传入当前类所拥有的所有注解类型以基于这些注解类型来进行过滤操作
     * </p>
     */
    private final boolean isHasAnnotationTypes;

    /**
     * 是否需要方法参数类型
     * <p>
     *     如果为true, 那么在过滤时会传入当前方法的所有参数类型以基于这些参数类型来进行过滤操作
     * </p>
     */
    private final boolean isBehaviorHasWithParameterTypes;

    /**
     * 是否需要方法异常类型
     * <p>
     *     如果为true, 那么在过滤时会传入当前方法的所有异常类型以基于这些异常类型来进行过滤操作
     * </p>
     */
    private final boolean isBehaviorHasExceptionTypes;

    /**
     * 是否需要方法注解类型
     * <p>
     *     如果为true, 那么在过滤时会传入当前方法的所有注解类型以基于这些注解类型来进行过滤操作
     * </p>
     */
    private final boolean isBehaviorHasAnnotationTypes;

    /**
     * 增强过滤器V140实现
     *
     * @param target                          代理增强过滤器目标
     * @param isHasInterfaceTypes             是否需要过滤接口类型
     * @param isHasAnnotationTypes            是否需要过滤注解类型
     * @param isBehaviorHasWithParameterTypes 是否需要方法参数类型
     * @param isBehaviorHasExceptionTypes     是否需要方法异常类型
     * @param isBehaviorHasAnnotationTypes    是否需要方法注解类型
     */
    public ExtFilterImplByV140(ExtFilter target,
                               boolean isHasInterfaceTypes, boolean isHasAnnotationTypes,
                               boolean isBehaviorHasWithParameterTypes, boolean isBehaviorHasExceptionTypes, boolean isBehaviorHasAnnotationTypes) {
        this.target = target;
        this.isHasInterfaceTypes = isHasInterfaceTypes;
        this.isHasAnnotationTypes = isHasAnnotationTypes;
        this.isBehaviorHasWithParameterTypes = isBehaviorHasWithParameterTypes;
        this.isBehaviorHasExceptionTypes = isBehaviorHasExceptionTypes;
        this.isBehaviorHasAnnotationTypes = isBehaviorHasAnnotationTypes;
    }

    /**
     * 是否需要过滤接口类型
     *
     * @return TRUE | FALSE
     */
    public boolean isHasInterfaceTypes() {
        return isHasInterfaceTypes;
    }

    /**
     * 是否需要过滤注解类型
     *
     * @return TRUE | FALSE
     */
    public boolean isHasAnnotationTypes() {
        return isHasAnnotationTypes;
    }

    /**
     * 是否需要方法参数类型
     *
     * @return TRUE | FALSE
     */
    public boolean isBehaviorHasWithParameterTypes() {
        return isBehaviorHasWithParameterTypes;
    }

    /**
     * 是否需要方法异常类型
     *
     * @return TRUE | FALSE
     */
    public boolean isBehaviorHasExceptionTypes() {
        return isBehaviorHasExceptionTypes;
    }

    /**
     * 是否需要方法注解类型
     *
     * @return TRUE | FALSE
     */
    public boolean isBehaviorHasAnnotationTypes() {
        return isBehaviorHasAnnotationTypes;
    }

    @Override
    public boolean isIncludeSubClasses() {
        return target.isIncludeSubClasses();
    }

    @Override
    public boolean isIncludeBootstrap() {
        return target.isIncludeBootstrap();
    }

    @Override
    public boolean doClassFilter(final int access,
                                 final String javaClassName,
                                 final String superClassTypeJavaClassName,
                                 final String[] interfaceTypeJavaClassNameArray,
                                 final String[] annotationTypeJavaClassNameArray) {
        return target.doClassFilter(
                access,
                javaClassName,
                superClassTypeJavaClassName,
                interfaceTypeJavaClassNameArray,
                annotationTypeJavaClassNameArray
        );
    }

    @Override
    public boolean doMethodFilter(final int access,
                                  final String javaMethodName,
                                  final String[] parameterTypeJavaClassNameArray,
                                  final String[] throwsTypeJavaClassNameArray,
                                  final String[] annotationTypeJavaClassNameArray) {
        return target.doMethodFilter(
                access,
                javaMethodName,
                parameterTypeJavaClassNameArray,
                throwsTypeJavaClassNameArray,
                annotationTypeJavaClassNameArray
        );
    }

}
