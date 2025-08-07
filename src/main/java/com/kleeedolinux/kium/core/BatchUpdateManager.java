package com.kleeedolinux.kium.core;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import com.kleeedolinux.kium.KiumMod;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import it.unimi.dsi.fastutil.longs.*;

public class BatchUpdateManager {
    private static final int BATCH_SIZE = 32;
    private static final int MAX_BATCHES_PER_FRAME = 8;
    private static final int BATCH_TIMEOUT_MS = 16; // One frame at 60 FPS
    private static final int PRIORITY_LEVELS = 4;
    
    private final ThreadPoolExecutor batchProcessor;
    private final ScheduledExecutorService batchScheduler;
    private final ArrayBlockingQueue<UpdateBatch>[] priorityQueues;
    private final ConcurrentHashMap<Long, PendingUpdate> pendingUpdates;
    
    private final AtomicInteger processedBatches = new AtomicInteger(0);
    private final AtomicInteger droppedUpdates = new AtomicInteger(0);
    private final AtomicLong totalUpdateTime = new AtomicLong(0);
    
    private volatile Vec3d playerPosition = Vec3d.ZERO;
    private volatile boolean isProcessing = false;
    
    @SuppressWarnings("unchecked")
    public BatchUpdateManager() {
        int cores = Runtime.getRuntime().availableProcessors();
        
        this.batchProcessor = new ThreadPoolExecutor(
            Math.max(2, cores / 2),
            cores,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(512),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Kium-BatchProcessor-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY + 1);
                    return t;
                }
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        
        this.batchScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Kium-BatchScheduler");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY - 1);
            return t;
        });
        
        this.priorityQueues = new ArrayBlockingQueue[PRIORITY_LEVELS];
        for (int i = 0; i < PRIORITY_LEVELS; i++) {
            priorityQueues[i] = new ArrayBlockingQueue<>(256);
        }
        
        this.pendingUpdates = new ConcurrentHashMap<>(2048);
        
        startBatchProcessor();
        KiumMod.LOGGER.info("Batch update manager initialized with {} priority levels", PRIORITY_LEVELS);
    }
    
    private void startBatchProcessor() {
        // Start batch processing scheduler - runs at 60 FPS
        batchScheduler.scheduleAtFixedRate(this::processBatches, 0, BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        // Start priority queue processors
        for (int i = 0; i < PRIORITY_LEVELS; i++) {
            final int priority = i;
            new Thread(() -> processPriorityQueue(priority), "Kium-PriorityProcessor-" + i).start();
        }
    }
    
    public void updatePlayerPosition(Vec3d position) {
        this.playerPosition = position;
        
        // Trigger immediate processing for nearby chunks
        triggerImmediateUpdates(position);
    }
    
    public void submitChunkUpdate(ChunkPos chunkPos, UpdateType updateType, Object updateData) {
        long chunkKey = chunkPos.toLong();
        
        // Calculate priority based on distance from player
        double distance = calculateDistance(chunkPos);
        int priority = calculatePriority(distance, updateType);
        
        PendingUpdate update = new PendingUpdate(chunkPos, updateType, updateData, priority, System.nanoTime());
        
        // Merge with existing updates for the same chunk
        PendingUpdate existing = pendingUpdates.put(chunkKey, update);
        if (existing != null) {
            update = mergeUpdates(existing, update);
            pendingUpdates.put(chunkKey, update);
        }
        
        // Add to appropriate priority queue
        if (!priorityQueues[priority].offer(createBatch(update))) {
            // Queue full, drop lowest priority updates
            droppedUpdates.incrementAndGet();
        }
    }
    
    public void submitBatchUpdate(Collection<ChunkPos> chunks, UpdateType updateType, Object updateData) {
        List<PendingUpdate> updates = new ArrayList<>();
        
        for (ChunkPos pos : chunks) {
            double distance = calculateDistance(pos);
            int priority = calculatePriority(distance, updateType);
            
            updates.add(new PendingUpdate(pos, updateType, updateData, priority, System.nanoTime()));
        }
        
        // Group updates by priority
        Map<Integer, List<PendingUpdate>> priorityGroups = new HashMap<>();
        for (PendingUpdate update : updates) {
            priorityGroups.computeIfAbsent(update.priority, k -> new ArrayList<>()).add(update);
        }
        
        // Submit batches for each priority level
        for (Map.Entry<Integer, List<PendingUpdate>> entry : priorityGroups.entrySet()) {
            int priority = entry.getKey();
            List<PendingUpdate> priorityUpdates = entry.getValue();
            
            // Split into batches of BATCH_SIZE
            for (int i = 0; i < priorityUpdates.size(); i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, priorityUpdates.size());
                List<PendingUpdate> batch = priorityUpdates.subList(i, endIndex);
                
                UpdateBatch updateBatch = new UpdateBatch(batch, priority);
                if (!priorityQueues[priority].offer(updateBatch)) {
                    droppedUpdates.addAndGet(batch.size());
                }
            }
        }
    }
    
    private void processBatches() {
        if (isProcessing) return;
        
        isProcessing = true;
        long startTime = System.nanoTime();
        
        try {
            int processedThisFrame = 0;
            
            // Process high-priority batches first
            for (int priority = 0; priority < PRIORITY_LEVELS && processedThisFrame < MAX_BATCHES_PER_FRAME; priority++) {
                while (processedThisFrame < MAX_BATCHES_PER_FRAME) {
                    UpdateBatch batch = priorityQueues[priority].poll();
                    if (batch == null) break;
                    
                    processBatch(batch);
                    processedThisFrame++;
                }
            }
            
        } finally {
            long endTime = System.nanoTime();
            totalUpdateTime.addAndGet(endTime - startTime);
            isProcessing = false;
        }
    }
    
    private void processPriorityQueue(int priority) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                UpdateBatch batch = priorityQueues[priority].take();
                batchProcessor.submit(() -> processBatch(batch));
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    private void processBatch(UpdateBatch batch) {
        long batchStartTime = System.nanoTime();
        
        // Sort updates by chunk distance for cache efficiency
        batch.updates.sort((a, b) -> Double.compare(
            calculateDistance(a.chunkPos), 
            calculateDistance(b.chunkPos)
        ));
        
        // Group similar update types for batch processing
        Map<UpdateType, List<PendingUpdate>> typeGroups = new HashMap<>();
        for (PendingUpdate update : batch.updates) {
            typeGroups.computeIfAbsent(update.updateType, k -> new ArrayList<>()).add(update);
        }
        
        // Process each update type as a group
        for (Map.Entry<UpdateType, List<PendingUpdate>> entry : typeGroups.entrySet()) {
            UpdateType updateType = entry.getKey();
            List<PendingUpdate> updates = entry.getValue();
            
            processUpdateGroup(updateType, updates);
        }
        
        long batchEndTime = System.nanoTime();
        batch.processingTime = (batchEndTime - batchStartTime) / 1_000_000.0;
        
        processedBatches.incrementAndGet();
        
        // Remove processed updates from pending map
        for (PendingUpdate update : batch.updates) {
            pendingUpdates.remove(update.chunkPos.toLong(), update);
        }
        
        // Log slow batches
        if (batch.processingTime > 5.0) {
            KiumMod.LOGGER.warn("Slow batch processing: {} updates took {:.2f}ms", 
                batch.updates.size(), batch.processingTime);
        }
    }
    
    private void processUpdateGroup(UpdateType updateType, List<PendingUpdate> updates) {
        switch (updateType) {
            case MESH_REBUILD:
                processMeshRebuildGroup(updates);
                break;
            case LIGHTING_UPDATE:
                processLightingUpdateGroup(updates);
                break;
            case BLOCK_UPDATE:
                processBlockUpdateGroup(updates);
                break;
            case LOD_UPDATE:
                processLODUpdateGroup(updates);
                break;
            case VISIBILITY_UPDATE:
                processVisibilityUpdateGroup(updates);
                break;
        }
    }
    
    private void processMeshRebuildGroup(List<PendingUpdate> updates) {
        // Batch mesh rebuilds for efficiency
        List<ChunkPos> positions = updates.stream().map(u -> u.chunkPos).toList();
        
        if (KiumMod.getChunkOptimizer() != null) {
            FastChunkBuilder builder = KiumMod.getChunkOptimizer().getFastChunkBuilder();
            if (builder != null) {
                for (ChunkPos pos : positions) {
                    double distance = calculateDistance(pos);
                    boolean emergency = distance <= 3.0;
                    
                    builder.submitChunkBuild(pos, new byte[0], emergency);
                }
            }
        }
    }
    
    private void processLightingUpdateGroup(List<PendingUpdate> updates) {
        // Batch lighting calculations
        for (PendingUpdate update : updates) {
            // Lighting update logic would go here
            calculateChunkLighting(update.chunkPos);
        }
    }
    
    private void processBlockUpdateGroup(List<PendingUpdate> updates) {
        // Batch block updates
        Map<ChunkPos, List<PendingUpdate>> chunkGroups = new HashMap<>();
        
        for (PendingUpdate update : updates) {
            chunkGroups.computeIfAbsent(update.chunkPos, k -> new ArrayList<>()).add(update);
        }
        
        for (Map.Entry<ChunkPos, List<PendingUpdate>> entry : chunkGroups.entrySet()) {
            ChunkPos pos = entry.getKey();
            List<PendingUpdate> chunkUpdates = entry.getValue();
            
            updateChunkBlocks(pos, chunkUpdates);
        }
    }
    
    private void processLODUpdateGroup(List<PendingUpdate> updates) {
        // Batch LOD level changes
        for (PendingUpdate update : updates) {
            updateChunkLOD(update.chunkPos, (Integer) update.updateData);
        }
    }
    
    private void processVisibilityUpdateGroup(List<PendingUpdate> updates) {
        // Batch visibility updates
        for (PendingUpdate update : updates) {
            updateChunkVisibility(update.chunkPos, (Boolean) update.updateData);
        }
    }
    
    private void calculateChunkLighting(ChunkPos pos) {
        // Calculate lighting for chunk using optimized algorithm
        int baseX = pos.x << 4;
        int baseZ = pos.z << 4;
        
        // Process lighting in 4x4x4 blocks for cache efficiency
        for (int y = -64; y < 320; y += 4) {
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    calculateLightingBlock(baseX + x, y, baseZ + z);
                }
            }
        }
    }
    
    private void calculateLightingBlock(int x, int y, int z) {
        // Fast lighting calculation for 4x4x4 block
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 4 && y + dy < 320; dy++) {
                for (int dz = 0; dz < 4; dz++) {
                    int blockY = y + dy;
                    if (blockY < -64) continue;
                    
                    // Calculate sky light propagation
                    byte skyLight = (byte) Math.max(0, 15 - Math.max(0, 128 - blockY) / 8);
                    
                    // Calculate block light (simplified - assumes no light sources)
                    byte blockLight = 0;
                    
                    // Store lighting data (in real implementation, this would update the world)
                }
            }
        }
    }
    
    private void updateChunkBlocks(ChunkPos pos, List<PendingUpdate> updates) {
        // Process block updates for this chunk
        Map<String, Integer> blockCounts = new HashMap<>();
        
        for (PendingUpdate update : updates) {
            if (update.updateData instanceof BlockUpdateData data) {
                String blockType = data.blockType;
                blockCounts.merge(blockType, 1, Integer::sum);
                
                // Apply block update
                applyBlockUpdate(pos, data);
            }
        }
        
        // If significant block changes, trigger mesh rebuild
        int totalUpdates = blockCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalUpdates > 10) {
            submitChunkUpdate(pos, UpdateType.MESH_REBUILD, null);
        }
    }
    
    private void applyBlockUpdate(ChunkPos pos, BlockUpdateData data) {
        // Apply individual block update
        // In real implementation, this would interface with Minecraft's chunk system
        int worldX = (pos.x << 4) + data.localX;
        int worldZ = (pos.z << 4) + data.localZ;
        
        // Update block state and mark for re-rendering
        markBlockForUpdate(worldX, data.y, worldZ, data.blockType);
    }
    
    private void markBlockForUpdate(int x, int y, int z, String blockType) {
        // Mark surrounding area for potential re-lighting and re-meshing
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    ChunkPos neighborPos = new ChunkPos((x + dx) >> 4, (z + dz) >> 4);
                    // Would trigger neighbor chunk updates in real implementation
                }
            }
        }
    }
    
    private void updateChunkLOD(ChunkPos pos, int lodLevel) {
        // Update chunk Level of Detail
        if (KiumMod.getChunkOptimizer() != null) {
            long chunkKey = pos.toLong();
            
            // Update LOD level in chunk state
            if (lodLevel >= 0 && lodLevel <= 6) {
                // Remove from old LOD level
                for (int level = 0; level <= 6; level++) {
                    LongSet levelChunks = KiumMod.getChunkOptimizer().getChunksAtLOD(level);
                    levelChunks.remove(chunkKey);
                }
                
                // Add to new LOD level
                LongSet newLevelChunks = KiumMod.getChunkOptimizer().getChunksAtLOD(lodLevel);
                newLevelChunks.add(chunkKey);
                
                // Trigger mesh rebuild with new LOD
                submitChunkUpdate(pos, UpdateType.MESH_REBUILD, lodLevel);
            }
        }
    }
    
    private void updateChunkVisibility(ChunkPos pos, boolean visible) {
        // Update chunk visibility state
        long chunkKey = pos.toLong();
        
        if (visible) {
            // Make chunk visible - ensure it's rendered
            if (KiumMod.getChunkOptimizer() != null) {
                double distance = calculateDistance(pos);
                boolean emergency = distance <= 3.0;
                
                // Submit for immediate rendering if close
                KiumMod.getChunkOptimizer().getFastChunkBuilder().submitChunkBuild(pos, new byte[0], emergency);
            }
        } else {
            // Hide chunk - remove from render queues
            if (KiumMod.getChunkOptimizer() != null && KiumMod.getChunkOptimizer().getFastChunkBuilder() != null) {
                KiumMod.getChunkOptimizer().getFastChunkBuilder().invalidateChunk(pos);
            }
            
            // Remove from GPU if present
            if (KiumMod.getGPUOptimizer() != null) {
                // Would remove associated vertex buffers
            }
        }
    }
    
    // Helper classes for block update data
    public static class BlockUpdateData {
        public final int localX, y, localZ;
        public final String blockType;
        public final Object additionalData;
        
        public BlockUpdateData(int localX, int y, int localZ, String blockType, Object additionalData) {
            this.localX = localX;
            this.y = y;
            this.localZ = localZ;
            this.blockType = blockType;
            this.additionalData = additionalData;
        }
    }
    
    private void triggerImmediateUpdates(Vec3d position) {
        // Find chunks within immediate radius that need updates
        ChunkPos playerChunk = new ChunkPos((int) position.x >> 4, (int) position.z >> 4);
        
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ChunkPos pos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                long chunkKey = pos.toLong();
                
                PendingUpdate pending = pendingUpdates.get(chunkKey);
                if (pending != null && pending.priority > 0) {
                    // Upgrade to highest priority
                    pending.priority = 0;
                    
                    // Move to high priority queue
                    priorityQueues[0].offer(createBatch(pending));
                }
            }
        }
    }
    
    private double calculateDistance(ChunkPos pos) {
        double chunkCenterX = (pos.x << 4) + 8.0;
        double chunkCenterZ = (pos.z << 4) + 8.0;
        
        return Math.sqrt(
            Math.pow(chunkCenterX - playerPosition.x, 2) +
            Math.pow(chunkCenterZ - playerPosition.z, 2)
        ) / 16.0;
    }
    
    private int calculatePriority(double distance, UpdateType updateType) {
        // Base priority on distance and update type
        int basePriority = (int) Math.min(3, distance / 8);
        
        // Adjust for update type importance
        switch (updateType) {
            case MESH_REBUILD -> basePriority = Math.max(0, basePriority - 1);
            case LIGHTING_UPDATE -> basePriority = Math.min(3, basePriority + 1);
            case BLOCK_UPDATE -> basePriority = basePriority;
            case LOD_UPDATE -> basePriority = Math.min(3, basePriority + 1);
            case VISIBILITY_UPDATE -> basePriority = Math.max(0, basePriority - 1);
        }
        
        return Math.max(0, Math.min(PRIORITY_LEVELS - 1, basePriority));
    }
    
    private PendingUpdate mergeUpdates(PendingUpdate existing, PendingUpdate newUpdate) {
        // Merge updates for the same chunk
        if (existing.updateType == newUpdate.updateType) {
            // Same type - use newer update with higher priority
            return newUpdate.priority <= existing.priority ? newUpdate : existing;
        } else {
            // Different types - prioritize based on importance
            UpdateType[] priorityOrder = {
                UpdateType.MESH_REBUILD,
                UpdateType.VISIBILITY_UPDATE,
                UpdateType.BLOCK_UPDATE,
                UpdateType.LIGHTING_UPDATE,
                UpdateType.LOD_UPDATE
            };
            
            for (UpdateType type : priorityOrder) {
                if (existing.updateType == type) return existing;
                if (newUpdate.updateType == type) return newUpdate;
            }
            
            return newUpdate; // Fallback
        }
    }
    
    private UpdateBatch createBatch(PendingUpdate update) {
        return new UpdateBatch(Collections.singletonList(update), update.priority);
    }
    
    public int getProcessedBatches() {
        return processedBatches.get();
    }
    
    public int getDroppedUpdates() {
        return droppedUpdates.get();
    }
    
    public double getAverageProcessingTime() {
        int batches = processedBatches.get();
        long totalTime = totalUpdateTime.get();
        return batches > 0 ? (totalTime / 1_000_000.0) / batches : 0.0;
    }
    
    public int getPendingUpdates() {
        return pendingUpdates.size();
    }
    
    public String getPerformanceStats() {
        return String.format(
            "BatchUpdateManager - Processed: %d, Dropped: %d, Pending: %d, Avg Time: %.2fms",
            getProcessedBatches(), getDroppedUpdates(), getPendingUpdates(), getAverageProcessingTime()
        );
    }
    
    public void shutdown() {
        batchScheduler.shutdown();
        batchProcessor.shutdown();
        
        try {
            if (!batchScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                batchScheduler.shutdownNow();
            }
            if (!batchProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                batchProcessor.shutdownNow();
            }
        } catch (InterruptedException e) {
            batchScheduler.shutdownNow();
            batchProcessor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        pendingUpdates.clear();
        KiumMod.LOGGER.info("BatchUpdateManager shutdown complete");
    }
    
    public void pause() {
        // Pause batch processing
    }
    
    public void resume() {
        // Resume batch processing  
    }
    
    public enum UpdateType {
        MESH_REBUILD,
        LIGHTING_UPDATE, 
        BLOCK_UPDATE,
        LOD_UPDATE,
        VISIBILITY_UPDATE
    }
    
    private static class PendingUpdate {
        final ChunkPos chunkPos;
        final UpdateType updateType;
        final Object updateData;
        int priority;
        final long submitTime;
        
        PendingUpdate(ChunkPos chunkPos, UpdateType updateType, Object updateData, int priority, long submitTime) {
            this.chunkPos = chunkPos;
            this.updateType = updateType;
            this.updateData = updateData;
            this.priority = priority;
            this.submitTime = submitTime;
        }
        
        double getAge() {
            return (System.nanoTime() - submitTime) / 1_000_000.0;
        }
    }
    
    private static class UpdateBatch {
        final List<PendingUpdate> updates;
        final int priority;
        final long createTime;
        double processingTime = 0.0;
        
        UpdateBatch(List<PendingUpdate> updates, int priority) {
            this.updates = new ArrayList<>(updates);
            this.priority = priority;
            this.createTime = System.nanoTime();
        }
        
        int size() {
            return updates.size();
        }
        
        double getAge() {
            return (System.nanoTime() - createTime) / 1_000_000.0;
        }
    }
}