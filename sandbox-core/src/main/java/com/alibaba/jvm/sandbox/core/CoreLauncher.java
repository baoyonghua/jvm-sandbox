package com.alibaba.jvm.sandbox.core;

import com.sun.tools.attach.VirtualMachine;
import org.apache.commons.lang3.StringUtils;

import static com.alibaba.jvm.sandbox.core.util.SandboxStringUtils.getCauseMessage;

/**
 * 沙箱内核启动器
 * <p>
 * CoreLauncher 类作为 JVM-Sandbox 的启动器，由它的{@link #attachAgent(String, String, String)}方法负责 attach 到目标进程上。<br>
 * 当成功attach到目标进程后，会触发Agent-Class {@link AgentLauncher}的agentmain方法，该方法包含了诸多操作, 包括：
 * <li>将 sandbox-spy 包下的 Spy 类注册到 BootstrapClassLoader 中</li>
 * <li>创建 SandboxClassLoader 加载 sandbox-core 包下的所有类（包括依赖的 sandbox-api 等）</li>
 * <li>以及反射调用 sandbox-core 包下的 JettyCoreServer.bind()方法启动 HTTP 服务</li>
 * </p>
 */
public class CoreLauncher {


    /**
     * @param targetJvmPid 目标JVM进程的PID
     * @param agentJarPath sandbox-agent.jar的路径
     * @param token        token, 用于客户端与沙箱进行通信的身份验证
     * @throws Exception
     */
    public CoreLauncher(final String targetJvmPid, final String agentJarPath, final String token) throws Exception {

        // 加载agent
        attachAgent(targetJvmPid, agentJarPath, token);

    }

    /**
     * 内核启动程序
     *
     * @param args 参数
     *             [0] : PID
     *             [1] : agent.jar's value
     *             [2] : token
     */
    public static void main(String[] args) {
        try {

            // check args
            if (args.length != 3
                    || StringUtils.isBlank(args[0])
                    || StringUtils.isBlank(args[1])
                    || StringUtils.isBlank(args[2])) {
                throw new IllegalArgumentException("illegal args");
            }

            new CoreLauncher(args[0], args[1], args[2]);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.err.println("sandbox load jvm failed : " + getCauseMessage(t));
            System.exit(-1);
        }
    }

    /**
     * 加载Agent
     *
     * @param targetJvmPid 目标JVM进程的PID
     * @param agentJarPath sandbox-agent.jar的路径
     * @param token        token, 用于客户端与沙箱进行通信的身份验证
     * @throws Exception
     */
    private void attachAgent(final String targetJvmPid, final String agentJarPath, final String cfg) throws Exception {

        VirtualMachine vmObj = null;
        try {

            vmObj = VirtualMachine.attach(targetJvmPid);
            if (vmObj != null) {
                vmObj.loadAgent(agentJarPath, cfg);
            }

        } finally {
            if (null != vmObj) {
                vmObj.detach();
            }
        }
    }

}
