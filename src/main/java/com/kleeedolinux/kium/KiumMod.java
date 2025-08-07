package com.kleeedolinux.kium;

import net.fabricmc.api.ModInitializer;
import com.kleeedolinux.kium.core.ChunkOptimizer;
import com.kleeedolinux.kium.core.AtomicThreadManager;
import com.kleeedolinux.kium.core.BatchUpdateManager;
import com.kleeedolinux.kium.performance.PerformanceMonitor;
import com.kleeedolinux.kium.algorithms.PredictiveChunkLoader;
import com.kleeedolinux.kium.algorithms.ParallelCullingSystem;
import com.kleeedolinux.kium.algorithms.VectorizedMeshGenerator;
import com.kleeedolinux.kium.rendering.GPUOptimizer;
import com.kleeedolinux.kium.optimization.SpawnChunkOptimizer;
import com.kleeedolinux.kium.pipeline.ChunkPrerenderPipeline;
import com.kleeedolinux.kium.pipeline.ChunkPreProcessor;
import com.kleeedolinux.kium.config.KiumConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KiumMod implements ModInitializer {
    public static final String MOD_ID = "kium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static KiumConfig config;
    private static ChunkOptimizer chunkOptimizer;
    private static AtomicThreadManager threadManager;
    private static PerformanceMonitor performanceMonitor;
    private static BatchUpdateManager batchUpdateManager;
    private static PredictiveChunkLoader predictiveLoader;
    private static ParallelCullingSystem cullingSystem;
    private static VectorizedMeshGenerator meshGenerator;
    private static GPUOptimizer gpuOptimizer;
    private static SpawnChunkOptimizer spawnChunkOptimizer;
    private static ChunkPrerenderPipeline prerenderPipeline;
    private static ChunkPreProcessor chunkPreProcessor;
    
    private static volatile boolean isPaused = false;
    private static long lastCleanupTime = System.currentTimeMillis();
    private static final long CLEANUP_INTERVAL = 30000; // 30 seconds
    
    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Kium Performance Optimizer...");
        
        try {
            // Load configuration
            config = KiumConfig.getInstance();
            LOGGER.info("Loaded configuration: {}", config);
            
            // Initialize core systems
            threadManager = new AtomicThreadManager();
            performanceMonitor = new PerformanceMonitor();
            
            // Add shutdown hook for cleanup
            Runtime.getRuntime().addShutdownHook(new Thread(KiumMod::shutdown));
            
            // INITIALIZE VANILLA INTEGRATION - REPLACE VANILLA PIPELINE
            try {
                com.kleeedolinux.kium.integration.VanillaIntegration.initialize();
                LOGGER.info("VANILLA PIPELINE SUCCESSFULLY REPLACED WITH KIUM");
            } catch (Exception e) {
                LOGGER.error("CRITICAL: Failed to replace vanilla pipeline: {}", e.getMessage(), e);
                throw new RuntimeException("Kium initialization failed - cannot replace vanilla pipeline", e);
            }
            
            // Initialize optimization systems
            if (config.enableFastChunkBuilder) {
                chunkOptimizer = new ChunkOptimizer(threadManager, performanceMonitor);
                LOGGER.info("FastChunkBuilder enabled");
            }
            
            if (config.enablePredictiveLoading) {
                predictiveLoader = new PredictiveChunkLoader();
                LOGGER.info("Predictive chunk loading enabled");
            }
            
            if (config.enableParallelCulling) {
                cullingSystem = new ParallelCullingSystem();
                LOGGER.info("Parallel culling system enabled");
            }
            
            if (config.enableVectorizedMeshing) {
                meshGenerator = new VectorizedMeshGenerator();
                LOGGER.info("Vectorized mesh generation enabled");
            }
            
            if (config.enableGPUOptimization) {
                gpuOptimizer = new GPUOptimizer();
                LOGGER.info("GPU optimization enabled");
            }
            
            if (config.enableSpawnChunkOptimization) {
                spawnChunkOptimizer = new SpawnChunkOptimizer();
                LOGGER.info("Spawn chunk optimization enabled");
            }
            
            if (config.enableChunkPrerendering) {
                prerenderPipeline = new ChunkPrerenderPipeline();
                chunkPreProcessor = new ChunkPreProcessor(prerenderPipeline);
                LOGGER.info("Chunk pre-rendering pipeline enabled");
            }
            
            // Initialize batch update system
            batchUpdateManager = new BatchUpdateManager();
            
            LOGGER.info("Kium initialized successfully - Render Distance: {}, Threads: {}, Features: {}",
                config.maxRenderDistance, config.getEffectiveChunkThreads(), getEnabledFeaturesCount());
                
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Kium", e);
            throw new RuntimeException("Kium initialization failed", e);
        }
    }
    
    private int getEnabledFeaturesCount() {
        int count = 0;
        if (config.enableFastChunkBuilder && chunkOptimizer != null) count++;
        if (config.enablePredictiveLoading && predictiveLoader != null) count++;
        if (config.enableParallelCulling && cullingSystem != null) count++;
        if (config.enableVectorizedMeshing && meshGenerator != null) count++;
        if (config.enableGPUOptimization && gpuOptimizer != null) count++;
        if (config.enableSpawnChunkOptimization && spawnChunkOptimizer != null) count++;
        if (config.enableChunkPrerendering && prerenderPipeline != null && chunkPreProcessor != null) count++;
        return count;
    }
    
    // Shutdown hook for clean resource cleanup
    public static void shutdown() {
        LOGGER.info("Shutting down Kium Performance Optimizer...");
        
        try {
            if (gpuOptimizer != null) {
                gpuOptimizer.shutdown();
            }
            
            if (spawnChunkOptimizer != null) {
                spawnChunkOptimizer.shutdown();
            }
            
            if (chunkPreProcessor != null) {
                chunkPreProcessor.shutdown();
            }
            
            if (prerenderPipeline != null) {
                prerenderPipeline.shutdown();
            }
            
            if (cullingSystem != null) {
                cullingSystem.shutdown();
            }
            
            if (predictiveLoader != null) {
                predictiveLoader.shutdown();
            }
            
            if (batchUpdateManager != null) {
                batchUpdateManager.shutdown();
            }
            
            if (chunkOptimizer != null) {
                chunkOptimizer.shutdown();
            }
            
            if (threadManager != null) {
                threadManager.shutdown();
            }
            
            LOGGER.info("Kium shutdown complete");
            
        } catch (Exception e) {
            LOGGER.error("Error during Kium shutdown", e);
        }
    }
    
    // Getters for all systems
    public static KiumConfig getConfig() {
        return config;
    }
    
    public static ChunkOptimizer getChunkOptimizer() {
        return chunkOptimizer;
    }
    
    public static AtomicThreadManager getThreadManager() {
        return threadManager;
    }
    
    public static PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }
    
    public static BatchUpdateManager getBatchUpdateManager() {
        return batchUpdateManager;
    }
    
    public static PredictiveChunkLoader getPredictiveLoader() {
        return predictiveLoader;
    }
    
    public static ParallelCullingSystem getCullingSystem() {
        return cullingSystem;
    }
    
    public static VectorizedMeshGenerator getMeshGenerator() {
        return meshGenerator;
    }
    
    public static GPUOptimizer getGPUOptimizer() {
        return gpuOptimizer;
    }
    
    public static SpawnChunkOptimizer getSpawnChunkOptimizer() {
        return spawnChunkOptimizer;
    }
    
    public static ChunkPrerenderPipeline getPrerenderPipeline() {
        return prerenderPipeline;
    }
    
    public static ChunkPreProcessor getChunkPreProcessor() {
        return chunkPreProcessor;
    }
    
    // Performance and status methods
    public static String getPerformanceStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== Kium Performance Stats ===\n");
        
        if (performanceMonitor != null) {
            stats.append(String.format("FPS: %.1f, Frame Time: %.2fms\n", 
                performanceMonitor.getCurrentFPS(), performanceMonitor.getAverageFrameTime()));
        }
        
        if (threadManager != null) {
            stats.append(String.format("Threads: %d active, %d processed chunks\n",
                threadManager.getActiveThreads(), threadManager.getProcessedChunks()));
        }
        
        if (chunkOptimizer != null && chunkOptimizer.getFastChunkBuilder() != null) {
            stats.append(chunkOptimizer.getFastChunkBuilder().getPerformanceStats()).append("\n");
        }
        
        if (batchUpdateManager != null) {
            stats.append(batchUpdateManager.getPerformanceStats()).append("\n");
        }
        
        if (predictiveLoader != null) {
            stats.append(predictiveLoader.getPerformanceStats()).append("\n");
        }
        
        if (cullingSystem != null) {
            stats.append(String.format("Culling: %d operations, %.1f%% efficiency\n",
                cullingSystem.getTotalCullingOperations(), cullingSystem.getCullingEfficiency() * 100));
        }
        
        if (gpuOptimizer != null) {
            stats.append(gpuOptimizer.getPerformanceStats()).append("\n");
        }
        
        if (spawnChunkOptimizer != null) {
            stats.append(spawnChunkOptimizer.getPerformanceStats()).append("\n");
        }
        
        if (prerenderPipeline != null) {
            stats.append(prerenderPipeline.getPerformanceStats()).append("\n");
        }
        
        if (chunkPreProcessor != null) {
            stats.append(chunkPreProcessor.getPerformanceStats()).append("\n");
        }
        
        return stats.toString();
    }
    
    public static boolean isInitialized() {
        return config != null && threadManager != null && performanceMonitor != null;
    }
    
    public static int getActiveSystemsCount() {
        int count = 0;
        if (chunkOptimizer != null) count++;
        if (predictiveLoader != null) count++;
        if (cullingSystem != null) count++;
        if (meshGenerator != null) count++;
        if (gpuOptimizer != null) count++;
        if (spawnChunkOptimizer != null) count++;
        if (prerenderPipeline != null) count++;
        if (chunkPreProcessor != null) count++;
        if (batchUpdateManager != null) count++;
        return count;
    }
    
    // Pause/Resume functionality to reduce CPU usage when idle
    public static void pauseAllSystems() {
        isPaused = true;
        LOGGER.info("Pausing all Kium systems to reduce CPU usage");
        
        if (threadManager != null) {
            threadManager.pauseProcessing();
        }
        if (predictiveLoader != null) {
            predictiveLoader.pause();
        }
        if (batchUpdateManager != null) {
            batchUpdateManager.pause();
        }
        if (chunkOptimizer != null) {
            chunkOptimizer.pause();
        }
        
        // Force garbage collection to free memory
        System.gc();
    }
    
    public static void resumeAllSystems() {
        if (isPaused) {
            isPaused = false;
            LOGGER.info("Resuming all Kium systems");
            
            if (threadManager != null) {
                threadManager.resumeProcessing();
            }
            if (predictiveLoader != null) {
                predictiveLoader.resume();
            }
            if (batchUpdateManager != null) {
                batchUpdateManager.resume();
            }
            if (chunkOptimizer != null) {
                chunkOptimizer.resume();
            }
        }
    }
    
    public static boolean isPaused() {
        return isPaused;
    }
    
    // Periodic cleanup to prevent memory leaks and CPU accumulation
    public static void performPeriodicCleanup() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL) {
            lastCleanupTime = currentTime;
            
            LOGGER.debug("Performing periodic cleanup to prevent memory leaks");
            
            // Clean up thread manager
            if (threadManager != null) {
                threadManager.performCleanup();
            }
            
            // Clean up GPU optimizer
            if (gpuOptimizer != null) {
                gpuOptimizer.optimizeGPUState();
            }
            
            // Clean up chunk optimizer
            if (chunkOptimizer != null) {
                chunkOptimizer.performCleanup();
            }
            
            // Clean up predictive loader
            if (predictiveLoader != null) {
                predictiveLoader.cleanupOldData();
            }
            
            // Clean up spawn chunk optimizer
            if (spawnChunkOptimizer != null) {
                spawnChunkOptimizer.cleanupSpawnChunks();
                spawnChunkOptimizer.optimizeSpawnRadius();
            }
            
            // Force minor garbage collection
            System.gc();
        }
    }
    
}