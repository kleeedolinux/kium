package com.kleeedolinux.kium.pipeline;

import com.kleeedolinux.kium.KiumMod;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Pre-rendering pipeline that processes and optimizes chunk data before I/O operations
 * This system sits between chunk generation/loading and disk storage, providing:
 * - Mesh pre-generation and caching
 * - Block data compression and optimization
 * - Redundancy elimination
 * - I/O preparation and batching
 */
public class ChunkPrerenderPipeline {
    private static final int PRERENDER_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 3);
    private static final int BATCH_SIZE = 32; // Process chunks in batches
    private static final int COMPRESSION_LEVEL = 6; // Balanced compression
    private static final long CACHE_TTL = 600000; // 10 minutes
    
    // Thread pools for different stages
    private final ForkJoinPool prerenderPool;
    private final ThreadPoolExecutor compressionPool;
    private final ScheduledExecutorService maintenanceScheduler;
    
    // Processing queues
    private final BlockingQueue<ChunkPrerenderTask> incomingQueue;
    private final ConcurrentHashMap<Long, PrerenderedChunkData> prerenderCache;
    private final ConcurrentHashMap<Long, CompletableFuture<PrerenderedChunkData>> processingTasks;
    
    // Performance tracking
    private final AtomicLong totalChunksProcessed = new AtomicLong(0);
    private final AtomicLong compressionSavings = new AtomicLong(0);
    private final AtomicLong prerenderHits = new AtomicLong(0);
    private final AtomicLong ioOptimizations = new AtomicLong(0);
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    
    // Configuration
    private volatile boolean enableMeshPregeneration = true;
    private volatile boolean enableBlockOptimization = true;
    private volatile boolean enableCompressionBatching = true;
    private volatile int maxQueueSize = 1024;
    
    public ChunkPrerenderPipeline() {
        // Initialize thread pools with specific optimizations
        this.prerenderPool = new ForkJoinPool(
            PRERENDER_THREADS,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null, true
        );
        
        this.compressionPool = new ThreadPoolExecutor(
            2, Math.max(2, PRERENDER_THREADS / 2),
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "Kium-ChunkCompressor");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        );
        
        this.maintenanceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Kium-PrerenderMaintenance");
            t.setDaemon(true);
            return t;
        });
        
        // Initialize data structures
        this.incomingQueue = new ArrayBlockingQueue<>(maxQueueSize);
        this.prerenderCache = new ConcurrentHashMap<>(2048);
        this.processingTasks = new ConcurrentHashMap<>(512);
        
        // Start processing loops
        startProcessingLoop();
        startMaintenanceLoop();
        
        KiumMod.LOGGER.info("ChunkPrerenderPipeline initialized with {} threads", PRERENDER_THREADS);
    }
    
    /**
     * Submits a chunk for pre-rendering before I/O operations
     */
    public CompletableFuture<PrerenderedChunkData> prerenderChunk(WorldChunk chunk, int priority) {
        if (chunk == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        long chunkKey = chunk.getPos().toLong();
        
        // Check cache first
        PrerenderedChunkData cached = prerenderCache.get(chunkKey);
        if (cached != null && !cached.isExpired()) {
            prerenderHits.incrementAndGet();
            return CompletableFuture.completedFuture(cached);
        }
        
        // Check if already processing
        CompletableFuture<PrerenderedChunkData> existingTask = processingTasks.get(chunkKey);
        if (existingTask != null && !existingTask.isDone()) {
            return existingTask;
        }
        
        // Create new processing task
        CompletableFuture<PrerenderedChunkData> future = new CompletableFuture<>();
        processingTasks.put(chunkKey, future);
        
        ChunkPrerenderTask task = new ChunkPrerenderTask(chunk, priority, future);
        
        if (!incomingQueue.offer(task)) {
            // Queue full - process with high priority chunks immediately
            if (priority >= 8) {
                return processChunkImmediate(chunk);
            } else {
                // Drop low priority chunks when queue is full
                processingTasks.remove(chunkKey);
                future.complete(null);
                return future;
            }
        }
        
        return future;
    }
    
    /**
     * Gets prerendered data if available, null otherwise
     */
    public PrerenderedChunkData getPrerenderedData(ChunkPos chunkPos) {
        long chunkKey = chunkPos.toLong();
        PrerenderedChunkData cached = prerenderCache.get(chunkKey);
        return (cached != null && !cached.isExpired()) ? cached : null;
    }
    
    /**
     * Processes chunk immediately for high-priority cases
     */
    private CompletableFuture<PrerenderedChunkData> processChunkImmediate(WorldChunk chunk) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return processChunk(chunk, 10);
            } catch (Exception e) {
                KiumMod.LOGGER.error("Failed immediate chunk processing: {}", e.getMessage());
                return null;
            }
        }, prerenderPool);
    }
    
    /**
     * Main processing loop that consumes tasks from the queue
     */
    private void startProcessingLoop() {
        prerenderPool.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<ChunkPrerenderTask> batch = new ArrayList<>(BATCH_SIZE);
                    
                    // Collect a batch of tasks
                    ChunkPrerenderTask firstTask = incomingQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (firstTask == null) continue;
                    
                    batch.add(firstTask);
                    incomingQueue.drainTo(batch, BATCH_SIZE - 1);
                    
                    // Process batch in parallel
                    List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
                    
                    for (ChunkPrerenderTask task : batch) {
                        CompletableFuture<Void> taskFuture = CompletableFuture.runAsync(() -> {
                            try {
                                activeTasks.incrementAndGet();
                                PrerenderedChunkData result = processChunk(task.chunk, task.priority);
                                
                                if (result != null) {
                                    long chunkKey = task.chunk.getPos().toLong();
                                    prerenderCache.put(chunkKey, result);
                                    totalChunksProcessed.incrementAndGet();
                                }
                                
                                task.future.complete(result);
                                processingTasks.remove(task.chunk.getPos().toLong());
                                
                            } catch (Exception e) {
                                KiumMod.LOGGER.error("Chunk prerender failed: {}", e.getMessage());
                                task.future.completeExceptionally(e);
                            } finally {
                                activeTasks.decrementAndGet();
                            }
                        }, prerenderPool);
                        
                        batchFutures.add(taskFuture);
                    }
                    
                    // Wait for batch completion with timeout
                    try {
                        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                            .get(5, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        KiumMod.LOGGER.warn("Batch processing timeout - some chunks may be delayed");
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    KiumMod.LOGGER.error("Processing loop error: {}", e.getMessage());
                }
            }
        });
    }
    
    /**
     * Core chunk processing logic - optimizes chunk data before I/O
     */
    private PrerenderedChunkData processChunk(WorldChunk chunk, int priority) {
        long startTime = System.nanoTime();
        ChunkPos pos = chunk.getPos();
        
        // 1. Extract and optimize block data
        OptimizedBlockData blockData = extractOptimizedBlockData(chunk);
        
        // 2. Pre-generate mesh data if enabled
        MeshData meshData = null;
        if (enableMeshPregeneration) {
            meshData = pregenerateMeshData(chunk, blockData);
        }
        
        // 3. Compress data for I/O efficiency
        CompressedData compressed = null;
        if (enableCompressionBatching) {
            compressed = compressChunkData(blockData, meshData);
            compressionSavings.addAndGet(compressed.originalSize - compressed.compressedSize);
        }
        
        // 4. Generate I/O optimization hints
        IOHints ioHints = generateIOHints(chunk, blockData);
        ioOptimizations.incrementAndGet();
        
        long processingTime = (System.nanoTime() - startTime) / 1_000_000;
        
        PrerenderedChunkData result = new PrerenderedChunkData(
            pos, blockData, meshData, compressed, ioHints, 
            System.currentTimeMillis(), processingTime
        );
        
        KiumMod.LOGGER.debug("Prerendered chunk {} in {}ms (priority: {})", pos, processingTime, priority);
        return result;
    }
    
    /**
     * Extracts and optimizes block data from chunk
     */
    private OptimizedBlockData extractOptimizedBlockData(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        Map<BlockState, Integer> blockCounts = new HashMap<>();
        List<BlockStateRun> runs = new ArrayList<>();
        int totalBlocks = 0;
        int airBlocks = 0;
        
        // Process each section and create run-length encoding
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) {
                // Empty section - single air run
                runs.add(new BlockStateRun(Blocks.AIR.getDefaultState(), 16 * 16 * 16));
                airBlocks += 16 * 16 * 16;
                totalBlocks += 16 * 16 * 16;
                continue;
            }
            
            // Process section with run-length encoding
            BlockState currentState = null;
            int runLength = 0;
            
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        totalBlocks++;
                        
                        if (state.isAir()) {
                            airBlocks++;
                        }
                        
                        blockCounts.merge(state, 1, Integer::sum);
                        
                        if (state.equals(currentState)) {
                            runLength++;
                        } else {
                            if (currentState != null) {
                                runs.add(new BlockStateRun(currentState, runLength));
                            }
                            currentState = state;
                            runLength = 1;
                        }
                    }
                }
            }
            
            // Add final run for section
            if (currentState != null) {
                runs.add(new BlockStateRun(currentState, runLength));
            }
        }
        
        // Calculate compression ratio
        double compressionRatio = (double) runs.size() / totalBlocks;
        
        return new OptimizedBlockData(runs, blockCounts, totalBlocks, airBlocks, compressionRatio);
    }
    
    /**
     * Pre-generates mesh data for faster rendering
     */
    private MeshData pregenerateMeshData(WorldChunk chunk, OptimizedBlockData blockData) {
        // Only pregenerate mesh for chunks with reasonable complexity
        if (blockData.compressionRatio > 0.5) {
            return null; // Too complex, let runtime handle it
        }
        
        // Generate basic mesh statistics and face visibility
        int visibleFaces = 0;
        int totalFaces = 0;
        Set<BlockState> uniqueBlocks = new HashSet<>();
        
        for (BlockStateRun run : blockData.runs) {
            if (!run.state.isAir()) {
                uniqueBlocks.add(run.state);
                // Estimate faces (simplified - 6 faces per block, minus hidden faces)
                totalFaces += run.length * 6;
                visibleFaces += run.length * 3; // Rough estimate of visible faces
            }
        }
        
        // Create lightweight mesh data
        return new MeshData(visibleFaces, totalFaces, uniqueBlocks.size(), 
            blockData.totalBlocks - blockData.airBlocks);
    }
    
    /**
     * Compresses chunk data for efficient I/O
     */
    private CompressedData compressChunkData(OptimizedBlockData blockData, MeshData meshData) {
        try {
            // Serialize block data to bytes
            ByteBuffer buffer = ByteBuffer.allocate(blockData.runs.size() * 8); // Rough estimate
            
            for (BlockStateRun run : blockData.runs) {
                // Simplified serialization - in real implementation would use proper format
                buffer.putInt(run.state.hashCode());
                buffer.putInt(run.length);
            }
            
            byte[] originalData = buffer.array();
            int originalSize = originalData.length;
            
            // Compress using Deflate
            Deflater deflater = new Deflater(COMPRESSION_LEVEL);
            deflater.setInput(originalData);
            deflater.finish();
            
            byte[] compressedData = new byte[originalSize];
            int compressedSize = deflater.deflate(compressedData);
            deflater.end();
            
            // Trim to actual size
            byte[] finalCompressed = new byte[compressedSize];
            System.arraycopy(compressedData, 0, finalCompressed, 0, compressedSize);
            
            return new CompressedData(finalCompressed, originalSize, compressedSize);
            
        } catch (Exception e) {
            KiumMod.LOGGER.error("Chunk compression failed: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Generates I/O optimization hints
     */
    private IOHints generateIOHints(WorldChunk chunk, OptimizedBlockData blockData) {
        ChunkPos pos = chunk.getPos();
        
        // Calculate I/O priority based on chunk characteristics
        int ioPriority = 5; // Default
        
        // Higher priority for chunks near spawn
        double distanceFromSpawn = Math.sqrt(pos.x * pos.x + pos.z * pos.z);
        if (distanceFromSpawn < 16) {
            ioPriority = 10;
        } else if (distanceFromSpawn < 64) {
            ioPriority = 7;
        }
        
        // Adjust based on complexity
        if (blockData.compressionRatio < 0.1) {
            ioPriority += 2; // Simple chunks can be processed faster
        }
        
        // Determine optimal batch group
        String batchGroup = "default";
        if (blockData.airBlocks > blockData.totalBlocks * 0.9) {
            batchGroup = "mostly_air";
        } else if (blockData.blockCounts.size() < 5) {
            batchGroup = "simple";
        } else if (blockData.blockCounts.size() > 50) {
            batchGroup = "complex";
        }
        
        // Estimate write size
        int estimatedWriteSize = blockData.runs.size() * 8; // Rough estimate
        
        return new IOHints(ioPriority, batchGroup, estimatedWriteSize, 
            blockData.compressionRatio < 0.2);
    }
    
    /**
     * Maintenance loop for cache cleanup and optimization
     */
    private void startMaintenanceLoop() {
        maintenanceScheduler.scheduleAtFixedRate(() -> {
            try {
                // Clean expired cache entries
                long currentTime = System.currentTimeMillis();
                int removed = 0;
                
                Iterator<Map.Entry<Long, PrerenderedChunkData>> iterator = 
                    prerenderCache.entrySet().iterator();
                    
                while (iterator.hasNext()) {
                    Map.Entry<Long, PrerenderedChunkData> entry = iterator.next();
                    if (entry.getValue().isExpired(currentTime)) {
                        iterator.remove();
                        removed++;
                    }
                }
                
                // Clean completed processing tasks
                processingTasks.entrySet().removeIf(entry -> entry.getValue().isDone());
                
                if (removed > 0) {
                    KiumMod.LOGGER.debug("Cleaned {} expired prerender cache entries", removed);
                }
                
                // Log performance stats
                if (totalChunksProcessed.get() % 1000 == 0 && totalChunksProcessed.get() > 0) {
                    logPerformanceStats();
                }
                
            } catch (Exception e) {
                KiumMod.LOGGER.error("Maintenance loop error: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Performance statistics and monitoring
     */
    public String getPerformanceStats() {
        double hitRate = prerenderHits.get() / Math.max(1.0, totalChunksProcessed.get());
        double avgSavings = compressionSavings.get() / Math.max(1.0, totalChunksProcessed.get());
        
        return String.format(
            "ChunkPrerenderPipeline - Processed: %d, Cache Hits: %.1f%%, Compression Savings: %.1fKB avg, Active: %d, Queue: %d",
            totalChunksProcessed.get(), hitRate * 100, avgSavings / 1024.0, 
            activeTasks.get(), incomingQueue.size()
        );
    }
    
    private void logPerformanceStats() {
        KiumMod.LOGGER.info(getPerformanceStats());
    }
    
    public void shutdown() {
        try {
            maintenanceScheduler.shutdown();
            prerenderPool.shutdown();
            compressionPool.shutdown();
            
            if (!maintenanceScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                maintenanceScheduler.shutdownNow();
            }
            
            if (!prerenderPool.awaitTermination(5, TimeUnit.SECONDS)) {
                prerenderPool.shutdownNow();
            }
            
            if (!compressionPool.awaitTermination(3, TimeUnit.SECONDS)) {
                compressionPool.shutdownNow();
            }
            
            prerenderCache.clear();
            processingTasks.clear();
            incomingQueue.clear();
            
            KiumMod.LOGGER.info("ChunkPrerenderPipeline shutdown complete");
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            KiumMod.LOGGER.warn("Shutdown interrupted");
        }
    }
    
    // Configuration methods
    public void setEnableMeshPregeneration(boolean enable) {
        this.enableMeshPregeneration = enable;
    }
    
    public void setEnableBlockOptimization(boolean enable) {
        this.enableBlockOptimization = enable;
    }
    
    public void setEnableCompressionBatching(boolean enable) {
        this.enableCompressionBatching = enable;
    }
    
    // Getters
    public long getTotalChunksProcessed() { return totalChunksProcessed.get(); }
    public long getCompressionSavings() { return compressionSavings.get(); }
    public long getPrerenderHits() { return prerenderHits.get(); }
    public int getActiveTasks() { return activeTasks.get(); }
    public int getQueueSize() { return incomingQueue.size(); }
    
    // Data classes
    private static class ChunkPrerenderTask {
        final WorldChunk chunk;
        final int priority;
        final CompletableFuture<PrerenderedChunkData> future;
        
        ChunkPrerenderTask(WorldChunk chunk, int priority, CompletableFuture<PrerenderedChunkData> future) {
            this.chunk = chunk;
            this.priority = priority;
            this.future = future;
        }
    }
    
    public static class PrerenderedChunkData {
        public final ChunkPos chunkPos;
        public final OptimizedBlockData blockData;
        public final MeshData meshData;
        public final CompressedData compressedData;
        public final IOHints ioHints;
        public final long timestamp;
        public final long processingTime;
        
        PrerenderedChunkData(ChunkPos chunkPos, OptimizedBlockData blockData, MeshData meshData,
                           CompressedData compressedData, IOHints ioHints, long timestamp, long processingTime) {
            this.chunkPos = chunkPos;
            this.blockData = blockData;
            this.meshData = meshData;
            this.compressedData = compressedData;
            this.ioHints = ioHints;
            this.timestamp = timestamp;
            this.processingTime = processingTime;
        }
        
        public boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        public boolean isExpired(long currentTime) {
            return (currentTime - timestamp) > CACHE_TTL;
        }
    }
    
    public static class OptimizedBlockData {
        public final List<BlockStateRun> runs;
        public final Map<BlockState, Integer> blockCounts;
        public final int totalBlocks;
        public final int airBlocks;
        public final double compressionRatio;
        
        OptimizedBlockData(List<BlockStateRun> runs, Map<BlockState, Integer> blockCounts,
                          int totalBlocks, int airBlocks, double compressionRatio) {
            this.runs = runs;
            this.blockCounts = blockCounts;
            this.totalBlocks = totalBlocks;
            this.airBlocks = airBlocks;
            this.compressionRatio = compressionRatio;
        }
    }
    
    public static class BlockStateRun {
        public final BlockState state;
        public final int length;
        
        BlockStateRun(BlockState state, int length) {
            this.state = state;
            this.length = length;
        }
    }
    
    public static class MeshData {
        public final int visibleFaces;
        public final int totalFaces;
        public final int uniqueBlocks;
        public final int solidBlocks;
        
        MeshData(int visibleFaces, int totalFaces, int uniqueBlocks, int solidBlocks) {
            this.visibleFaces = visibleFaces;
            this.totalFaces = totalFaces;
            this.uniqueBlocks = uniqueBlocks;
            this.solidBlocks = solidBlocks;
        }
    }
    
    public static class CompressedData {
        public final byte[] data;
        public final int originalSize;
        public final int compressedSize;
        
        CompressedData(byte[] data, int originalSize, int compressedSize) {
            this.data = data;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
        }
        
        public double getCompressionRatio() {
            return (double) compressedSize / originalSize;
        }
    }
    
    public static class IOHints {
        public final int priority;
        public final String batchGroup;
        public final int estimatedSize;
        public final boolean fastWrite;
        
        IOHints(int priority, String batchGroup, int estimatedSize, boolean fastWrite) {
            this.priority = priority;
            this.batchGroup = batchGroup;
            this.estimatedSize = estimatedSize;
            this.fastWrite = fastWrite;
        }
    }
}