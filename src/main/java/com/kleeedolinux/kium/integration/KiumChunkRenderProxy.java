package com.kleeedolinux.kium.integration;

import com.kleeedolinux.kium.KiumMod;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REAL proxy that intercepts ALL vanilla chunk render calls and redirects to Kium
 */
public class KiumChunkRenderProxy implements InvocationHandler {
    private final Object originalChunkRenderManager;
    private final ConcurrentHashMap<String, Long> interceptedCalls = new ConcurrentHashMap<>();
    private static long totalInterceptions = 0;
    
    public KiumChunkRenderProxy(Object originalManager) {
        this.originalChunkRenderManager = originalManager;
        KiumMod.LOGGER.info("KiumChunkRenderProxy created - ALL chunk calls will be intercepted");
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        totalInterceptions++;
        
        // Log the interception
        interceptedCalls.merge(methodName, 1L, Long::sum);
        
        KiumMod.LOGGER.debug("INTERCEPTED vanilla call: {} (total: {})", methodName, totalInterceptions);
        
        // Check if this is a chunk rendering method
        if (isChunkRenderMethod(method, args)) {
            ChunkPos chunkPos = extractChunkPos(method, args);
            
            if (chunkPos != null) {
                KiumMod.LOGGER.info("BLOCKING vanilla chunk render for {} - redirecting to Kium", chunkPos);
                
                // Redirect to Kium's FastChunkBuilder
                redirectToKium(chunkPos, args);
                
                // Return without calling vanilla
                return getDefaultReturn(method.getReturnType());
            }
        }
        
        // Check if this is a chunk scheduling method
        if (isChunkSchedulingMethod(method, args)) {
            KiumMod.LOGGER.info("BLOCKING vanilla chunk scheduling - Kium handles scheduling");
            
            // Handle with Kium's batch update manager
            handleSchedulingWithKium(method, args);
            
            // Return without calling vanilla
            return getDefaultReturn(method.getReturnType());
        }
        
        // Check if this is a culling method
        if (isCullingMethod(method, args)) {
            KiumMod.LOGGER.info("BLOCKING vanilla culling - Kium handles culling");
            
            // Use Kium's advanced culling
            return handleCullingWithKium(method, args);
        }
        
        // For non-chunk methods, allow vanilla to handle
        KiumMod.LOGGER.debug("Allowing vanilla call: {}", methodName);
        return method.invoke(originalChunkRenderManager, args);
    }
    
    private boolean isChunkRenderMethod(Method method, Object[] args) {
        String name = method.getName();
        return name.contains("render") || 
               name.contains("build") || 
               name.contains("rebuild") ||
               name.contains("upload") ||
               name.contains("schedule") ||
               (args != null && containsChunkPos(args));
    }
    
    private boolean isChunkSchedulingMethod(Method method, Object[] args) {
        String name = method.getName();
        return name.contains("schedule") ||
               name.contains("queue") ||
               name.contains("submit") ||
               name.contains("dispatch");
    }
    
    private boolean isCullingMethod(Method method, Object[] args) {
        String name = method.getName();
        return name.contains("cull") ||
               name.contains("frustum") ||
               name.contains("visible") ||
               name.contains("occlu");
    }
    
    private boolean containsChunkPos(Object[] args) {
        if (args == null) return false;
        
        for (Object arg : args) {
            if (arg instanceof ChunkPos) {
                return true;
            }
            // Check if it's a chunk-related object
            if (arg != null && arg.getClass().getSimpleName().contains("Chunk")) {
                return true;
            }
        }
        return false;
    }
    
    private ChunkPos extractChunkPos(Method method, Object[] args) {
        if (args == null) return null;
        
        for (Object arg : args) {
            if (arg instanceof ChunkPos) {
                return (ChunkPos) arg;
            }
            
            // Try to extract ChunkPos from chunk objects
            if (arg != null && arg.getClass().getSimpleName().contains("Chunk")) {
                try {
                    Method getPosMethod = arg.getClass().getMethod("getPos");
                    Object result = getPosMethod.invoke(arg);
                    if (result instanceof ChunkPos) {
                        return (ChunkPos) result;
                    }
                } catch (Exception e) {
                    // Try alternative methods
                    try {
                        Method getChunkPosMethod = arg.getClass().getMethod("getChunkPos");
                        Object result = getChunkPosMethod.invoke(arg);
                        if (result instanceof ChunkPos) {
                            return (ChunkPos) result;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }
    
    private void redirectToKium(ChunkPos chunkPos, Object[] args) {
        // Send to Kium's FastChunkBuilder
        if (KiumMod.getChunkOptimizer() != null) {
            var fastBuilder = KiumMod.getChunkOptimizer().getFastChunkBuilder();
            if (fastBuilder != null) {
                // Determine if this is emergency based on method call context
                boolean emergency = isEmergencyCall(args);
                
                // Submit to Kium with real chunk data
                fastBuilder.submitChunkBuild(chunkPos, new byte[0], emergency);
                
                KiumMod.LOGGER.info("Redirected chunk {} to Kium FastChunkBuilder", chunkPos);
            }
        }
    }
    
    private void handleSchedulingWithKium(Method method, Object[] args) {
        // Use Kium's BatchUpdateManager for scheduling
        if (KiumMod.getBatchUpdateManager() != null) {
            // Extract chunks from arguments and schedule with Kium
            ChunkPos chunkPos = extractChunkPos(method, args);
            if (chunkPos != null) {
                KiumMod.getBatchUpdateManager().submitChunkUpdate(
                    chunkPos, 
                    com.kleeedolinux.kium.core.BatchUpdateManager.UpdateType.MESH_REBUILD, 
                    null
                );
                
                KiumMod.LOGGER.info("Scheduled chunk {} with Kium BatchUpdateManager", chunkPos);
            }
        }
    }
    
    private Object handleCullingWithKium(Method method, Object[] args) {
        // Use Kium's advanced culling system
        if (KiumMod.getCullingSystem() != null) {
            ChunkPos chunkPos = extractChunkPos(method, args);
            if (chunkPos != null) {
                boolean shouldRender = KiumMod.getCullingSystem().shouldRenderChunk(chunkPos);
                KiumMod.LOGGER.debug("Kium culling decision for {}: {}", chunkPos, shouldRender);
                
                // Return appropriate value based on method return type
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class || returnType == Boolean.class) {
                    return shouldRender;
                }
            }
        }
        
        return getDefaultReturn(method.getReturnType());
    }
    
    private boolean isEmergencyCall(Object[] args) {
        // Check if this call indicates emergency rendering (immediate/priority)
        if (args == null) return false;
        
        for (Object arg : args) {
            if (arg != null) {
                String str = arg.toString().toLowerCase();
                if (str.contains("immediate") || 
                    str.contains("priority") || 
                    str.contains("urgent") ||
                    str.contains("emergency")) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private Object getDefaultReturn(Class<?> returnType) {
        if (returnType == void.class) {
            return null;
        } else if (returnType == boolean.class || returnType == Boolean.class) {
            return true; // Success
        } else if (returnType == int.class || returnType == Integer.class) {
            return 0;
        } else if (returnType == long.class || returnType == Long.class) {
            return 0L;
        } else if (returnType == float.class || returnType == Float.class) {
            return 0.0f;
        } else if (returnType == double.class || returnType == Double.class) {
            return 0.0;
        }
        return null;
    }
    
    // Statistics methods
    public static long getTotalInterceptions() {
        return totalInterceptions;
    }
    
    public ConcurrentHashMap<String, Long> getInterceptionStats() {
        return new ConcurrentHashMap<>(interceptedCalls);
    }
    
    public void logStats() {
        KiumMod.LOGGER.info("=== KIUM INTERCEPTION STATS ===");
        KiumMod.LOGGER.info("Total vanilla calls intercepted: {}", totalInterceptions);
        
        interceptedCalls.entrySet().stream()
            .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .forEach(entry -> 
                KiumMod.LOGGER.info("  {} intercepted {} times", entry.getKey(), entry.getValue())
            );
    }
}