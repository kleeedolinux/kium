package com.kleeedolinux.kium.algorithms;

import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.ChunkPos;
import com.kleeedolinux.kium.KiumMod;

import it.unimi.dsi.fastutil.longs.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SpatialIndex {
    private static final int GRID_SIZE = 64;
    private static final int GRID_SHIFT = 6;
    
    private final ConcurrentHashMap<Long, LongSet> spatialGrid = new ConcurrentHashMap<>();
    private final Long2DoubleMap chunkDistances = new Long2DoubleOpenHashMap();
    private final AtomicInteger indexUpdates = new AtomicInteger(0);
    
    private volatile Vec3d lastPlayerPos = Vec3d.ZERO;
    private volatile long lastUpdateTime = 0;
    
    public SpatialIndex() {
        KiumMod.LOGGER.info("Spatial index initialized with grid size: {}x{}", GRID_SIZE, GRID_SIZE);
    }
    
    public void updateIndex(LongSet visibleChunks, Vec3d playerPos) {
        long currentTime = System.currentTimeMillis();
        
        if (shouldUpdate(playerPos, currentTime)) {
            rebuildIndex(visibleChunks, playerPos);
            lastPlayerPos = playerPos;
            lastUpdateTime = currentTime;
            indexUpdates.incrementAndGet();
        } else {
            updateDistances(visibleChunks, playerPos);
        }
    }
    
    private boolean shouldUpdate(Vec3d playerPos, long currentTime) {
        return lastPlayerPos.distanceTo(playerPos) > GRID_SIZE * 0.5 || 
               currentTime - lastUpdateTime > 1000;
    }
    
    private void rebuildIndex(LongSet visibleChunks, Vec3d playerPos) {
        spatialGrid.clear();
        chunkDistances.clear();
        
        int playerGridX = (int) (playerPos.x / 16) >> GRID_SHIFT;
        int playerGridZ = (int) (playerPos.z / 16) >> GRID_SHIFT;
        
        for (long chunkKey : visibleChunks) {
            ChunkPos chunkPos = new ChunkPos(chunkKey);
            int gridX = chunkPos.x >> GRID_SHIFT;
            int gridZ = chunkPos.z >> GRID_SHIFT;
            
            long gridKey = ((long) gridX << 32) | (gridZ & 0xFFFFFFFFL);
            
            spatialGrid.computeIfAbsent(gridKey, k -> new LongOpenHashSet()).add(chunkKey);
            
            double distance = calculateChunkDistance(chunkPos, playerPos);
            chunkDistances.put(chunkKey, distance);
        }
    }
    
    private void updateDistances(LongSet visibleChunks, Vec3d playerPos) {
        for (long chunkKey : visibleChunks) {
            ChunkPos chunkPos = new ChunkPos(chunkKey);
            double distance = calculateChunkDistance(chunkPos, playerPos);
            chunkDistances.put(chunkKey, distance);
        }
    }
    
    private double calculateChunkDistance(ChunkPos chunkPos, Vec3d playerPos) {
        double chunkCenterX = chunkPos.x * 16.0 + 8.0;
        double chunkCenterZ = chunkPos.z * 16.0 + 8.0;
        
        return Math.sqrt(
            Math.pow(chunkCenterX - playerPos.x, 2) + 
            Math.pow(chunkCenterZ - playerPos.z, 2)
        );
    }
    
    public LongSet getChunksInRadius(Vec3d center, double radius) {
        LongSet result = new LongOpenHashSet();
        
        int minGridX = (int) ((center.x - radius) / 16) >> GRID_SHIFT;
        int maxGridX = (int) ((center.x + radius) / 16) >> GRID_SHIFT;
        int minGridZ = (int) ((center.z - radius) / 16) >> GRID_SHIFT;
        int maxGridZ = (int) ((center.z + radius) / 16) >> GRID_SHIFT;
        
        for (int gridX = minGridX; gridX <= maxGridX; gridX++) {
            for (int gridZ = minGridZ; gridZ <= maxGridZ; gridZ++) {
                long gridKey = ((long) gridX << 32) | (gridZ & 0xFFFFFFFFL);
                LongSet chunks = spatialGrid.get(gridKey);
                
                if (chunks != null) {
                    for (long chunkKey : chunks) {
                        double distance = chunkDistances.getOrDefault(chunkKey, Double.MAX_VALUE);
                        if (distance <= radius) {
                            result.add(chunkKey);
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    public LongSet getChunksInLODRange(Vec3d center, double minDistance, double maxDistance) {
        LongSet result = new LongOpenHashSet();
        
        int minGridX = (int) ((center.x - maxDistance) / 16) >> GRID_SHIFT;
        int maxGridX = (int) ((center.x + maxDistance) / 16) >> GRID_SHIFT;
        int minGridZ = (int) ((center.z - maxDistance) / 16) >> GRID_SHIFT;
        int maxGridZ = (int) ((center.z + maxDistance) / 16) >> GRID_SHIFT;
        
        for (int gridX = minGridX; gridX <= maxGridX; gridX++) {
            for (int gridZ = minGridZ; gridZ <= maxGridZ; gridZ++) {
                long gridKey = ((long) gridX << 32) | (gridZ & 0xFFFFFFFFL);
                LongSet chunks = spatialGrid.get(gridKey);
                
                if (chunks != null) {
                    for (long chunkKey : chunks) {
                        double distance = chunkDistances.getOrDefault(chunkKey, Double.MAX_VALUE);
                        if (distance >= minDistance && distance <= maxDistance) {
                            result.add(chunkKey);
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    public LongList getNearestChunks(Vec3d center, int count) {
        LongList allChunks = new LongArrayList(chunkDistances.keySet());
        
        allChunks.sort((a, b) -> {
            double distA = chunkDistances.getOrDefault(a, Double.MAX_VALUE);
            double distB = chunkDistances.getOrDefault(b, Double.MAX_VALUE);
            return Double.compare(distA, distB);
        });
        
        return allChunks.subList(0, Math.min(count, allChunks.size()));
    }
    
    public double getChunkDistance(long chunkKey) {
        return chunkDistances.getOrDefault(chunkKey, Double.MAX_VALUE);
    }
    
    public LongSet getChunksInGrid(int gridX, int gridZ) {
        long gridKey = ((long) gridX << 32) | (gridZ & 0xFFFFFFFFL);
        return spatialGrid.getOrDefault(gridKey, new LongOpenHashSet());
    }
    
    public int getGridSize() {
        return GRID_SIZE;
    }
    
    public int getIndexedChunkCount() {
        return chunkDistances.size();
    }
    
    public int getGridCellCount() {
        return spatialGrid.size();
    }
    
    public int getIndexUpdates() {
        return indexUpdates.get();
    }
    
    public void optimizeIndex() {
        long currentTime = System.currentTimeMillis();
        
        chunkDistances.long2DoubleEntrySet().removeIf(entry -> {
            return currentTime - lastUpdateTime > 5000 && entry.getDoubleValue() > 2048.0;
        });
        
        spatialGrid.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(chunkKey -> !chunkDistances.containsKey(chunkKey));
            return entry.getValue().isEmpty();
        });
    }
    
    public void clear() {
        spatialGrid.clear();
        chunkDistances.clear();
    }
}