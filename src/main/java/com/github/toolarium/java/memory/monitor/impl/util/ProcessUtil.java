/*
 * ProcessUtil.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.impl.util;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Singleton utility to determine the current JVM process ID and inspect other processes.
 *
 * <p>Three strategies are tried in order for the current process; the first one that
 * succeeds is cached and reused on every subsequent call:</p>
 * <ol>
 *   <li>{@code java.lang.ProcessHandle.current()} via reflection (Java 9+)</li>
 *   <li>{@link ManagementFactory#getRuntimeMXBean()}{@code .getName()} — the name is
 *       typically {@code <pid>@<hostname>}; the part before {@code @} is used.</li>
 *   <li>Internal {@code sun.management.VMManagement#getProcessId()} via reflection
 *       (available on older HotSpot JVMs).</li>
 * </ol>
 *
 * <p>If none of the strategies succeeds the returned string is empty.</p>
 *
 * <p>Looking up or checking another process by PID requires Java 9+ and uses
 * {@code java.lang.ProcessHandle.of(pid)}. On older JVMs those methods return
 * an empty string / {@code false}.</p>
 *
 * @author patrick
 */
public final class ProcessUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessUtil.class);
    private static final String JAVA_LANG_PROCESS_HANDLE = "java.lang.ProcessHandle";


    /**
     * The detection strategy that was found to work on this JVM.
     * {@code null} means not yet determined; {@link Strategy#NONE} means all strategies failed.
     */
    private enum Strategy {
        /** java.lang.ProcessHandle (Java 9+) */
        PROCESS_HANDLE,
        /** RuntimeMXBean.getName() split on '@' */
        RUNTIME_NAME,
        /** sun.management.VMManagement#getProcessId() */
        VM_MANAGEMENT,
        /** No strategy worked */
        NONE
    }

    private volatile Strategy cachedStrategy;

    private static final class HOLDER {
        static final ProcessUtil INSTANCE = new ProcessUtil();
    }


    /**
     * Private constructor.
     */
    private ProcessUtil() {
        cachedStrategy = null;
    }


    /**
     * Returns the singleton instance.
     *
     * @return the singleton
     */
    public static ProcessUtil getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Returns the current JVM process ID as a string, or an empty string if it cannot be determined.
     *
     * <p>The first successful strategy is cached and reused on every subsequent call.</p>
     *
     * @return the process ID string, never {@code null}
     */
    public String getProcessId() {
        Strategy strategy = cachedStrategy;
        if (strategy != null) {
            if (strategy == Strategy.NONE) {
                return "";
            }
            return resolveCurrentPid(strategy);
        }

        String result = resolveCurrentPid(Strategy.PROCESS_HANDLE);
        if (!result.isEmpty()) {
            cachedStrategy = Strategy.PROCESS_HANDLE;
            return result;
        }

        result = resolveCurrentPid(Strategy.RUNTIME_NAME);
        if (!result.isEmpty()) {
            cachedStrategy = Strategy.RUNTIME_NAME;
            return result;
        }

        result = resolveCurrentPid(Strategy.VM_MANAGEMENT);
        if (!result.isEmpty()) {
            cachedStrategy = Strategy.VM_MANAGEMENT;
            return result;
        }

        cachedStrategy = Strategy.NONE;
        return "";
    }


    /**
     * Returns the process ID string for the given PID, or an empty string if the process
     * does not exist or cannot be looked up (requires Java 9+).
     *
     * @param pid the target process ID
     * @return the process ID string, never {@code null}
     */
    public String getProcessId(long pid) {
        try {
            Class<?> clazz = Class.forName(JAVA_LANG_PROCESS_HANDLE);
            Method ofMethod = clazz.getMethod("of", long.class);
            Object optional = ofMethod.invoke(null, pid);
            Method isPresent = optional.getClass().getMethod("isPresent");
            if (Boolean.TRUE.equals(isPresent.invoke(optional))) {
                Method get = optional.getClass().getMethod("get");
                Object handle = get.invoke(optional);
                Method pidMethod = handle.getClass().getMethod("pid");
                return "" + pidMethod.invoke(handle);
            }
        } catch (Exception e) { // NOPMD
            // ProcessHandle not available on this JVM
        }
        return "";
    }


    /**
     * Returns {@code true} when a process with the given PID is currently running.
     * Requires Java 9+; always returns {@code false} on older JVMs.
     *
     * @param pid the target process ID
     * @return {@code true} if the process is alive
     */
    public boolean isProcessRunning(long pid) {
        return !getProcessId(pid).isEmpty();
    }


    /**
     * Prints the current process ID to stdout.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        LOG.info("{}", ProcessUtil.getInstance().getProcessId());
    }


    /**
     * Executes a single strategy to read the current process ID.
     *
     * @param strategy the strategy to use
     * @return the process ID string, or empty if it failed
     */
    private String resolveCurrentPid(Strategy strategy) {
        switch (strategy) {
            case PROCESS_HANDLE:
                return resolveViaProcessHandle();
            case RUNTIME_NAME:
                return resolveViaRuntimeName();
            case VM_MANAGEMENT:
                return resolveViaVmManagement();
            default:
                return "";
        }
    }


    /**
     * Resolves the current process ID via {@code java.lang.ProcessHandle} (Java 9+).
     *
     * @return the process ID string, or empty if not available
     */
    private String resolveViaProcessHandle() {
        try {
            Class<?> clazz = Class.forName(JAVA_LANG_PROCESS_HANDLE);
            Method current = clazz.getMethod("current");
            Object handle = current.invoke(null);
            if (handle != null) {
                Method pidMethod = handle.getClass().getMethod("pid");
                return "" + pidMethod.invoke(handle);
            }
        } catch (Exception e) { // NOPMD
            // not available
        }
        return "";
    }


    /**
     * Resolves the current process ID via {@link ManagementFactory#getRuntimeMXBean()} name.
     *
     * @return the process ID string, or empty if not available
     */
    private String resolveViaRuntimeName() {
        try {
            if (ManagementFactory.getRuntimeMXBean() != null) {
                String processName = ManagementFactory.getRuntimeMXBean().getName();
                if (processName != null && !processName.isEmpty()) {
                    String[] parts = processName.split("@");
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        return parts[0];
                    }
                }
            }
        } catch (Exception e) { // NOPMD
            // not available
        }
        return "";
    }


    /**
     * Resolves the current process ID via internal {@code sun.management.VMManagement} reflection.
     *
     * @return the process ID string, or empty if not available
     */
    private String resolveViaVmManagement() {
        try {
            if (ManagementFactory.getRuntimeMXBean() != null) {
                java.lang.management.RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
                Field jvmField = runtime.getClass().getDeclaredField("jvm");
                if (jvmField != null) {
                    jvmField.setAccessible(true);
                    Object mgmt = jvmField.get(runtime);
                    Method pidMethod = mgmt.getClass().getDeclaredMethod("getProcessId");
                    pidMethod.setAccessible(true);
                    Object pid = pidMethod.invoke(mgmt);
                    if (pid != null) {
                        return "" + pid;
                    }
                }
            }
        } catch (Exception e) { // NOPMD
            // not available
        }
        return "";
    }
}
