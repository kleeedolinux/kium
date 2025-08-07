package com.kleeedolinux.kium.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import com.kleeedolinux.kium.KiumMod;

public class AtomicThreadManager {
    private final int coreCount = Runtime.getRuntime().availableProcessors();
    private final ThreadPoolExecutor chunkProcessor;
    private final ThreadPoolExecutor cullingProcessor;
    private final ThreadPoolExecutor meshProcessor;
    private final ScheduledExecutorService scheduler;
    
    private final AtomicLong processedChunks = new AtomicLong(0);
    private final AtomicLong culledChunks = new AtomicLong(0);
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    private volatile boolean paused = false;
    
    private final BlockingQueue<ChunkTask> highPriorityQueue;
    private final BlockingQueue<ChunkTask> lowPriorityQueue;
    
    public AtomicThreadManager() {
        this.highPriorityQueue = new ArrayBlockingQueue<>(8192);
        this.lowPriorityQueue = new ArrayBlockingQueue<>(16384);
        
        this.chunkProcessor = new ThreadPoolExecutor(
            Math.max(2, coreCount / 2),
            coreCount * 2,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(4096),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Kium-ChunkProcessor-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        this.cullingProcessor = new ThreadPoolExecutor(
            Math.max(1, coreCount / 4),
            coreCount,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2048),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Kium-CullingProcessor-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.MAX_PRIORITY);
                    return t;
                }
            }
        );
        
        this.meshProcessor = new ThreadPoolExecutor(
            coreCount / 2,
            coreCount,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Kium-MeshProcessor-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY + 1);
                    return t;
                }
            }
        );
        
        this.scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Kium-Scheduler-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
        
        startTaskProcessor();
        KiumMod.LOGGER.info("Atomic thread manager initialized with {} core threads", coreCount);
    }
    
    private void startTaskProcessor() {
        scheduler.scheduleAtFixedRate(this::processTaskQueues, 0, 16, TimeUnit.MILLISECONDS);
    }
    
    private void processTaskQueues() {
        if (paused) {
            try {
                Thread.sleep(100); // Sleep longer when paused
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        
        ChunkTask task = highPriorityQueue.poll();
        if (task == null) {
            task = lowPriorityQueue.poll();
        }
        
        if (task != null) {
            submitChunkTask(task);
        } else {
            // Sleep briefly if no tasks to reduce CPU usage when idle
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public void submitHighPriorityChunk(ChunkTask task) {
        if (!highPriorityQueue.offer(task)) {
            chunkProcessor.submit(() -> {
                activeThreads.incrementAndGet();
                try {
                    task.execute();
                    processedChunks.incrementAndGet();
                } finally {
                    activeThreads.decrementAndGet();
                }
            });
        }
    }
    
    public void submitLowPriorityChunk(ChunkTask task) {
        lowPriorityQueue.offer(task);
    }
    
    public void submitChunkTask(ChunkTask task) {
        chunkProcessor.submit(() -> {
            activeThreads.incrementAndGet();
            try {
                task.execute();
                processedChunks.incrementAndGet();
            } finally {
                activeThreads.decrementAndGet();
            }
        });
    }
    
    public CompletableFuture<Void> submitCullingTask(Runnable task) {
        return CompletableFuture.runAsync(() -> {
            task.run();
            culledChunks.incrementAndGet();
        }, cullingProcessor);
    }
    
    public CompletableFuture<Void> submitMeshTask(Runnable task) {
        return CompletableFuture.runAsync(task, meshProcessor);
    }
    
    public CompletableFuture<Void> submitChunkTask(Runnable task) {
        return CompletableFuture.runAsync(() -> {
            activeThreads.incrementAndGet();
            try {
                task.run();
                processedChunks.incrementAndGet();
            } finally {
                activeThreads.decrementAndGet();
            }
        }, chunkProcessor);
    }
    
    public void adaptThreadPool(int renderDistance) {
        int targetThreads = Math.min(coreCount * 6, renderDistance / 4);
        chunkProcessor.setCorePoolSize(Math.max(coreCount, targetThreads));
        chunkProcessor.setMaximumPoolSize(Math.max(coreCount * 2, targetThreads * 2));
    }
    
    public long getProcessedChunks() {
        return processedChunks.get();
    }
    
    public long getCulledChunks() {
        return culledChunks.get();
    }
    
    public int getActiveThreads() {
        return activeThreads.get();
    }
    
    public void pauseProcessing() {
        paused = true;
    }
    
    public void resumeProcessing() {
        paused = false;
    }
    
    public void performCleanup() {
        // Clear queues to prevent buildup
        int highPriorityCleared = highPriorityQueue.size();
        int lowPriorityCleared = lowPriorityQueue.size();
        
        highPriorityQueue.clear();
        lowPriorityQueue.clear();
        
        // Compact thread pools if they're too large
        if (chunkProcessor.getActiveCount() == 0 && chunkProcessor.getPoolSize() > chunkProcessor.getCorePoolSize()) {
            chunkProcessor.setCorePoolSize(Math.max(2, coreCount / 2));
        }
        
        if (highPriorityCleared + lowPriorityCleared > 0) {
            KiumMod.LOGGER.debug("Cleaned up {} queued tasks from thread manager", 
                highPriorityCleared + lowPriorityCleared);
        }
    }
    
    public void shutdown() {
        paused = true;
        
        // Clear all queues
        highPriorityQueue.clear();
        lowPriorityQueue.clear();
        
        chunkProcessor.shutdown();
        cullingProcessor.shutdown();
        meshProcessor.shutdown();
        scheduler.shutdown();
        
        try {
            if (!chunkProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                chunkProcessor.shutdownNow();
            }
            if (!cullingProcessor.awaitTermination(2, TimeUnit.SECONDS)) {
                cullingProcessor.shutdownNow();
            }
            if (!meshProcessor.awaitTermination(2, TimeUnit.SECONDS)) {
                meshProcessor.shutdownNow();
            }
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            chunkProcessor.shutdownNow();
            cullingProcessor.shutdownNow();
            meshProcessor.shutdownNow();
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    public interface ChunkTask {
        void execute();
        int getPriority();
    }
}