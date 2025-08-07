package com.kleeedolinux.kium.core;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import com.kleeedolinux.kium.algorithms.LODSystem;
import com.kleeedolinux.kium.algorithms.FrustumCuller;
import com.kleeedolinux.kium.algorithms.SpatialIndex;
import com.kleeedolinux.kium.performance.PerformanceMonitor;
import com.kleeedolinux.kium.KiumMod;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import it.unimi.dsi.fastutil.longs.*;

public class ChunkOptimizer {
    private final AtomicThreadManager threadManager;
    private final PerformanceMonitor performanceMonitor;
    private final LODSystem lodSystem;
    private final FrustumCuller frustumCuller;
    private final SpatialIndex spatialIndex;
    
    private final Long2ObjectMap<ChunkState> chunkStates = new Long2ObjectOpenHashMap<>();
    private final AtomicInteger maxRenderDistance = new AtomicInteger(32);
    private final AtomicBoolean adaptiveMode = new AtomicBoolean(true);
    private final FastChunkBuilder fastChunkBuilder;
    
    private final ConcurrentHashMap<Integer, LongSet> lodLevels = new ConcurrentHashMap<>();
    private volatile boolean paused = false;
    
    public ChunkOptimizer(AtomicThreadManager threadManager, PerformanceMonitor performanceMonitor) {
        this.threadManager = threadManager;
        this.performanceMonitor = performanceMonitor;
        this.lodSystem = new LODSystem();
        this.frustumCuller = new FrustumCuller();
        this.spatialIndex = new SpatialIndex();
        
        try {
            this.fastChunkBuilder = new FastChunkBuilder();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize FastChunkBuilder", e);
        }
        
        initializeLODLevels();
        KiumMod.LOGGER.info("ChunkOptimizer initialized with FastChunkBuilder and LOD system support");
    }
    
    private void initializeLODLevels() {
        for (int i = 0; i <= 6; i++) {
            lodLevels.put(i, new LongOpenHashSet());
        }
    }
    
    public void optimizeChunks(Vec3d playerPos, float partialTick) {
        if (paused) return;
        
        long startTime = System.nanoTime();
        
        adaptRenderDistance();
        
        int renderDistance = maxRenderDistance.get();
        ChunkPos playerChunk = new ChunkPos((int) playerPos.x >> 4, (int) playerPos.z >> 4);
        
        LongSet visibleChunks = new LongOpenHashSet();
        LongSet toProcess = new LongOpenHashSet();
        
        // Update FastChunkBuilder with current player position
        fastChunkBuilder.updatePlayerPosition(playerPos);
        
        threadManager.submitCullingTask(() -> {
            calculateVisibleChunks(playerPos, playerChunk, renderDistance, visibleChunks, toProcess);
        }).thenRun(() -> {
            processChunkLODs(playerPos, toProcess);
            
            // Submit chunks to FastChunkBuilder for immediate rendering
            submitChunksForFastRendering(toProcess, playerPos);
        });
        
        long endTime = System.nanoTime();
        performanceMonitor.recordChunkOptimizationTime((endTime - startTime) / 1_000_000.0);
    }
    
    private void calculateVisibleChunks(Vec3d playerPos, ChunkPos playerChunk, int renderDistance, LongSet visibleChunks, LongSet toProcess) {
        int centerX = playerChunk.x;
        int centerZ = playerChunk.z;
        
        for (int x = centerX - renderDistance; x <= centerX + renderDistance; x++) {
            for (int z = centerZ - renderDistance; z <= centerZ + renderDistance; z++) {
                long chunkKey = ChunkPos.toLong(x, z);
                double distance = Math.sqrt((x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ));
                
                if (distance <= renderDistance) {
                    if (frustumCuller.isChunkInFrustum(x, z, playerPos)) {
                        visibleChunks.add(chunkKey);
                        toProcess.add(chunkKey);
                        
                        ChunkState state = chunkStates.computeIfAbsent(chunkKey, k -> new ChunkState());
                        state.lastSeen = System.currentTimeMillis();
                        state.distance = distance;
                    }
                }
            }
        }
        
        spatialIndex.updateIndex(visibleChunks, playerPos);
    }
    
    private void processChunkLODs(Vec3d playerPos, LongSet chunks) {
        clearLODLevels();
        
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        
        for (long chunkKey : chunks) {
            tasks.add(threadManager.submitChunkTask(() -> {
                ChunkPos pos = new ChunkPos(chunkKey);
                double distance = Math.sqrt(
                    Math.pow(pos.x * 16 + 8 - playerPos.x, 2) + 
                    Math.pow(pos.z * 16 + 8 - playerPos.z, 2)
                );
                
                int lodLevel = lodSystem.calculateLODLevel(distance, maxRenderDistance.get());
                lodLevels.get(lodLevel).add(chunkKey);
                
                ChunkState state = chunkStates.get(chunkKey);
                if (state != null) {
                    state.lodLevel = lodLevel;
                    state.needsUpdate = state.lastLOD != lodLevel;
                    state.lastLOD = lodLevel;
                }
            }));
        }
        
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
            .thenRun(this::optimizeMeshGeneration);
    }
    
    private void clearLODLevels() {
        for (LongSet set : lodLevels.values()) {
            set.clear();
        }
    }
    
    private void optimizeMeshGeneration() {
        for (int lod = 0; lod <= 6; lod++) {
            final int finalLod = lod;
            LongSet chunks = lodLevels.get(lod);
            if (!chunks.isEmpty()) {
                threadManager.submitMeshTask(() -> {
                    for (long chunkKey : chunks) {
                        generateOptimizedMesh(chunkKey, finalLod);
                    }
                });
            }
        }
    }
    
    private void generateOptimizedMesh(long chunkKey, int lodLevel) {
        ChunkState state = chunkStates.get(chunkKey);
        if (state != null && state.needsUpdate) {
            switch (lodLevel) {
                case 0, 1 -> generateFullMesh(chunkKey);
                case 2, 3 -> generateReducedMesh(chunkKey, lodLevel);
                case 4, 5 -> generateSimplifiedMesh(chunkKey, lodLevel);
                case 6 -> generateImpostorMesh(chunkKey);
            }
            state.needsUpdate = false;
        }
    }
    
    private void generateFullMesh(long chunkKey) {
        ChunkPos pos = new ChunkPos(chunkKey);
    }
    
    private void generateReducedMesh(long chunkKey, int lodLevel) {
        ChunkPos pos = new ChunkPos(chunkKey);
        int reduction = 1 << (lodLevel - 1);
    }
    
    private void generateSimplifiedMesh(long chunkKey, int lodLevel) {
        ChunkPos pos = new ChunkPos(chunkKey);
        int simplification = 1 << lodLevel;
    }
    
    private void generateImpostorMesh(long chunkKey) {
        ChunkPos pos = new ChunkPos(chunkKey);
    }
    
    private void adaptRenderDistance() {
        if (!adaptiveMode.get()) return;
        
        double avgFrameTime = performanceMonitor.getAverageFrameTime();
        double targetFrameTime = 16.67;
        
        int currentDistance = maxRenderDistance.get();
        
        if (avgFrameTime > targetFrameTime * 1.2 && currentDistance > 16) {
            maxRenderDistance.set(Math.max(16, currentDistance - 2));
            threadManager.adaptThreadPool(maxRenderDistance.get());
        } else if (avgFrameTime < targetFrameTime * 0.8 && currentDistance < 1024) {
            maxRenderDistance.set(Math.min(1024, currentDistance + 1));
            threadManager.adaptThreadPool(maxRenderDistance.get());
        }
    }
    
    public void setMaxRenderDistance(int distance) {
        maxRenderDistance.set(Math.max(16, Math.min(1024, distance)));
        threadManager.adaptThreadPool(distance);
    }
    
    public int getMaxRenderDistance() {
        return maxRenderDistance.get();
    }
    
    public void setAdaptiveMode(boolean adaptive) {
        adaptiveMode.set(adaptive);
    }
    
    public LongSet getChunksAtLOD(int lodLevel) {
        return new LongOpenHashSet(lodLevels.getOrDefault(lodLevel, new LongOpenHashSet()));
    }
    
    public int getChunkLOD(long chunkKey) {
        ChunkState state = chunkStates.get(chunkKey);
        return state != null ? state.lodLevel : -1;
    }
    
    private void submitChunksForFastRendering(LongSet chunks, Vec3d playerPos) {
        for (long chunkKey : chunks) {
            ChunkPos pos = new ChunkPos(chunkKey);
            double distance = Math.sqrt(
                Math.pow(pos.x * 16 + 8 - playerPos.x, 2) + 
                Math.pow(pos.z * 16 + 8 - playerPos.z, 2)
            ) / 16.0; // Convert to chunk distance
            
            // Determine if this is an emergency/immediate chunk
            boolean emergency = distance <= 3.0;
            
            // Submit to FastChunkBuilder
            fastChunkBuilder.submitChunkBuild(pos, new byte[0], emergency);
        }
    }
    
    public FastChunkBuilder getFastChunkBuilder() {
        return fastChunkBuilder;
    }
    
    public void pause() {
        paused = true;
        if (fastChunkBuilder != null) {
            fastChunkBuilder.pause();
        }
    }
    
    public void resume() {
        paused = false;
        if (fastChunkBuilder != null) {
            fastChunkBuilder.resume();
        }
    }
    
    public void performCleanup() {
        // Clear old chunk states
        long currentTime = System.currentTimeMillis();
        chunkStates.values().removeIf(state -> 
            currentTime - state.lastSeen > 300000); // Remove chunks not seen for 5 minutes
            
        // Clear empty LOD levels
        for (LongSet set : lodLevels.values()) {
            if (set.isEmpty()) {
                set.clear();
            }
        }
        
        KiumMod.LOGGER.debug("ChunkOptimizer cleanup: {} active chunk states", chunkStates.size());
    }
    
    public void shutdown() {
        paused = true;
        
        // Clear all data structures
        chunkStates.clear();
        for (LongSet set : lodLevels.values()) {
            set.clear();
        }
        
        if (fastChunkBuilder != null) {
            fastChunkBuilder.shutdown();
        }
    }
    
    private static class ChunkState {
        long lastSeen;
        double distance;
        int lodLevel = 0;
        int lastLOD = -1;
        boolean needsUpdate = true;
    }
}