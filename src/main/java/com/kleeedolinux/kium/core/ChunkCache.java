package com.kleeedolinux.kium.core;

import net.minecraft.util.math.ChunkPos;
import com.kleeedolinux.kium.KiumMod;

import net.openhft.chronicle.map.ChronicleMap;
import it.unimi.dsi.fastutil.longs.*;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ChunkCache {
    private static final int MAX_CACHE_SIZE = 8192;
    private static final String CACHE_FILE = "kium_chunk_cache.dat";
    
    private final ChronicleMap<Long, ChunkData> persistentCache;
    private final ConcurrentHashMap<Long, CompressedChunkData> memoryCache;
    private final Long2ObjectMap<Long> accessTimes;
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final ScheduledExecutorService cleanupExecutor;
    
    public ChunkCache() throws IOException {
        this.persistentCache = ChronicleMap
            .of(Long.class, ChunkData.class)
            .entries(MAX_CACHE_SIZE * 2)
            .createPersistedTo(new File(CACHE_FILE));
            
        this.memoryCache = new ConcurrentHashMap<>(MAX_CACHE_SIZE);
        this.accessTimes = new Long2ObjectOpenHashMap<>();
        
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Kium-ChunkCache-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        cleanupExecutor.scheduleAtFixedRate(this::performCleanup, 30, 30, TimeUnit.SECONDS);
        KiumMod.LOGGER.info("Chunk cache initialized with memory-mapped storage");
    }
    
    public void cacheChunk(long chunkKey, byte[] meshData, int lodLevel, long timestamp) {
        ChunkData data = new ChunkData(meshData, lodLevel, timestamp);
        
        if (shouldCacheInMemory(lodLevel)) {
            CompressedChunkData compressed = new CompressedChunkData(meshData, lodLevel, timestamp);
            memoryCache.put(chunkKey, compressed);
        }
        
        persistentCache.put(chunkKey, data);
        accessTimes.put(chunkKey, (Long) System.currentTimeMillis());
    }
    
    public ChunkData getChunk(long chunkKey) {
        accessTimes.put(chunkKey, (Long) System.currentTimeMillis());
        
        CompressedChunkData memData = memoryCache.get(chunkKey);
        if (memData != null) {
            cacheHits.incrementAndGet();
            return memData.decompress();
        }
        
        ChunkData persistentData = persistentCache.get(chunkKey);
        if (persistentData != null) {
            cacheHits.incrementAndGet();
            
            if (shouldCacheInMemory(persistentData.lodLevel)) {
                memoryCache.put(chunkKey, new CompressedChunkData(persistentData));
            }
            
            return persistentData;
        }
        
        cacheMisses.incrementAndGet();
        return null;
    }
    
    private boolean shouldCacheInMemory(int lodLevel) {
        return lodLevel <= 2;
    }
    
    public boolean hasChunk(long chunkKey) {
        return memoryCache.containsKey(chunkKey) || persistentCache.containsKey(chunkKey);
    }
    
    public void invalidateChunk(long chunkKey) {
        memoryCache.remove(chunkKey);
        persistentCache.remove(chunkKey);
        accessTimes.remove(chunkKey);
    }
    
    public void invalidateRadius(ChunkPos center, int radius) {
        int centerX = center.x;
        int centerZ = center.z;
        
        LongList toRemove = new LongArrayList();
        
        for (Long chunkKey : memoryCache.keySet()) {
            ChunkPos pos = new ChunkPos(chunkKey);
            double distance = Math.sqrt(
                Math.pow(pos.x - centerX, 2) + 
                Math.pow(pos.z - centerZ, 2)
            );
            
            if (distance <= radius) {
                toRemove.add(chunkKey.longValue());
            }
        }
        
        for (long key : toRemove) {
            invalidateChunk(key);
        }
    }
    
    private void performCleanup() {
        long currentTime = System.currentTimeMillis();
        long maxAge = 5 * 60 * 1000; // 5 minutes
        
        // Cleanup memory cache
        if (memoryCache.size() > MAX_CACHE_SIZE * 0.8) {
            LongList toRemove = new LongArrayList();
            
            for (var entry : accessTimes.long2ObjectEntrySet()) {
                long age = currentTime - entry.getValue();
                if (age > maxAge) {
                    toRemove.add(entry.getLongKey());
                }
            }
            
            toRemove.forEach(key -> {
                memoryCache.remove(key);
                accessTimes.remove(key);
            });
        }
        
        // Cleanup persistent cache
        if (persistentCache.size() > MAX_CACHE_SIZE * 1.5) {
            LongList oldEntries = new LongArrayList();
            
            for (var entry : persistentCache.entrySet()) {
                Long accessTime = accessTimes.get(entry.getKey());
                if (accessTime == null || currentTime - accessTime > maxAge * 2) {
                    oldEntries.add(entry.getKey());
                }
            }
            
            oldEntries.forEach(persistentCache::remove);
        }
        
        KiumMod.LOGGER.debug("Cache cleanup completed. Memory: {}, Persistent: {}", 
            memoryCache.size(), persistentCache.size());
    }
    
    public void preloadChunks(LongSet chunkKeys) {
        CompletableFuture.runAsync(() -> {
            for (long chunkKey : chunkKeys) {
                if (!hasChunk(chunkKey)) {
                    // Trigger chunk generation/loading
                    ChunkPos pos = new ChunkPos(chunkKey);
                }
            }
        });
    }
    
    public double getCacheHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        
        return total > 0 ? (double) hits / total : 0.0;
    }
    
    public int getMemoryCacheSize() {
        return memoryCache.size();
    }
    
    public long getPersistentCacheSize() {
        return persistentCache.size();
    }
    
    public long getCacheHits() {
        return cacheHits.get();
    }
    
    public long getCacheMisses() {
        return cacheMisses.get();
    }
    
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            persistentCache.close();
        } catch (Exception e) {
            KiumMod.LOGGER.error("Error closing persistent cache", e);
        }
    }
    
    public static class ChunkData {
        public final byte[] meshData;
        public final int lodLevel;
        public final long timestamp;
        
        public ChunkData(byte[] meshData, int lodLevel, long timestamp) {
            this.meshData = meshData;
            this.lodLevel = lodLevel;
            this.timestamp = timestamp;
        }
    }
    
    private static class CompressedChunkData {
        private final byte[] compressedData;
        private final int lodLevel;
        private final long timestamp;
        
        public CompressedChunkData(byte[] data, int lodLevel, long timestamp) {
            this.compressedData = compress(data);
            this.lodLevel = lodLevel;
            this.timestamp = timestamp;
        }
        
        public CompressedChunkData(ChunkData data) {
            this(data.meshData, data.lodLevel, data.timestamp);
        }
        
        private static byte[] compress(byte[] data) {
            // Simple run-length encoding for mesh data
            if (data.length == 0) return data;
            
            ByteBuffer buffer = ByteBuffer.allocate(data.length * 2);
            byte current = data[0];
            int count = 1;
            
            for (int i = 1; i < data.length; i++) {
                if (data[i] == current && count < 255) {
                    count++;
                } else {
                    buffer.put(current);
                    buffer.put((byte) count);
                    current = data[i];
                    count = 1;
                }
            }
            
            buffer.put(current);
            buffer.put((byte) count);
            
            byte[] compressed = new byte[buffer.position()];
            buffer.rewind();
            buffer.get(compressed);
            
            return compressed;
        }
        
        public ChunkData decompress() {
            if (compressedData.length == 0) {
                return new ChunkData(new byte[0], lodLevel, timestamp);
            }
            
            ByteBuffer buffer = ByteBuffer.allocate(compressedData.length * 4);
            
            for (int i = 0; i < compressedData.length; i += 2) {
                byte value = compressedData[i];
                byte count = compressedData[i + 1];
                
                for (int j = 0; j < count; j++) {
                    buffer.put(value);
                }
            }
            
            byte[] decompressed = new byte[buffer.position()];
            buffer.rewind();
            buffer.get(decompressed);
            
            return new ChunkData(decompressed, lodLevel, timestamp);
        }
    }
}