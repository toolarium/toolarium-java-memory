/*
 * MemoryMonitor.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor;

import com.github.toolarium.common.util.ByteUtil;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements a memory monitor
 *
 * @author patrick
 */
public class MemoryMonitor implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryMonitor.class);
    private static final String VALUE_SEPARATOR = "|";
    private long interval = 5000; // 5 sec sample checkpoint interval
    private long resultIndex = 0;
    private volatile Thread monitorThread;
    private MemoryMXBean memoryBean;
    private volatile IMemoryReport memoryReport;


    /**
     * Sets the memory report
     *
     * @param memoryReport the memory report
     */
    public void setMemoryReport(IMemoryReport memoryReport) {
        this.memoryReport = memoryReport;
    }


    /**
     * Sets the interval
     *
     * @param value the interval value
     */
    public void setInterval(long value) {
        this.interval = value;
    }


    /**
     * Start the memory monitor
     */
    public synchronized void startMonitor() {
        if (memoryReport == null) {
            memoryReport = new LogMemoryReport();
        }

        if (monitorThread == null) {
            memoryBean = ManagementFactory.getMemoryMXBean();
            monitorThread = new Thread(this, MemoryMonitor.class.getName() + ": Memory monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();
        }
    }


    /**
     * Stops the memory monitor
     */
    public synchronized void stopMonitor() {
        Thread thread = monitorThread;
        if (thread != null) {
            monitorThread = null;

            try {
                thread.interrupt();
            } catch (Exception e) {
                // NOP
            }
        }
    }


    /**
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        IMemoryReport report = memoryReport;
        if (report == null) {
            return;
        }

        report.start(interval);

        try {
            while (monitorThread == Thread.currentThread() && !Thread.currentThread().isInterrupted()) {
                try {
                    // wait every check point before getting the next memory usage
                    Thread.sleep(interval);
                    report.report(resultIndex, memoryBean.getHeapMemoryUsage(), memoryBean.getNonHeapMemoryUsage());
                    resultIndex++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Memory report cycle failed: {}", e.getMessage(), e);
                    }
                }
            }
        } finally {
            report.end();
            memoryReport = null;
        }
    }


    /**
     * Defines the memory report interface
     *
     * @author pmeier
     */
    public interface IMemoryReport {

        /**
         * Start memory report
         *
         * @param interval the interval
         */
        void start(long interval);


        /**
         * Report the memory information
         *
         * @param id the identifier
         * @param heapMemoryUsage the heap memory usage
         * @param nonHeapMemoryUsage the non heap memory usage
         */
        void report(long id, MemoryUsage heapMemoryUsage, MemoryUsage nonHeapMemoryUsage);


        /**
         * End the memory report
         */
        void end();
    }


    /**
     * Implements a log memory report
     */
    public abstract class AbstractMemoryReport implements IMemoryReport {

        /**
         * @see com.github.toolarium.java.memory.monitor.MemoryMonitor.IMemoryReport#start(long)
         */
        @Override
        public void start(long i) {
        }


        /**
         * @see com.github.toolarium.java.memory.monitor.MemoryMonitor.IMemoryReport#end()
         */
        @Override
        public void end() {
        }
    }


    /**
     * Implements a log memory report
     */
    class LogMemoryReport extends AbstractMemoryReport {
        /**
         * @see com.github.toolarium.java.memory.monitor.MemoryMonitor.AbstractMemoryReport#start(long)
         */
        @Override
        public void start(long i) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Start with interval {}", i);
            }
        }


        /**
         * @see com.github.toolarium.java.memory.monitor.MemoryMonitor.AbstractMemoryReport#end()
         */
        @Override
        public void end() {
            if (LOG.isDebugEnabled()) {
                LOG.debug("End");
            }
        }


        /**
         * @see com.github.toolarium.java.memory.monitor.MemoryMonitor.IMemoryReport#report(long, java.lang.management.MemoryUsage, java.lang.management.MemoryUsage)
         */
        @Override
        public void report(long id, MemoryUsage heapMemoryUsage, MemoryUsage nonHeapMemoryUsage) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Memory: heap [{}{}{}{}{}{}{}], non heap [{}{}{}{}{}{}{}] - [init{}commited{}used{}max]",
                           ByteUtil.getInstance().prettyPrintByteSize(heapMemoryUsage.getInit()), VALUE_SEPARATOR,
                           ByteUtil.getInstance().prettyPrintByteSize(heapMemoryUsage.getCommitted()), VALUE_SEPARATOR,
                           ByteUtil.getInstance().prettyPrintByteSize(heapMemoryUsage.getUsed()), VALUE_SEPARATOR,
                           ByteUtil.getInstance().prettyPrintByteSize(heapMemoryUsage.getMax()),
                           ByteUtil.getInstance().prettyPrintByteSize(nonHeapMemoryUsage.getInit()), VALUE_SEPARATOR,
                           ByteUtil.getInstance().prettyPrintByteSize(nonHeapMemoryUsage.getCommitted()), VALUE_SEPARATOR,
                           ByteUtil.getInstance().prettyPrintByteSize(nonHeapMemoryUsage.getUsed()), VALUE_SEPARATOR,
                           ByteUtil.getInstance().prettyPrintByteSize(nonHeapMemoryUsage.getMax()),
                           VALUE_SEPARATOR, VALUE_SEPARATOR, VALUE_SEPARATOR);
            }
        }
    }
}
