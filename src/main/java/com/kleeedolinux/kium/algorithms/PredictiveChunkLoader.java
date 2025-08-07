package com.kleeedolinux.kium.algorithms;

import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.ChunkPos;
import com.kleeedolinux.kium.KiumMod;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import it.unimi.dsi.fastutil.longs.*;

public class PredictiveChunkLoader {
    private static final int PREDICTION_HISTORY = 10; // Track last 10 positions
    private static final double MIN_VELOCITY_THRESHOLD = 0.1; // Minimum velocity to trigger prediction
    private static final double MAX_PREDICTION_DISTANCE = 512.0; // Maximum prediction distance in blocks
    private static final int MAX_PREDICTED_CHUNKS = 128; // Maximum chunks to preload
    
    private final ThreadPoolExecutor predictionExecutor;
    private final ScheduledExecutorService predictionScheduler;
    
    // Movement tracking
    private final Queue<MovementSample> movementHistory = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Vec3d> currentVelocity = new AtomicReference<>(Vec3d.ZERO);
    private final AtomicReference<Vec3d> currentAcceleration = new AtomicReference<>(Vec3d.ZERO);
    
    // Prediction state
    private final ConcurrentHashMap<Long, PredictedChunk> predictedChunks = new ConcurrentHashMap<>();
    private final AtomicLong totalPredictions = new AtomicLong(0);
    private final AtomicLong correctPredictions = new AtomicLong(0);
    private final AtomicLong preloadedChunks = new AtomicLong(0);
    
    // Movement patterns
    private MovementPattern currentPattern = MovementPattern.STATIONARY;
    private volatile long lastPatternUpdate = System.currentTimeMillis();
    
    public PredictiveChunkLoader() {
        int cores = Runtime.getRuntime().availableProcessors();
        
        this.predictionExecutor = new ThreadPoolExecutor(
            Math.max(1, cores / 4),
            Math.max(2, cores / 2),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Kium-PredictiveLoader-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY);
                    return t;
                }
            }
        );
        
        this.predictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Kium-PredictionScheduler");
            t.setDaemon(true);
            return t;
        });
        
        startPredictionSystem();
        KiumMod.LOGGER.info("Predictive chunk loader initialized");
    }
    
    private void startPredictionSystem() {
        // Run prediction updates at 20 FPS
        predictionScheduler.scheduleAtFixedRate(this::updatePredictions, 0, 50, TimeUnit.MILLISECONDS);
        
        // Cleanup old predictions every 5 seconds
        predictionScheduler.scheduleAtFixedRate(this::cleanupPredictions, 5, 5, TimeUnit.SECONDS);
    }
    
    public void updatePlayerPosition(Vec3d position, boolean isFlying, boolean isElytraFlying) {
        long currentTime = System.currentTimeMillis();
        
        // Add movement sample to history
        MovementSample sample = new MovementSample(position, currentTime, isFlying, isElytraFlying);
        movementHistory.offer(sample);
        
        // Limit history size
        while (movementHistory.size() > PREDICTION_HISTORY) {
            movementHistory.poll();
        }
        
        // Calculate velocity and acceleration
        updateMovementMetrics();
        
        // Update movement pattern
        updateMovementPattern();
        
        // Trigger prediction if needed
        if (shouldTriggerPrediction()) {
            predictionExecutor.submit(() -> generatePredictions(position));
        }
    }
    
    private void updateMovementMetrics() {
        if (movementHistory.size() < 2) return;
        
        List<MovementSample> samples = new ArrayList<>(movementHistory);
        
        // Calculate velocity from recent samples
        MovementSample current = samples.get(samples.size() - 1);
        MovementSample previous = samples.get(samples.size() - 2);
        
        double deltaTime = (current.timestamp - previous.timestamp) / 1000.0;
        if (deltaTime > 0) {
            Vec3d velocity = current.position.subtract(previous.position).multiply(1.0 / deltaTime);
            currentVelocity.set(velocity);
            
            // Calculate acceleration if we have enough samples
            if (samples.size() >= 3) {
                MovementSample beforePrevious = samples.get(samples.size() - 3);
                double prevDeltaTime = (previous.timestamp - beforePrevious.timestamp) / 1000.0;
                
                if (prevDeltaTime > 0) {
                    Vec3d prevVelocity = previous.position.subtract(beforePrevious.position).multiply(1.0 / prevDeltaTime);
                    Vec3d acceleration = velocity.subtract(prevVelocity).multiply(1.0 / deltaTime);
                    currentAcceleration.set(acceleration);
                }
            }
        }
    }
    
    private void updateMovementPattern() {
        if (movementHistory.size() < 3) return;
        
        Vec3d velocity = currentVelocity.get();
        double speed = velocity.length();
        
        MovementSample latest = ((LinkedList<MovementSample>) movementHistory).peekLast();
        if (latest == null) return;
        
        MovementPattern newPattern;
        
        if (speed < MIN_VELOCITY_THRESHOLD) {
            newPattern = MovementPattern.STATIONARY;
        } else if (latest.isElytraFlying) {
            newPattern = MovementPattern.ELYTRA_FLIGHT;
        } else if (latest.isFlying) {
            newPattern = MovementPattern.CREATIVE_FLIGHT;
        } else if (speed > 20.0) {
            newPattern = MovementPattern.FAST_TRAVEL;
        } else if (isMovementLinear()) {
            newPattern = MovementPattern.LINEAR_MOVEMENT;
        } else if (isMovementCircular()) {
            newPattern = MovementPattern.CIRCULAR_MOVEMENT;
        } else {
            newPattern = MovementPattern.RANDOM_EXPLORATION;
        }
        
        if (newPattern != currentPattern) {
            currentPattern = newPattern;
            lastPatternUpdate = System.currentTimeMillis();
            
            KiumMod.LOGGER.debug("Movement pattern changed to: {}", newPattern);
        }
    }
    
    private boolean isMovementLinear() {
        if (movementHistory.size() < 5) return false;
        
        List<MovementSample> samples = new ArrayList<>(movementHistory);
        Vec3d firstDir = samples.get(1).position.subtract(samples.get(0).position).normalize();
        
        for (int i = 2; i < samples.size(); i++) {
            Vec3d dir = samples.get(i).position.subtract(samples.get(i-1).position);
            if (dir.length() < 0.1) continue;
            
            dir = dir.normalize();
            double dot = firstDir.dotProduct(dir);
            
            if (dot < 0.8) return false; // Less than ~37 degree deviation
        }
        
        return true;
    }
    
    private boolean isMovementCircular() {
        if (movementHistory.size() < 8) return false;
        
        // Check if player is moving in a roughly circular pattern
        List<MovementSample> samples = new ArrayList<>(movementHistory);
        Vec3d center = calculateCenterOfMass(samples);
        
        double avgRadius = 0;
        for (MovementSample sample : samples) {
            avgRadius += sample.position.distanceTo(center);
        }
        avgRadius /= samples.size();
        
        // Check if all positions are roughly the same distance from center
        double radiusVariance = 0;
        for (MovementSample sample : samples) {
            double radius = sample.position.distanceTo(center);
            radiusVariance += Math.pow(radius - avgRadius, 2);
        }
        radiusVariance = Math.sqrt(radiusVariance / samples.size());
        
        return radiusVariance < avgRadius * 0.3; // Within 30% of average radius
    }
    
    private Vec3d calculateCenterOfMass(List<MovementSample> samples) {
        double x = 0, y = 0, z = 0;
        for (MovementSample sample : samples) {
            x += sample.position.x;
            y += sample.position.y; 
            z += sample.position.z;
        }
        return new Vec3d(x / samples.size(), y / samples.size(), z / samples.size());
    }
    
    private boolean shouldTriggerPrediction() {
        Vec3d velocity = currentVelocity.get();
        double speed = velocity.length();
        
        return speed >= MIN_VELOCITY_THRESHOLD && currentPattern != MovementPattern.STATIONARY;
    }
    
    private void generatePredictions(Vec3d currentPos) {
        totalPredictions.incrementAndGet();
        
        Vec3d velocity = currentVelocity.get();
        Vec3d acceleration = currentAcceleration.get();
        
        Set<ChunkPos> chunksToPreload = new HashSet<>();
        
        // Generate predictions based on movement pattern
        switch (currentPattern) {
            case ELYTRA_FLIGHT -> generateElytraPredictions(currentPos, velocity, acceleration, chunksToPreload);
            case CREATIVE_FLIGHT -> generateFlightPredictions(currentPos, velocity, acceleration, chunksToPreload);
            case FAST_TRAVEL -> generateFastTravelPredictions(currentPos, velocity, acceleration, chunksToPreload);
            case LINEAR_MOVEMENT -> generateLinearPredictions(currentPos, velocity, acceleration, chunksToPreload);
            case CIRCULAR_MOVEMENT -> generateCircularPredictions(currentPos, velocity, acceleration, chunksToPreload);
            case RANDOM_EXPLORATION -> generateExplorationPredictions(currentPos, velocity, acceleration, chunksToPreload);
        }
        
        // Submit chunks for preloading
        preloadPredictedChunks(chunksToPreload, currentPos);
    }
    
    private void generateElytraPredictions(Vec3d pos, Vec3d vel, Vec3d acc, Set<ChunkPos> chunks) {
        // Elytra flight - predict long distance with physics
        double timeHorizon = 10.0; // 10 seconds ahead
        double timeStep = 0.5; // Every 0.5 seconds
        
        Vec3d currentPos = pos;
        Vec3d currentVel = vel;
        
        for (double t = timeStep; t <= timeHorizon; t += timeStep) {
            // Simple physics integration
            currentVel = currentVel.add(acc.multiply(timeStep));
            currentPos = currentPos.add(currentVel.multiply(timeStep));
            
            // Apply drag (simplified)
            currentVel = currentVel.multiply(0.99);
            
            if (currentPos.distanceTo(pos) > MAX_PREDICTION_DISTANCE) break;
            
            addChunkWithRadius(currentPos, getRadiusForDistance(currentPos.distanceTo(pos)), chunks);
        }
    }
    
    private void generateFlightPredictions(Vec3d pos, Vec3d vel, Vec3d acc, Set<ChunkPos> chunks) {
        // Creative flight - predict medium distance
        double timeHorizon = 5.0;
        double timeStep = 0.25;
        
        Vec3d currentPos = pos;
        Vec3d currentVel = vel;
        
        for (double t = timeStep; t <= timeHorizon; t += timeStep) {
            currentVel = currentVel.add(acc.multiply(timeStep));
            currentPos = currentPos.add(currentVel.multiply(timeStep));
            
            if (currentPos.distanceTo(pos) > MAX_PREDICTION_DISTANCE * 0.7) break;
            
            addChunkWithRadius(currentPos, getRadiusForDistance(currentPos.distanceTo(pos)), chunks);
        }
    }
    
    private void generateFastTravelPredictions(Vec3d pos, Vec3d vel, Vec3d acc, Set<ChunkPos> chunks) {
        // Fast travel (horse, boat, etc.) - predict ahead in movement direction
        double timeHorizon = 8.0;
        double timeStep = 0.5;
        
        Vec3d direction = vel.normalize();
        double speed = vel.length();
        
        for (double t = timeStep; t <= timeHorizon; t += timeStep) {
            double distance = speed * t;
            if (distance > MAX_PREDICTION_DISTANCE * 0.8) break;
            
            Vec3d predictedPos = pos.add(direction.multiply(distance));
            addChunkWithRadius(predictedPos, getRadiusForDistance(distance), chunks);
        }
    }
    
    private void generateLinearPredictions(Vec3d pos, Vec3d vel, Vec3d acc, Set<ChunkPos> chunks) {
        // Linear movement - predict straight ahead
        double timeHorizon = 3.0;
        double timeStep = 0.2;
        
        for (double t = timeStep; t <= timeHorizon; t += timeStep) {
            Vec3d predictedPos = pos.add(vel.multiply(t));
            
            if (predictedPos.distanceTo(pos) > MAX_PREDICTION_DISTANCE * 0.5) break;
            
            addChunkWithRadius(predictedPos, getRadiusForDistance(predictedPos.distanceTo(pos)), chunks);
        }
    }
    
    private void generateCircularPredictions(Vec3d pos, Vec3d vel, Vec3d acc, Set<ChunkPos> chunks) {
        // Circular movement - predict around the circle
        List<MovementSample> samples = new ArrayList<>(movementHistory);
        if (samples.size() < 5) return;
        
        Vec3d center = calculateCenterOfMass(samples);
        double radius = pos.distanceTo(center);
        
        Vec3d toCenter = center.subtract(pos).normalize();
        Vec3d tangent = new Vec3d(-toCenter.z, 0, toCenter.x); // Perpendicular in XZ plane
        
        // Predict positions around the circle
        for (int i = 1; i <= 8; i++) {
            double angle = (vel.dotProduct(tangent) > 0 ? 1 : -1) * Math.PI * i / 8;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            
            Vec3d predictedPos = center.add(
                toCenter.multiply(cos * radius).add(tangent.multiply(sin * radius))
            );
            
            addChunkWithRadius(predictedPos, 2, chunks);
        }
    }
    
    private void generateExplorationPredictions(Vec3d pos, Vec3d vel, Vec3d acc, Set<ChunkPos> chunks) {
        // Random exploration - predict in multiple directions
        Vec3d direction = vel.normalize();
        double speed = vel.length();
        
        // Predict ahead
        Vec3d ahead = pos.add(direction.multiply(speed * 2.0));
        addChunkWithRadius(ahead, 3, chunks);
        
        // Predict to sides
        Vec3d right = new Vec3d(-direction.z, 0, direction.x);
        Vec3d sideRight = pos.add(right.multiply(32));
        Vec3d sideLeft = pos.add(right.multiply(-32));
        
        addChunkWithRadius(sideRight, 2, chunks);
        addChunkWithRadius(sideLeft, 2, chunks);
    }
    
    private void addChunkWithRadius(Vec3d pos, int radius, Set<ChunkPos> chunks) {
        ChunkPos centerChunk = new ChunkPos((int) pos.x >> 4, (int) pos.z >> 4);
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    chunks.add(new ChunkPos(centerChunk.x + dx, centerChunk.z + dz));
                }
            }
        }
    }
    
    private int getRadiusForDistance(double distance) {
        if (distance < 64) return 4;
        if (distance < 128) return 3;
        if (distance < 256) return 2;
        return 1;
    }
    
    private void preloadPredictedChunks(Set<ChunkPos> chunks, Vec3d currentPos) {
        if (chunks.size() > MAX_PREDICTED_CHUNKS) {
            // Sort by distance and take closest chunks
            List<ChunkPos> sortedChunks = new ArrayList<>(chunks);
            sortedChunks.sort((a, b) -> {
                double distA = calculateChunkDistance(a, currentPos);
                double distB = calculateChunkDistance(b, currentPos);
                return Double.compare(distA, distB);
            });
            
            chunks = new HashSet<>(sortedChunks.subList(0, MAX_PREDICTED_CHUNKS));
        }
        
        for (ChunkPos pos : chunks) {
            long chunkKey = pos.toLong();
            double distance = calculateChunkDistance(pos, currentPos);
            
            PredictedChunk predicted = new PredictedChunk(pos, distance, currentPattern, System.currentTimeMillis());
            predictedChunks.put(chunkKey, predicted);
            
            // Submit to FastChunkBuilder
            if (KiumMod.getChunkOptimizer() != null) {
                KiumMod.getChunkOptimizer().getFastChunkBuilder().submitChunkBuild(pos, new byte[0], distance < 3.0);
                preloadedChunks.incrementAndGet();
            }
        }
    }
    
    private double calculateChunkDistance(ChunkPos pos, Vec3d playerPos) {
        double chunkCenterX = (pos.x << 4) + 8.0;
        double chunkCenterZ = (pos.z << 4) + 8.0;
        
        return Math.sqrt(
            Math.pow(chunkCenterX - playerPos.x, 2) +
            Math.pow(chunkCenterZ - playerPos.z, 2)
        );
    }
    
    private void updatePredictions() {
        // Validate predictions against actual player movement
        if (movementHistory.isEmpty()) return;
        
        MovementSample latest = ((LinkedList<MovementSample>) movementHistory).peekLast();
        if (latest == null) return;
        
        ChunkPos actualChunk = new ChunkPos((int) latest.position.x >> 4, (int) latest.position.z >> 4);
        long actualChunkKey = actualChunk.toLong();
        
        if (predictedChunks.containsKey(actualChunkKey)) {
            correctPredictions.incrementAndGet();
        }
    }
    
    private void cleanupPredictions() {
        long currentTime = System.currentTimeMillis();
        
        predictedChunks.entrySet().removeIf(entry -> {
            PredictedChunk predicted = entry.getValue();
            return currentTime - predicted.timestamp > 30000; // Remove after 30 seconds
        });
    }
    
    public double getPredictionAccuracy() {
        long total = totalPredictions.get();
        long correct = correctPredictions.get();
        return total > 0 ? (double) correct / total : 0.0;
    }
    
    public long getPreloadedChunks() {
        return preloadedChunks.get();
    }
    
    public MovementPattern getCurrentPattern() {
        return currentPattern;
    }
    
    public Vec3d getCurrentVelocity() {
        return currentVelocity.get();
    }
    
    public int getPredictedChunkCount() {
        return predictedChunks.size();
    }
    
    public String getPerformanceStats() {
        return String.format(
            "PredictiveLoader - Pattern: %s, Accuracy: %.1f%%, Predicted: %d, Preloaded: %d",
            currentPattern, getPredictionAccuracy() * 100, getPredictedChunkCount(), getPreloadedChunks()
        );
    }
    
    public void shutdown() {
        predictionScheduler.shutdown();
        predictionExecutor.shutdown();
        
        try {
            if (!predictionScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                predictionScheduler.shutdownNow();
            }
            if (!predictionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                predictionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            predictionScheduler.shutdownNow();
            predictionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        predictedChunks.clear();
        movementHistory.clear();
    }
    
    public void pause() {
        // Pause prediction processing
    }
    
    public void resume() {
        // Resume prediction processing
    }
    
    public void cleanupOldData() {
        // Clean up old predictions and movement history
        long currentTime = System.currentTimeMillis();
        
        predictedChunks.entrySet().removeIf(entry -> {
            PredictedChunk predicted = entry.getValue();
            return currentTime - predicted.timestamp > 30000; // Remove after 30 seconds
        });
        
        // Keep only recent movement history
        movementHistory.removeIf(sample -> currentTime - sample.timestamp > 60000); // Keep last minute
        
        KiumMod.LOGGER.debug("PredictiveChunkLoader cleanup: {} predicted chunks, {} movement samples", 
            predictedChunks.size(), movementHistory.size());
    }
    
    // Method required by VanillaIntegration
    public boolean shouldPreloadChunk(ChunkPos chunkPos) {
        // Check if this chunk is in our predictions
        long chunkKey = chunkPos.toLong();
        PredictedChunk predicted = predictedChunks.get(chunkKey);
        
        if (predicted != null) {
            // Check if prediction is still valid (not too old)
            long currentTime = System.currentTimeMillis();
            return (currentTime - predicted.timestamp) < 30000; // Valid for 30 seconds
        }
        
        // Not in predictions, don't preload
        return false;
    }
    
    public enum MovementPattern {
        STATIONARY,
        LINEAR_MOVEMENT,
        CIRCULAR_MOVEMENT,
        RANDOM_EXPLORATION,
        CREATIVE_FLIGHT,
        ELYTRA_FLIGHT,
        FAST_TRAVEL
    }
    
    private static class MovementSample {
        final Vec3d position;
        final long timestamp;
        final boolean isFlying;
        final boolean isElytraFlying;
        
        MovementSample(Vec3d position, long timestamp, boolean isFlying, boolean isElytraFlying) {
            this.position = position;
            this.timestamp = timestamp;
            this.isFlying = isFlying;
            this.isElytraFlying = isElytraFlying;
        }
    }
    
    private static class PredictedChunk {
        final ChunkPos position;
        final double distance;
        final MovementPattern pattern;
        final long timestamp;
        
        PredictedChunk(ChunkPos position, double distance, MovementPattern pattern, long timestamp) {
            this.position = position;
            this.distance = distance;
            this.pattern = pattern;
            this.timestamp = timestamp;
        }
    }
}