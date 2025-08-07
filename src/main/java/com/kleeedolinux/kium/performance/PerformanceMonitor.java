package com.kleeedolinux.kium.performance;

import com.kleeedolinux.kium.KiumMod;
import java.util.concurrent.atomic.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PerformanceMonitor {
    private volatile double averageFrameTime = 16.67;
    private final AtomicLong totalFrames = new AtomicLong(0);
    private volatile double chunkOptimizationTime = 0;
    
    private final ConcurrentLinkedQueue<Double> frameTimeHistory = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Double> optimizationHistory = new ConcurrentLinkedQueue<>();
    
    private static final int HISTORY_SIZE = 100;
    private long lastFrameTime = System.nanoTime();
    
    public PerformanceMonitor() {
        KiumMod.LOGGER.info("Performance monitor initialized");
    }
    
    public void recordFrame() {
        long currentTime = System.nanoTime();
        double frameTime = (currentTime - lastFrameTime) / 1_000_000.0;
        lastFrameTime = currentTime;
        
        totalFrames.incrementAndGet();
        updateFrameTimeHistory(frameTime);
        calculateAverageFrameTime();
    }
    
    private void updateFrameTimeHistory(double frameTime) {
        frameTimeHistory.offer(frameTime);
        
        while (frameTimeHistory.size() > HISTORY_SIZE) {
            frameTimeHistory.poll();
        }
    }
    
    private void calculateAverageFrameTime() {
        if (!frameTimeHistory.isEmpty()) {
            double sum = frameTimeHistory.stream().mapToDouble(Double::doubleValue).sum();
            averageFrameTime = sum / frameTimeHistory.size();
        }
    }
    
    public void recordChunkOptimizationTime(double timeMs) {
        chunkOptimizationTime = timeMs;
        
        optimizationHistory.offer(timeMs);
        while (optimizationHistory.size() > HISTORY_SIZE) {
            optimizationHistory.poll();
        }
    }
    
    public double getAverageFrameTime() {
        return averageFrameTime;
    }
    
    public double getCurrentFPS() {
        return averageFrameTime > 0 ? 1000.0 / averageFrameTime : 0;
    }
    
    public long getTotalFrames() {
        return totalFrames.get();
    }
    
    public double getChunkOptimizationTime() {
        return chunkOptimizationTime;
    }
    
    public double getAverageOptimizationTime() {
        if (optimizationHistory.isEmpty()) {
            return 0;
        }
        
        double sum = optimizationHistory.stream().mapToDouble(Double::doubleValue).sum();
        return sum / optimizationHistory.size();
    }
    
    public boolean isPerformanceGood() {
        return getCurrentFPS() >= 60 && getAverageOptimizationTime() < 5.0;
    }
    
    public int getPerformanceScore() {
        double fps = getCurrentFPS();
        double optTime = getAverageOptimizationTime();
        
        int fpsScore = (int) Math.min(100, fps * 100 / 60);
        int optScore = (int) Math.max(0, 100 - optTime * 10);
        
        return (fpsScore + optScore) / 2;
    }
}