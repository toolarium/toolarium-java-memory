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
    private Thread monintorThread;
    private MemoryMXBean memoryBean;
    private IMemoryReport memoryReport;


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
    public void startMonitor() {
        if (memoryReport == null) {
            memoryReport = new LogMemoryReport();
        }
        
        if (monintorThread == null) {
            memoryBean = ManagementFactory.getMemoryMXBean();
            monintorThread = new Thread(this, MemoryMonitor.class.getName() + ": Memory monitor");
            monintorThread.setDaemon(true);
            monintorThread.start();
        }
    }


    /**
     * Stops th memory monitor
     */
    public void stopMonitor() {
        if (monintorThread != null) {
            memoryReport.end();
            memoryReport = null;

            try {
                monintorThread.interrupt();
            } catch (Exception e) {
                // NOP
            }

            monintorThread = null;
        }
    }

    
    /**
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        if (memoryReport == null) {
            return;
        }

        memoryReport.start(interval);

        try {
            while (memoryReport != null && !Thread.currentThread().isInterrupted()) {
                try {
                    // wait every check point before getting the next memory usage
                    Thread.sleep(interval);
                    memoryReport.report(resultIndex, memoryBean.getHeapMemoryUsage(), memoryBean.getNonHeapMemoryUsage());
                    resultIndex++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // NOP
                }
            }
        } finally {
            if (memoryReport != null) {
                memoryReport.end();
            }
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
                LOG.debug("Start with interval " + i);
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
                LOG.debug("Memory: "
                           + "heap [" 
                           + ByteUtil.getInstance().prettyPrintByteSize(heapMemoryUsage.getInit()) + VALUE_SEPARATOR 
                           + ByteUtil.getInstance().prettyPrintByteSize(heapMemoryUsage.getCommitted()) + VALUE_SEPARATOR 
                           + ByteUtil.getInstance().prettyPrintByteSize(heapMemoryUsage.getUsed()) + VALUE_SEPARATOR 
                           + ByteUtil.getInstance().prettyPrintByteSize(heapMemoryUsage.getMax()) 
                           + "]"
                           + ", "
                           + "non heap [" 
                           + ByteUtil.getInstance().prettyPrintByteSize(nonHeapMemoryUsage.getInit()) + VALUE_SEPARATOR 
                           + ByteUtil.getInstance().prettyPrintByteSize(nonHeapMemoryUsage.getCommitted()) + VALUE_SEPARATOR 
                           + ByteUtil.getInstance().prettyPrintByteSize(nonHeapMemoryUsage.getUsed()) + VALUE_SEPARATOR 
                           + ByteUtil.getInstance().prettyPrintByteSize(nonHeapMemoryUsage.getMax()) 
                           + "]" 
                           + " - [init" + VALUE_SEPARATOR + "commited" + VALUE_SEPARATOR + "used" + VALUE_SEPARATOR + "max]");
            }
        }
    }
}
