package com.kleeedolinux.kium.algorithms;

import com.kleeedolinux.kium.KiumMod;

public class LODSystem {
    private static final double[] LOD_THRESHOLDS = {
        32.0,   // LOD 0: Full detail (0-32 chunks)
        64.0,   // LOD 1: High detail (32-64 chunks)
        128.0,  // LOD 2: Medium detail (64-128 chunks)
        256.0,  // LOD 3: Reduced detail (128-256 chunks)
        512.0,  // LOD 4: Low detail (256-512 chunks)
        768.0,  // LOD 5: Very low detail (512-768 chunks)
        1024.0  // LOD 6: Impostor (768-1024 chunks)
    };
    
    private static final int[] MESH_COMPLEXITY = {
        100,  // LOD 0: 100% mesh complexity
        75,   // LOD 1: 75% mesh complexity
        50,   // LOD 2: 50% mesh complexity
        25,   // LOD 3: 25% mesh complexity
        12,   // LOD 4: 12% mesh complexity
        6,    // LOD 5: 6% mesh complexity
        1     // LOD 6: 1% mesh complexity (impostor)
    };
    
    private static final int[] BLOCK_REDUCTION = {
        1,    // LOD 0: No reduction
        1,    // LOD 1: No reduction
        2,    // LOD 2: 2x2 block groups
        4,    // LOD 3: 4x4 block groups
        8,    // LOD 4: 8x8 block groups
        16,   // LOD 5: 16x16 block groups
        32    // LOD 6: 32x32 block groups (impostor)
    };
    
    public LODSystem() {
        KiumMod.LOGGER.info("LOD System initialized with {} levels", LOD_THRESHOLDS.length);
    }
    
    public int calculateLODLevel(double distance, int maxRenderDistance) {
        double normalizedDistance = distance / maxRenderDistance;
        
        for (int i = 0; i < LOD_THRESHOLDS.length; i++) {
            if (distance <= LOD_THRESHOLDS[i]) {
                return i;
            }
        }
        
        return LOD_THRESHOLDS.length - 1;
    }
    
    public int calculateLODLevelAdaptive(double distance, int maxRenderDistance, double frameTime, double targetFrameTime) {
        int baseLOD = calculateLODLevel(distance, maxRenderDistance);
        
        double performance = frameTime / targetFrameTime;
        
        if (performance > 1.5) {
            return Math.min(baseLOD + 2, LOD_THRESHOLDS.length - 1);
        } else if (performance > 1.2) {
            return Math.min(baseLOD + 1, LOD_THRESHOLDS.length - 1);
        } else if (performance < 0.8) {
            return Math.max(baseLOD - 1, 0);
        }
        
        return baseLOD;
    }
    
    public int getMeshComplexity(int lodLevel) {
        if (lodLevel < 0 || lodLevel >= MESH_COMPLEXITY.length) {
            return MESH_COMPLEXITY[MESH_COMPLEXITY.length - 1];
        }
        return MESH_COMPLEXITY[lodLevel];
    }
    
    public int getBlockReduction(int lodLevel) {
        if (lodLevel < 0 || lodLevel >= BLOCK_REDUCTION.length) {
            return BLOCK_REDUCTION[BLOCK_REDUCTION.length - 1];
        }
        return BLOCK_REDUCTION[lodLevel];
    }
    
    public boolean shouldRenderFaces(int lodLevel) {
        return lodLevel <= 3;
    }
    
    public boolean shouldRenderTransparency(int lodLevel) {
        return lodLevel <= 2;
    }
    
    public boolean shouldRenderEntities(int lodLevel) {
        return lodLevel <= 1;
    }
    
    public boolean shouldRenderBlockEntities(int lodLevel) {
        return lodLevel == 0;
    }
    
    public int getUpdateFrequency(int lodLevel) {
        return switch (lodLevel) {
            case 0 -> 1;    // Every frame
            case 1 -> 2;    // Every 2 frames
            case 2 -> 4;    // Every 4 frames
            case 3 -> 8;    // Every 8 frames
            case 4 -> 16;   // Every 16 frames
            case 5 -> 32;   // Every 32 frames
            case 6 -> 64;   // Every 64 frames
            default -> 64;
        };
    }
    
    public double getLODThreshold(int lodLevel) {
        if (lodLevel < 0 || lodLevel >= LOD_THRESHOLDS.length) {
            return LOD_THRESHOLDS[LOD_THRESHOLDS.length - 1];
        }
        return LOD_THRESHOLDS[lodLevel];
    }
    
    public int getMaxLODLevel() {
        return LOD_THRESHOLDS.length - 1;
    }
    
    public String getLODDescription(int lodLevel) {
        return switch (lodLevel) {
            case 0 -> "Full Detail";
            case 1 -> "High Detail";
            case 2 -> "Medium Detail";
            case 3 -> "Reduced Detail";
            case 4 -> "Low Detail";
            case 5 -> "Very Low Detail";
            case 6 -> "Impostor";
            default -> "Unknown LOD";
        };
    }
    
    public boolean isImpostorLOD(int lodLevel) {
        return lodLevel >= 6;
    }
    
    public double calculateLODTransition(double distance, int currentLOD) {
        if (currentLOD >= LOD_THRESHOLDS.length - 1) {
            return 1.0;
        }
        
        double currentThreshold = currentLOD > 0 ? LOD_THRESHOLDS[currentLOD - 1] : 0;
        double nextThreshold = LOD_THRESHOLDS[currentLOD];
        
        return Math.max(0.0, Math.min(1.0, (distance - currentThreshold) / (nextThreshold - currentThreshold)));
    }
}