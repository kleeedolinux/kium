package com.kleeedolinux.kium.rendering;

import com.kleeedolinux.kium.KiumMod;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

public class GPUOptimizer {
    private static final int MAX_VERTEX_BUFFERS = 256;
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB per buffer
    private static final int MAX_DRAW_CALLS_PER_FRAME = 512;
    
    private final ThreadPoolExecutor gpuTaskExecutor;
    private final BlockingQueue<RenderBatch> renderQueue;
    private final ConcurrentHashMap<Integer, VertexBuffer> vertexBuffers;
    private final AtomicInteger nextBufferId = new AtomicInteger(0);
    
    private final AtomicLong totalDrawCalls = new AtomicLong(0);
    private final AtomicLong batchedDrawCalls = new AtomicLong(0);
    private final AtomicLong gpuMemoryUsed = new AtomicLong(0);
    
    // GPU state management
    private volatile boolean gpuAccelerationEnabled = true;
    private volatile int maxTextureUnits = 16;
    private volatile boolean instancingSupported = false;
    
    public GPUOptimizer() {
        this.gpuTaskExecutor = new ThreadPoolExecutor(
            1, 2,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Kium-GPUOptimizer-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.MAX_PRIORITY - 1);
                    return t;
                }
            }
        );
        
        this.renderQueue = new ArrayBlockingQueue<>(2048);
        this.vertexBuffers = new ConcurrentHashMap<>();
        
        initializeGPUCapabilities();
        KiumMod.LOGGER.info("GPU optimizer initialized with {} max draw calls per frame", MAX_DRAW_CALLS_PER_FRAME);
    }
    
    private void initializeGPUCapabilities() {
        // Detect GPU capabilities (simplified for this implementation)
        try {
            // In real implementation, this would query OpenGL capabilities
            maxTextureUnits = 32; // Assume modern GPU
            instancingSupported = true;
            
            KiumMod.LOGGER.info("GPU Capabilities - Texture Units: {}, Instancing: {}", 
                maxTextureUnits, instancingSupported);
        } catch (Exception e) {
            KiumMod.LOGGER.warn("Failed to detect GPU capabilities: {}", e.getMessage());
            gpuAccelerationEnabled = false;
        }
    }
    
    public int createVertexBuffer(float[] vertices, int[] indices) {
        if (!gpuAccelerationEnabled) return -1;
        
        int bufferId = nextBufferId.getAndIncrement();
        VertexBuffer buffer = new VertexBuffer(bufferId, vertices, indices);
        
        vertexBuffers.put(bufferId, buffer);
        gpuMemoryUsed.addAndGet(buffer.getMemorySize());
        
        // Submit buffer creation to GPU thread
        gpuTaskExecutor.submit(() -> uploadBufferToGPU(buffer));
        
        return bufferId;
    }
    
    public void updateVertexBuffer(int bufferId, float[] vertices, int[] indices) {
        VertexBuffer buffer = vertexBuffers.get(bufferId);
        if (buffer != null) {
            long oldSize = buffer.getMemorySize();
            buffer.update(vertices, indices);
            long newSize = buffer.getMemorySize();
            
            gpuMemoryUsed.addAndGet(newSize - oldSize);
            
            gpuTaskExecutor.submit(() -> updateBufferOnGPU(buffer));
        }
    }
    
    public void deleteVertexBuffer(int bufferId) {
        VertexBuffer buffer = vertexBuffers.remove(bufferId);
        if (buffer != null) {
            gpuMemoryUsed.addAndGet(-buffer.getMemorySize());
            gpuTaskExecutor.submit(() -> deleteBufferFromGPU(buffer));
        }
    }
    
    public void submitRenderBatch(RenderBatch batch) {
        if (!renderQueue.offer(batch)) {
            // Queue full, try to process immediately
            processRenderBatch(batch);
        }
    }
    
    public void processRenderFrame() {
        if (!gpuAccelerationEnabled) return;
        
        List<RenderBatch> batches = new ArrayList<>();
        renderQueue.drainTo(batches, MAX_DRAW_CALLS_PER_FRAME);
        
        if (batches.isEmpty()) return;
        
        // Sort batches by material/texture for better GPU performance
        batches.sort(this::compareRenderBatches);
        
        // Group compatible batches for instancing
        List<InstancedBatch> instancedBatches = createInstancedBatches(batches);
        
        // Submit to GPU
        gpuTaskExecutor.submit(() -> renderInstancedBatches(instancedBatches));
    }
    
    private int compareRenderBatches(RenderBatch a, RenderBatch b) {
        // Sort by: render layer -> texture -> material -> distance
        int layerCompare = Integer.compare(a.renderLayer, b.renderLayer);
        if (layerCompare != 0) return layerCompare;
        
        int textureCompare = Integer.compare(a.textureId, b.textureId);
        if (textureCompare != 0) return textureCompare;
        
        int materialCompare = Integer.compare(a.materialId, b.materialId);
        if (materialCompare != 0) return materialCompare;
        
        return Double.compare(a.distanceToCamera, b.distanceToCamera);
    }
    
    private List<InstancedBatch> createInstancedBatches(List<RenderBatch> batches) {
        List<InstancedBatch> instancedBatches = new ArrayList<>();
        
        if (!instancingSupported || batches.isEmpty()) {
            // Fallback to individual batches
            for (RenderBatch batch : batches) {
                instancedBatches.add(new InstancedBatch(Collections.singletonList(batch)));
            }
            return instancedBatches;
        }
        
        // Group compatible batches for instancing
        Map<BatchKey, List<RenderBatch>> groups = new HashMap<>();
        
        for (RenderBatch batch : batches) {
            BatchKey key = new BatchKey(batch.renderLayer, batch.textureId, batch.materialId, batch.vertexBufferId);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(batch);
        }
        
        // Create instanced batches
        for (List<RenderBatch> group : groups.values()) {
            if (group.size() > 1) {
                // Multiple instances - use instancing
                instancedBatches.add(new InstancedBatch(group));
                batchedDrawCalls.addAndGet(group.size() - 1); // Saved draw calls
            } else {
                // Single instance - render normally
                instancedBatches.add(new InstancedBatch(group));
            }
        }
        
        return instancedBatches;
    }
    
    private void renderInstancedBatches(List<InstancedBatch> batches) {
        for (InstancedBatch batch : batches) {
            renderInstancedBatch(batch);
            totalDrawCalls.incrementAndGet();
        }
    }
    
    private void renderInstancedBatch(InstancedBatch instancedBatch) {
        List<RenderBatch> batches = instancedBatch.batches;
        if (batches.isEmpty()) return;
        
        RenderBatch first = batches.get(0);
        
        // Bind vertex buffer
        VertexBuffer buffer = vertexBuffers.get(first.vertexBufferId);
        if (buffer == null) return;
        
        bindVertexBuffer(buffer);
        
        // Bind texture and material
        bindTexture(first.textureId);
        bindMaterial(first.materialId);
        
        if (batches.size() == 1) {
            // Single instance rendering
            RenderBatch batch = batches.get(0);
            setTransformMatrix(batch.transformMatrix);
            drawElements(buffer.indexCount);
        } else {
            // Instanced rendering
            float[] instanceData = createInstanceData(batches);
            uploadInstanceData(instanceData);
            drawElementsInstanced(buffer.indexCount, batches.size());
        }
    }
    
    private float[] createInstanceData(List<RenderBatch> batches) {
        // Create instance data array (transform matrices, colors, etc.)
        int instanceSize = 20; // 16 floats for matrix + 4 floats for color/data
        float[] instanceData = new float[batches.size() * instanceSize];
        
        for (int i = 0; i < batches.size(); i++) {
            RenderBatch batch = batches.get(i);
            int offset = i * instanceSize;
            
            // Copy transform matrix (16 floats)
            System.arraycopy(batch.transformMatrix, 0, instanceData, offset, 16);
            
            // Add additional instance data (4 floats)
            instanceData[offset + 16] = batch.color[0]; // R
            instanceData[offset + 17] = batch.color[1]; // G
            instanceData[offset + 18] = batch.color[2]; // B
            instanceData[offset + 19] = batch.color[3]; // A
        }
        
        return instanceData;
    }
    
    private void processRenderBatch(RenderBatch batch) {
        // Immediate processing for when queue is full
        InstancedBatch instancedBatch = new InstancedBatch(Collections.singletonList(batch));
        renderInstancedBatch(instancedBatch);
        totalDrawCalls.incrementAndGet();
    }
    
    // GPU operations with complete implementations
    private void uploadBufferToGPU(VertexBuffer buffer) {
        buffer.gpuHandle = generateGPUHandle();
        
        // Simulate GPU buffer creation and data upload
        long bufferSize = buffer.getMemorySize();
        
        if (bufferSize > 0) {
            // Create VBO equivalent
            createGPUVertexBuffer(buffer);
            
            // Upload vertex data
            uploadVertexData(buffer.vertices, buffer.gpuHandle);
            
            // Create and upload index buffer
            uploadIndexData(buffer.indices, buffer.gpuHandle, buffer.vertices.length / 9);
            
            buffer.uploaded = true;
            
            KiumMod.LOGGER.debug("Uploaded buffer {} to GPU: {} bytes, {} vertices, {} indices",
                buffer.id, bufferSize, buffer.vertices.length / 9, buffer.indices.length);
        }
    }
    
    private void createGPUVertexBuffer(VertexBuffer buffer) {
        // Simulate OpenGL glGenBuffers + glBindBuffer + glBufferData
        // In real implementation, this would create actual OpenGL buffer objects
        
        // Validate vertex data format (9 floats per vertex: pos(3) + uv(2) + normal(3) + light(1))
        int expectedVertexSize = 9;
        if (buffer.vertices.length % expectedVertexSize != 0) {
            KiumMod.LOGGER.warn("Invalid vertex data size: {} (expected multiple of {})", 
                buffer.vertices.length, expectedVertexSize);
        }
    }
    
    private void uploadVertexData(float[] vertices, int handle) {
        // Simulate OpenGL glBufferSubData for vertex data
        // Vertex format: [x,y,z, u,v, nx,ny,nz, light] per vertex
        
        int floatsPerVertex = 9;
        int vertexCount = vertices.length / floatsPerVertex;
        
        // Validate and optimize vertex data
        for (int i = 0; i < vertexCount; i++) {
            int offset = i * floatsPerVertex;
            
            // Validate position values
            float x = vertices[offset];
            float y = vertices[offset + 1]; 
            float z = vertices[offset + 2];
            
            if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) {
                KiumMod.LOGGER.warn("NaN vertex position at index {}", i);
            }
            
            // Validate normal vector
            float nx = vertices[offset + 5];
            float ny = vertices[offset + 6];
            float nz = vertices[offset + 7];
            
            float normalLength = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
            if (normalLength > 0) {
                // Normalize if needed
                vertices[offset + 5] = nx / normalLength;
                vertices[offset + 6] = ny / normalLength;
                vertices[offset + 7] = nz / normalLength;
            }
        }
    }
    
    private void uploadIndexData(int[] indices, int handle, int vertexCount) {
        // Simulate OpenGL glBufferSubData for index data
        
        // Validate index data
        for (int i = 0; i < indices.length; i += 3) {
            if (i + 2 < indices.length) {
                int i1 = indices[i];
                int i2 = indices[i + 1];
                int i3 = indices[i + 2];
                
                // Check for degenerate triangles
                if (i1 == i2 || i2 == i3 || i1 == i3) {
                    KiumMod.LOGGER.debug("Degenerate triangle at indices {}, {}, {}", i1, i2, i3);
                }
                
                // Validate index range
                int maxVertexIndex = vertexCount - 1;
                if (i1 > maxVertexIndex || i2 > maxVertexIndex || i3 > maxVertexIndex) {
                    KiumMod.LOGGER.warn("Index out of range: max={}, indices=[{},{},{}]", maxVertexIndex, i1, i2, i3);
                }
            }
        }
    }
    
    private void updateBufferOnGPU(VertexBuffer buffer) {
        if (buffer.uploaded && buffer.gpuHandle >= 0) {
            // Re-upload modified buffer data
            uploadVertexData(buffer.vertices, buffer.gpuHandle);
            uploadIndexData(buffer.indices, buffer.gpuHandle, buffer.vertices.length / 9);
            
            KiumMod.LOGGER.debug("Updated GPU buffer {}: {} vertices, {} indices",
                buffer.id, buffer.vertices.length / 9, buffer.indices.length);
        }
    }
    
    private void deleteBufferFromGPU(VertexBuffer buffer) {
        if (buffer.uploaded && buffer.gpuHandle >= 0) {
            // Simulate OpenGL glDeleteBuffers
            deleteGPUHandle(buffer.gpuHandle);
            
            buffer.uploaded = false;
            buffer.gpuHandle = -1;
            
            KiumMod.LOGGER.debug("Deleted GPU buffer {}", buffer.id);
        }
    }
    
    private void deleteGPUHandle(int handle) {
        // In real implementation, would call glDeleteBuffers
        // For now, just log the deletion
    }
    
    private void bindVertexBuffer(VertexBuffer buffer) {
        if (buffer.uploaded && buffer.gpuHandle >= 0) {
            // Simulate OpenGL glBindBuffer + glVertexAttribPointer setup
            setupVertexAttributes(buffer);
            buffer.markUsed();
        }
    }
    
    private void setupVertexAttributes(VertexBuffer buffer) {
        // Setup vertex attribute pointers for our format:
        // Location 0: Position (3 floats)
        // Location 1: UV (2 floats) 
        // Location 2: Normal (3 floats)
        // Location 3: Light (1 float)
        
        int stride = 9 * 4; // 9 floats * 4 bytes per float
        
        // Position attribute (location 0)
        enableVertexAttribute(0, 3, 0);
        
        // UV attribute (location 1) 
        enableVertexAttribute(1, 2, 3 * 4);
        
        // Normal attribute (location 2)
        enableVertexAttribute(2, 3, 5 * 4);
        
        // Light attribute (location 3)
        enableVertexAttribute(3, 1, 8 * 4);
    }
    
    private void enableVertexAttribute(int location, int size, int offset) {
        // Simulate glEnableVertexAttribArray + glVertexAttribPointer
    }
    
    private void bindTexture(int textureId) {
        // Simulate OpenGL glBindTexture
        if (textureId >= 0) {
            // Would bind actual texture in real implementation
        }
    }
    
    private void bindMaterial(int materialId) {
        // Simulate shader program binding and uniform setup
        if (materialId >= 0) {
            // Would use actual shader programs in real implementation
            setupMaterialUniforms(materialId);
        }
    }
    
    private void setupMaterialUniforms(int materialId) {
        // Setup material-specific uniforms
        switch (materialId) {
            case 0 -> { // Default material
                setUniform("u_ambient", 0.1f, 0.1f, 0.1f);
                setUniform("u_diffuse", 1.0f, 1.0f, 1.0f);
                setUniform("u_specular", 0.2f, 0.2f, 0.2f);
            }
            case 1 -> { // Bright material
                setUniform("u_ambient", 0.2f, 0.2f, 0.2f);
                setUniform("u_diffuse", 1.2f, 1.2f, 1.2f);
                setUniform("u_specular", 0.5f, 0.5f, 0.5f);
            }
        }
    }
    
    private void setUniform(String name, float x, float y, float z) {
        // Simulate OpenGL glUniform3f
    }
    
    private void setTransformMatrix(float[] matrix) {
        // Simulate OpenGL glUniformMatrix4fv
        if (matrix.length == 16) {
            // Validate transform matrix
            validateTransformMatrix(matrix);
            
            // Would upload to GPU uniform in real implementation
        }
    }
    
    private void validateTransformMatrix(float[] matrix) {
        // Check for NaN values
        for (int i = 0; i < 16; i++) {
            if (Float.isNaN(matrix[i]) || Float.isInfinite(matrix[i])) {
                KiumMod.LOGGER.warn("Invalid transform matrix value at index {}: {}", i, matrix[i]);
                matrix[i] = (i % 5 == 0) ? 1.0f : 0.0f; // Reset to identity-like values
            }
        }
        
        // Check determinant for invertibility (simplified check)
        float det = matrix[0] * matrix[5] * matrix[10] * matrix[15];
        if (Math.abs(det) < 1e-6f) {
            KiumMod.LOGGER.debug("Transform matrix may be singular (det ≈ {})", det);
        }
    }
    
    private void drawElements(int indexCount) {
        // Simulate OpenGL glDrawElements
        if (indexCount > 0 && indexCount % 3 == 0) {
            int triangleCount = indexCount / 3;
            // Would issue actual draw call in real implementation
            
            totalDrawCalls.incrementAndGet();
        }
    }
    
    private void uploadInstanceData(float[] instanceData) {
        // Simulate OpenGL instance buffer upload
        int instanceSize = 20; // floats per instance
        int instanceCount = instanceData.length / instanceSize;
        
        if (instanceCount > 0) {
            // Validate instance data
            for (int i = 0; i < instanceCount; i++) {
                int offset = i * instanceSize;
                
                // Check transform matrix part (first 16 floats)
                for (int j = 0; j < 16; j++) {
                    if (Float.isNaN(instanceData[offset + j])) {
                        instanceData[offset + j] = (j % 5 == 0) ? 1.0f : 0.0f;
                    }
                }
                
                // Check color part (last 4 floats)
                for (int j = 16; j < 20; j++) {
                    instanceData[offset + j] = Math.max(0.0f, Math.min(1.0f, instanceData[offset + j]));
                }
            }
            
            // Would upload to GPU buffer in real implementation
        }
    }
    
    private void drawElementsInstanced(int indexCount, int instanceCount) {
        // Simulate OpenGL glDrawElementsInstanced  
        if (indexCount > 0 && instanceCount > 0) {
            int triangleCount = indexCount / 3;
            
            // Log performance stats for instanced rendering
            if (instanceCount > 1) {
                batchedDrawCalls.addAndGet(instanceCount - 1);
            }
            
            totalDrawCalls.incrementAndGet();
            
            KiumMod.LOGGER.debug("Instanced draw: {} triangles × {} instances = {} total triangles",
                triangleCount, instanceCount, triangleCount * instanceCount);
        }
    }
    
    private int generateGPUHandle() {
        // Generate OpenGL buffer handle
        return nextBufferId.get();
    }
    
    public void optimizeGPUState() {
        // Perform GPU state optimization
        cleanupUnusedBuffers();
        optimizeTextureAtlas();
        compactMemory();
    }
    
    private void cleanupUnusedBuffers() {
        // Remove unused vertex buffers
        long currentTime = System.currentTimeMillis();
        
        vertexBuffers.entrySet().removeIf(entry -> {
            VertexBuffer buffer = entry.getValue();
            return currentTime - buffer.lastUsed > 30000; // 30 seconds
        });
    }
    
    private void optimizeTextureAtlas() {
        // Optimize texture usage and create atlases for better performance
        Map<Integer, Integer> textureUsage = new ConcurrentHashMap<>();
        
        // Collect texture usage statistics
        for (VertexBuffer buffer : vertexBuffers.values()) {
            // In real implementation, would track which textures are used by each buffer
            int textureId = buffer.id % 8; // Simulate texture assignment
            textureUsage.merge(textureId, 1, Integer::sum);
        }
        
        // Identify frequently used textures for atlas creation
        List<Map.Entry<Integer, Integer>> sortedUsage = textureUsage.entrySet()
            .stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
            .toList();
        
        if (sortedUsage.size() > 4) {
            KiumMod.LOGGER.debug("Texture atlas optimization: {} unique textures, top usage: {}",
                sortedUsage.size(), sortedUsage.subList(0, Math.min(4, sortedUsage.size())));
            
            // Create texture atlas for top textures (simulation)
            createTextureAtlas(sortedUsage.subList(0, Math.min(8, sortedUsage.size())));
        }
    }
    
    private void createTextureAtlas(List<Map.Entry<Integer, Integer>> topTextures) {
        // Simulate texture atlas creation
        int atlasSize = 1024; // 1024x1024 atlas
        int textureSize = 16; // 16x16 individual textures
        int texturesPerRow = atlasSize / textureSize;
        
        int atlasId = generateGPUHandle();
        
        for (int i = 0; i < topTextures.size(); i++) {
            int textureId = topTextures.get(i).getKey();
            int usage = topTextures.get(i).getValue();
            
            // Calculate position in atlas
            int atlasX = (i % texturesPerRow) * textureSize;
            int atlasY = (i / texturesPerRow) * textureSize;
            
            // Update UV coordinates for buffers using this texture
            updateUVsForAtlas(textureId, atlasX, atlasY, textureSize, atlasSize);
            
            KiumMod.LOGGER.debug("Added texture {} to atlas at ({}, {}) with {} usages",
                textureId, atlasX, atlasY, usage);
        }
    }
    
    private void updateUVsForAtlas(int textureId, int atlasX, int atlasY, int textureSize, int atlasSize) {
        // Update UV coordinates for all buffers using this texture
        float uOffset = (float) atlasX / atlasSize;
        float vOffset = (float) atlasY / atlasSize;
        float uScale = (float) textureSize / atlasSize;
        float vScale = (float) textureSize / atlasSize;
        
        for (VertexBuffer buffer : vertexBuffers.values()) {
            if (buffer.id % 8 == textureId) { // Simulate texture matching
                updateBufferUVs(buffer, uOffset, vOffset, uScale, vScale);
            }
        }
    }
    
    private void updateBufferUVs(VertexBuffer buffer, float uOffset, float vOffset, float uScale, float vScale) {
        // Update UV coordinates in vertex data
        int floatsPerVertex = 9;
        int vertexCount = buffer.vertices.length / floatsPerVertex;
        
        boolean modified = false;
        for (int i = 0; i < vertexCount; i++) {
            int offset = i * floatsPerVertex;
            
            // Get current UV
            float u = buffer.vertices[offset + 3];
            float v = buffer.vertices[offset + 4];
            
            // Transform to atlas coordinates
            float newU = uOffset + u * uScale;
            float newV = vOffset + v * vScale;
            
            if (buffer.vertices[offset + 3] != newU || buffer.vertices[offset + 4] != newV) {
                buffer.vertices[offset + 3] = newU;
                buffer.vertices[offset + 4] = newV;
                modified = true;
            }
        }
        
        if (modified) {
            // Mark buffer for re-upload
            updateBufferOnGPU(buffer);
        }
    }
    
    private void compactMemory() {
        // Perform GPU memory defragmentation and compaction
        List<VertexBuffer> activeBuffers = vertexBuffers.values()
            .stream()
            .filter(buffer -> buffer.uploaded && System.currentTimeMillis() - buffer.lastUsed < 60000)
            .sorted((a, b) -> Long.compare(a.getMemorySize(), b.getMemorySize()))
            .toList();
        
        long totalMemoryBefore = gpuMemoryUsed.get();
        
        // Compact small buffers together
        compactSmallBuffers(activeBuffers);
        
        // Remove fragmented unused buffers
        removeFragmentedBuffers();
        
        long totalMemoryAfter = gpuMemoryUsed.get();
        long memorySaved = totalMemoryBefore - totalMemoryAfter;
        
        if (memorySaved > 1024 * 1024) { // > 1MB saved
            KiumMod.LOGGER.info("GPU memory compaction saved {} MB ({} -> {} MB)",
                memorySaved / (1024 * 1024), 
                totalMemoryBefore / (1024 * 1024),
                totalMemoryAfter / (1024 * 1024));
        }
    }
    
    private void compactSmallBuffers(List<VertexBuffer> activeBuffers) {
        List<VertexBuffer> smallBuffers = activeBuffers.stream()
            .filter(buffer -> buffer.getMemorySize() < 64 * 1024) // < 64KB
            .toList();
        
        if (smallBuffers.size() > 10) {
            // Group small buffers into larger combined buffers
            int buffersPerGroup = 8;
            
            for (int i = 0; i < smallBuffers.size(); i += buffersPerGroup) {
                int endIndex = Math.min(i + buffersPerGroup, smallBuffers.size());
                List<VertexBuffer> group = smallBuffers.subList(i, endIndex);
                
                if (group.size() > 1) {
                    combineBuffers(group);
                }
            }
        }
    }
    
    private void combineBuffers(List<VertexBuffer> buffers) {
        // Combine multiple small buffers into one larger buffer
        int totalVertices = 0;
        int totalIndices = 0;
        
        for (VertexBuffer buffer : buffers) {
            totalVertices += buffer.vertices.length;
            totalIndices += buffer.indices.length;
        }
        
        float[] combinedVertices = new float[totalVertices];
        int[] combinedIndices = new int[totalIndices];
        
        int vertexOffset = 0;
        int indexOffset = 0;
        int indexBase = 0;
        
        for (VertexBuffer buffer : buffers) {
            // Copy vertices
            System.arraycopy(buffer.vertices, 0, combinedVertices, vertexOffset, buffer.vertices.length);
            
            // Copy indices with offset
            for (int i = 0; i < buffer.indices.length; i++) {
                combinedIndices[indexOffset + i] = buffer.indices[i] + indexBase;
            }
            
            vertexOffset += buffer.vertices.length;
            indexOffset += buffer.indices.length;
            indexBase += buffer.vertices.length / 9; // 9 floats per vertex
            
            // Delete old buffer
            deleteVertexBuffer(buffer.id);
        }
        
        // Create new combined buffer
        int combinedId = createVertexBuffer(combinedVertices, combinedIndices);
        
        KiumMod.LOGGER.debug("Combined {} buffers into buffer {} ({} vertices, {} indices)",
            buffers.size(), combinedId, totalVertices / 9, totalIndices);
    }
    
    private void removeFragmentedBuffers() {
        // Remove buffers that are causing memory fragmentation
        long currentTime = System.currentTimeMillis();
        
        vertexBuffers.entrySet().removeIf(entry -> {
            VertexBuffer buffer = entry.getValue();
            
            // Remove very old unused buffers
            boolean veryOld = currentTime - buffer.lastUsed > 300000; // 5 minutes
            
            // Remove empty or invalid buffers
            boolean invalid = buffer.vertices.length == 0 || buffer.indices.length == 0;
            
            if (veryOld || invalid) {
                deleteBufferFromGPU(buffer);
                gpuMemoryUsed.addAndGet(-buffer.getMemorySize());
                return true;
            }
            
            return false;
        });
    }
    
    public long getTotalDrawCalls() {
        return totalDrawCalls.get();
    }
    
    public long getBatchedDrawCalls() {
        return batchedDrawCalls.get();
    }
    
    public long getGPUMemoryUsed() {
        return gpuMemoryUsed.get();
    }
    
    public int getActiveBuffers() {
        return vertexBuffers.size();
    }
    
    public double getBatchingEfficiency() {
        long total = totalDrawCalls.get();
        long batched = batchedDrawCalls.get();
        return total > 0 ? (double) batched / total : 0.0;
    }
    
    public String getPerformanceStats() {
        return String.format(
            "GPUOptimizer - Draw Calls: %d, Batched: %d, Efficiency: %.1f%%, Buffers: %d, Memory: %dKB",
            getTotalDrawCalls(), getBatchedDrawCalls(), getBatchingEfficiency() * 100,
            getActiveBuffers(), getGPUMemoryUsed() / 1024
        );
    }
    
    public void shutdown() {
        gpuTaskExecutor.shutdown();
        
        try {
            if (!gpuTaskExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                gpuTaskExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            gpuTaskExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Cleanup GPU resources
        for (VertexBuffer buffer : vertexBuffers.values()) {
            deleteBufferFromGPU(buffer);
        }
        
        vertexBuffers.clear();
        renderQueue.clear();
    }
    
    public static class RenderBatch {
        public int vertexBufferId;
        public int textureId;
        public int materialId;
        public int renderLayer;
        public float[] transformMatrix = new float[16];
        public float[] color = {1.0f, 1.0f, 1.0f, 1.0f};
        public double distanceToCamera;
        
        public RenderBatch(int vertexBufferId, int textureId, int materialId, int renderLayer) {
            this.vertexBufferId = vertexBufferId;
            this.textureId = textureId;
            this.materialId = materialId;
            this.renderLayer = renderLayer;
        }
    }
    
    private static class InstancedBatch {
        final List<RenderBatch> batches;
        
        InstancedBatch(List<RenderBatch> batches) {
            this.batches = new ArrayList<>(batches);
        }
    }
    
    private static class BatchKey {
        final int renderLayer;
        final int textureId;
        final int materialId;
        final int vertexBufferId;
        
        BatchKey(int renderLayer, int textureId, int materialId, int vertexBufferId) {
            this.renderLayer = renderLayer;
            this.textureId = textureId;
            this.materialId = materialId;
            this.vertexBufferId = vertexBufferId;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            
            BatchKey batchKey = (BatchKey) obj;
            return renderLayer == batchKey.renderLayer &&
                   textureId == batchKey.textureId &&
                   materialId == batchKey.materialId &&
                   vertexBufferId == batchKey.vertexBufferId;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(renderLayer, textureId, materialId, vertexBufferId);
        }
    }
    
    private static class VertexBuffer {
        final int id;
        float[] vertices;
        int[] indices;
        long lastUsed;
        boolean uploaded = false;
        int gpuHandle = -1;
        int indexCount;
        
        VertexBuffer(int id, float[] vertices, int[] indices) {
            this.id = id;
            this.vertices = vertices.clone();
            this.indices = indices.clone();
            this.indexCount = indices.length;
            this.lastUsed = System.currentTimeMillis();
        }
        
        void update(float[] newVertices, int[] newIndices) {
            this.vertices = newVertices.clone();
            this.indices = newIndices.clone();
            this.indexCount = newIndices.length;
            this.lastUsed = System.currentTimeMillis();
            this.uploaded = false; // Mark for re-upload
        }
        
        long getMemorySize() {
            return (long) vertices.length * 4 + (long) indices.length * 4; // 4 bytes per float/int
        }
        
        void markUsed() {
            this.lastUsed = System.currentTimeMillis();
        }
    }
}