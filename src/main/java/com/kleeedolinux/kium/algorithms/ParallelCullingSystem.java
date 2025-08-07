package com.kleeedolinux.kium.algorithms;

import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.ChunkPos;
import com.kleeedolinux.kium.KiumMod;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import it.unimi.dsi.fastutil.longs.*;

public class ParallelCullingSystem {
    private static final int CULLING_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final int BATCH_SIZE = 64; // Process chunks in batches
    private static final int OCCLUSION_SAMPLES = 8; // Occlusion testing samples
    
    private final ForkJoinPool cullingPool;
    private final ThreadPoolExecutor hierarchicalCuller;
    private final ConcurrentHashMap<Long, CullingResult> cullingCache;
    
    private final AtomicLong totalCullingOperations = new AtomicLong(0);
    private final AtomicLong culledChunks = new AtomicLong(0);
    private final AtomicLong occlusionTests = new AtomicLong(0);
    
    // Frustum planes for parallel culling
    private volatile FrustumData currentFrustum;
    private final Object frustumLock = new Object();
    
    // Camera tracking for shouldRenderChunk method
    private volatile Vec3d lastCameraPos = null;
    private volatile int maxRenderDistance = 256;
    
    public ParallelCullingSystem() {
        this.cullingPool = new ForkJoinPool(CULLING_THREADS, 
            ForkJoinPool.defaultForkJoinWorkerThreadFactory, 
            null, true);
            
        this.hierarchicalCuller = new ThreadPoolExecutor(
            CULLING_THREADS / 2, CULLING_THREADS,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Kium-HierarchicalCuller-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.MAX_PRIORITY - 1);
                    return t;
                }
            }
        );
        
        this.cullingCache = new ConcurrentHashMap<>(4096);
        
        KiumMod.LOGGER.info("Parallel culling system initialized with {} threads", CULLING_THREADS);
    }
    
    public CompletableFuture<CullingResults> cullChunksParallel(LongSet candidateChunks, Vec3d cameraPos, 
                                                               FrustumData frustum, int maxDistance) {
        
        updateFrustum(frustum);
        updateCameraPosition(cameraPos, maxDistance);
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            // Convert to list for parallel processing
            List<Long> chunkList = new ArrayList<>(candidateChunks);
            CullingResults results = new CullingResults();
            
            // Parallel hierarchical culling
            performHierarchicalCulling(chunkList, cameraPos, maxDistance, results);
            
            // Parallel frustum culling
            performFrustumCulling(results.hierarchicallyVisible, cameraPos, results);
            
            // Parallel occlusion culling
            performOcclusionCulling(results.frustumVisible, cameraPos, results);
            
            long endTime = System.nanoTime();
            results.cullingTime = (endTime - startTime) / 1_000_000.0;
            
            totalCullingOperations.incrementAndGet();
            culledChunks.addAndGet(candidateChunks.size() - results.finalVisible.size());
            
            return results;
        }, cullingPool);
    }
    
    private void updateFrustum(FrustumData frustum) {
        synchronized (frustumLock) {
            this.currentFrustum = frustum;
        }
    }
    
    private void updateCameraPosition(Vec3d cameraPos, int maxDistance) {
        this.lastCameraPos = cameraPos;
        this.maxRenderDistance = maxDistance;
    }
    
    private void performHierarchicalCulling(List<Long> chunks, Vec3d cameraPos, int maxDistance, 
                                          CullingResults results) {
        
        // Create hierarchical culling task
        HierarchicalCullingTask task = new HierarchicalCullingTask(
            chunks, cameraPos, maxDistance, 0, chunks.size(), results.hierarchicallyVisible
        );
        
        cullingPool.invoke(task);
    }
    
    private void performFrustumCulling(LongSet chunks, Vec3d cameraPos, CullingResults results) {
        if (chunks.isEmpty()) return;
        
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        List<Long> chunkList = new ArrayList<>(chunks);
        
        // Split into batches for parallel processing
        for (int i = 0; i < chunkList.size(); i += BATCH_SIZE) {
            final int start = i;
            final int end = Math.min(i + BATCH_SIZE, chunkList.size());
            
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                frustumCullBatch(chunkList.subList(start, end), cameraPos, results.frustumVisible);
            }, cullingPool);
            
            tasks.add(task);
        }
        
        // Wait for all batches to complete
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
    }
    
    private void performOcclusionCulling(LongSet chunks, Vec3d cameraPos, CullingResults results) {
        if (chunks.isEmpty()) return;
        
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        List<Long> chunkList = new ArrayList<>(chunks);
        
        // Sort chunks by distance for better occlusion testing
        chunkList.sort((a, b) -> {
            double distA = calculateChunkDistance(new ChunkPos(a), cameraPos);
            double distB = calculateChunkDistance(new ChunkPos(b), cameraPos);
            return Double.compare(distA, distB);
        });
        
        // Parallel occlusion testing
        for (int i = 0; i < chunkList.size(); i += BATCH_SIZE) {
            final int start = i;
            final int end = Math.min(i + BATCH_SIZE, chunkList.size());
            
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                occlusionCullBatch(chunkList.subList(start, end), cameraPos, results.finalVisible, chunkList);
            }, cullingPool);
            
            tasks.add(task);
        }
        
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
    }
    
    private void frustumCullBatch(List<Long> chunks, Vec3d cameraPos, LongSet visibleSet) {
        FrustumData frustum;
        synchronized (frustumLock) {
            frustum = currentFrustum;
        }
        
        if (frustum == null) return;
        
        for (long chunkKey : chunks) {
            ChunkPos pos = new ChunkPos(chunkKey);
            
            if (isChunkInFrustumFast(pos, cameraPos, frustum)) {
                synchronized (visibleSet) {
                    visibleSet.add(chunkKey);
                }
            }
        }
    }
    
    private void occlusionCullBatch(List<Long> chunks, Vec3d cameraPos, LongSet visibleSet, List<Long> allChunks) {
        for (long chunkKey : chunks) {
            ChunkPos pos = new ChunkPos(chunkKey);
            
            if (!isChunkOccluded(pos, cameraPos, allChunks)) {
                synchronized (visibleSet) {
                    visibleSet.add(chunkKey);
                }
            }
        }
    }
    
    private boolean isChunkInFrustumFast(ChunkPos pos, Vec3d cameraPos, FrustumData frustum) {
        // Fast AABB-Frustum intersection test
        double minX = pos.x << 4;
        double maxX = minX + 16;
        double minZ = pos.z << 4;
        double maxZ = minZ + 16;
        double minY = -64;
        double maxY = 320;
        
        // Test against all 6 frustum planes
        for (PlaneData plane : frustum.planes) {
            if (plane.distanceToAABB(minX, minY, minZ, maxX, maxY, maxZ) < 0) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean isChunkOccluded(ChunkPos chunkPos, Vec3d cameraPos, List<Long> allChunks) {
        occlusionTests.incrementAndGet();
        
        double chunkCenterX = (chunkPos.x << 4) + 8;
        double chunkCenterZ = (chunkPos.z << 4) + 8;
        double chunkCenterY = 128; // Approximate chunk center Y
        
        // Simple occlusion test using ray sampling
        int occludedSamples = 0;
        
        for (int sample = 0; sample < OCCLUSION_SAMPLES; sample++) {
            // Generate sample points around chunk center
            double sampleX = chunkCenterX + (sample % 3 - 1) * 5;
            double sampleZ = chunkCenterZ + ((sample / 3) % 3 - 1) * 5;
            double sampleY = chunkCenterY + (sample / 9 - 1) * 20;
            
            if (isRayOccluded(cameraPos, new Vec3d(sampleX, sampleY, sampleZ), allChunks, chunkPos)) {
                occludedSamples++;
            }
        }
        
        // Chunk is occluded if most samples are blocked
        return occludedSamples > OCCLUSION_SAMPLES * 0.7;
    }
    
    private boolean isRayOccluded(Vec3d rayStart, Vec3d rayEnd, List<Long> allChunks, ChunkPos targetChunk) {
        // Fast ray-AABB intersection for occlusion testing
        Vec3d rayDir = rayEnd.subtract(rayStart).normalize();
        double rayLength = rayStart.distanceTo(rayEnd);
        
        for (long chunkKey : allChunks) {
            ChunkPos pos = new ChunkPos(chunkKey);
            
            // Don't test against the target chunk itself
            if (pos.equals(targetChunk)) continue;
            
            // Simple distance check - only test nearby chunks
            double chunkDist = calculateChunkDistance(pos, rayStart);
            if (chunkDist > rayLength) continue;
            
            // AABB intersection test
            if (rayIntersectsChunk(rayStart, rayDir, rayLength, pos)) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean rayIntersectsChunk(Vec3d rayStart, Vec3d rayDir, double rayLength, ChunkPos chunkPos) {
        // Fast ray-AABB intersection
        double minX = chunkPos.x << 4;
        double maxX = minX + 16;
        double minZ = chunkPos.z << 4;
        double maxZ = minZ + 16;
        double minY = 0; // Simplified - assume chunks have geometry from 0-128
        double maxY = 128;
        
        double tMinX = (minX - rayStart.x) / rayDir.x;
        double tMaxX = (maxX - rayStart.x) / rayDir.x;
        
        if (tMinX > tMaxX) {
            double temp = tMinX;
            tMinX = tMaxX;
            tMaxX = temp;
        }
        
        double tMinY = (minY - rayStart.y) / rayDir.y;
        double tMaxY = (maxY - rayStart.y) / rayDir.y;
        
        if (tMinY > tMaxY) {
            double temp = tMinY;
            tMinY = tMaxY;
            tMaxY = temp;
        }
        
        double tMinZ = (minZ - rayStart.z) / rayDir.z;
        double tMaxZ = (maxZ - rayStart.z) / rayDir.z;
        
        if (tMinZ > tMaxZ) {
            double temp = tMinZ;
            tMinZ = tMaxZ;
            tMaxZ = temp;
        }
        
        double tMin = Math.max(Math.max(tMinX, tMinY), tMinZ);
        double tMax = Math.min(Math.min(tMaxX, tMaxY), tMaxZ);
        
        return tMax >= 0 && tMin <= tMax && tMin <= rayLength;
    }
    
    private double calculateChunkDistance(ChunkPos pos, Vec3d cameraPos) {
        double chunkCenterX = (pos.x << 4) + 8;
        double chunkCenterZ = (pos.z << 4) + 8;
        
        return Math.sqrt(
            Math.pow(chunkCenterX - cameraPos.x, 2) +
            Math.pow(chunkCenterZ - cameraPos.z, 2)
        );
    }
    
    public CullingResult getCachedResult(long chunkKey, Vec3d cameraPos) {
        CullingResult cached = cullingCache.get(chunkKey);
        if (cached != null && cached.isValid(cameraPos)) {
            return cached;
        }
        return null;
    }
    
    public void cacheResult(long chunkKey, boolean visible, Vec3d cameraPos) {
        cullingCache.put(chunkKey, new CullingResult(visible, cameraPos, System.currentTimeMillis()));
    }
    
    public void cleanupCache() {
        long currentTime = System.currentTimeMillis();
        cullingCache.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().timestamp > 5000); // 5 second cache
    }
    
    public long getTotalCullingOperations() {
        return totalCullingOperations.get();
    }
    
    public long getCulledChunks() {
        return culledChunks.get();
    }
    
    public long getOcclusionTests() {
        return occlusionTests.get();
    }
    
    public double getCullingEfficiency() {
        long total = totalCullingOperations.get();
        long culled = culledChunks.get();
        return total > 0 ? (double) culled / total : 0.0;
    }
    
    public void shutdown() {
        cullingPool.shutdown();
        hierarchicalCuller.shutdown();
        
        try {
            if (!cullingPool.awaitTermination(5, TimeUnit.SECONDS)) {
                cullingPool.shutdownNow();
            }
            if (!hierarchicalCuller.awaitTermination(5, TimeUnit.SECONDS)) {
                hierarchicalCuller.shutdownNow();
            }
        } catch (InterruptedException e) {
            cullingPool.shutdownNow();
            hierarchicalCuller.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    private class HierarchicalCullingTask extends RecursiveTask<Void> {
        private final List<Long> chunks;
        private final Vec3d cameraPos;
        private final int maxDistance;
        private final int start;
        private final int end;
        private final LongSet result;
        
        HierarchicalCullingTask(List<Long> chunks, Vec3d cameraPos, int maxDistance, 
                              int start, int end, LongSet result) {
            this.chunks = chunks;
            this.cameraPos = cameraPos;
            this.maxDistance = maxDistance;
            this.start = start;
            this.end = end;
            this.result = result;
        }
        
        @Override
        protected Void compute() {
            if (end - start <= BATCH_SIZE) {
                // Process directly
                for (int i = start; i < end; i++) {
                    long chunkKey = chunks.get(i);
                    ChunkPos pos = new ChunkPos(chunkKey);
                    
                    double distance = calculateChunkDistance(pos, cameraPos);
                    if (distance <= maxDistance) {
                        synchronized (result) {
                            result.add(chunkKey);
                        }
                    }
                }
            } else {
                // Split and fork
                int mid = (start + end) / 2;
                HierarchicalCullingTask left = new HierarchicalCullingTask(
                    chunks, cameraPos, maxDistance, start, mid, result);
                HierarchicalCullingTask right = new HierarchicalCullingTask(
                    chunks, cameraPos, maxDistance, mid, end, result);
                
                invokeAll(left, right);
            }
            
            return null;
        }
    }
    
    public static class FrustumData {
        final PlaneData[] planes = new PlaneData[6];
        final long timestamp;
        
        public FrustumData(Vec3d cameraPos, float yaw, float pitch, float fov) {
            this.timestamp = System.currentTimeMillis();
            calculateFrustumPlanes(cameraPos, yaw, pitch, fov);
        }
        
        private void calculateFrustumPlanes(Vec3d cameraPos, float yaw, float pitch, float fov) {
            // Calculate frustum planes from camera parameters
            // This is a simplified version - real implementation would be more complex
            
            for (int i = 0; i < 6; i++) {
                planes[i] = new PlaneData();
            }
            
            // Set up frustum planes based on camera parameters
            // Top, Bottom, Left, Right, Near, Far
            float yawRad = (float) Math.toRadians(yaw);
            float pitchRad = (float) Math.toRadians(pitch);
            
            Vec3d forward = new Vec3d(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad)
            );
            
            Vec3d right = new Vec3d(Math.cos(yawRad), 0, Math.sin(yawRad));
            Vec3d up = forward.crossProduct(right).normalize();
            
            // Calculate frustum planes (simplified)
            planes[0].set(up.x, up.y, up.z, -up.dotProduct(cameraPos)); // Top
            planes[1].set(-up.x, -up.y, -up.z, up.dotProduct(cameraPos)); // Bottom  
            planes[2].set(right.x, right.y, right.z, -right.dotProduct(cameraPos)); // Right
            planes[3].set(-right.x, -right.y, -right.z, right.dotProduct(cameraPos)); // Left
            planes[4].set(forward.x, forward.y, forward.z, -forward.dotProduct(cameraPos.add(forward.multiply(0.1)))); // Near
            planes[5].set(-forward.x, -forward.y, -forward.z, forward.dotProduct(cameraPos.add(forward.multiply(1024)))); // Far
        }
    }
    
    public static class PlaneData {
        double a, b, c, d;
        
        void set(double a, double b, double c, double d) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
        }
        
        double distanceToAABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            double px = a >= 0 ? maxX : minX;
            double py = b >= 0 ? maxY : minY;
            double pz = c >= 0 ? maxZ : minZ;
            
            return a * px + b * py + c * pz + d;
        }
    }
    
    public static class CullingResults {
        final LongSet hierarchicallyVisible = new LongOpenHashSet();
        final LongSet frustumVisible = new LongOpenHashSet();
        final LongSet finalVisible = new LongOpenHashSet();
        double cullingTime = 0.0;
        
        public boolean isEmpty() {
            return finalVisible.isEmpty();
        }
        
        public int getTotalVisible() {
            return finalVisible.size();
        }
    }
    
    private static class CullingResult {
        final boolean visible;
        final Vec3d cameraPos;
        final long timestamp;
        
        CullingResult(boolean visible, Vec3d cameraPos, long timestamp) {
            this.visible = visible;
            this.cameraPos = cameraPos;
            this.timestamp = timestamp;
        }
        
        boolean isValid(Vec3d currentPos) {
            long age = System.currentTimeMillis() - timestamp;
            double distance = cameraPos.distanceTo(currentPos);
            return age < 1000 && distance < 16.0; // Valid for 1 second or if camera moved < 1 chunk
        }
    }
    
    // Method required by VanillaIntegration
    public boolean shouldRenderChunk(ChunkPos chunkPos) {
        // Simple implementation - in real use would check frustum and occlusion
        if (lastCameraPos == null) {
            return true; // Render if no camera data
        }
        
        // Calculate distance from camera
        double centerX = (chunkPos.x << 4) + 8.0;
        double centerZ = (chunkPos.z << 4) + 8.0;
        double distance = Math.sqrt(
            Math.pow(centerX - lastCameraPos.x, 2) + 
            Math.pow(centerZ - lastCameraPos.z, 2)
        );
        
        // Don't render chunks beyond reasonable distance
        return distance <= maxRenderDistance * 16.0;
    }
}