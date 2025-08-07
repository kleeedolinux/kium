package com.kleeedolinux.kium.algorithms;

import net.minecraft.util.math.Vec3d;
import com.kleeedolinux.kium.KiumMod;

import java.util.concurrent.atomic.AtomicLong;

public class FrustumCuller {
    private static final double CHUNK_SIZE = 16.0;
    
    private final Plane[] frustumPlanes = new Plane[6];
    private final AtomicLong culledChunks = new AtomicLong(0);
    private final AtomicLong processedChunks = new AtomicLong(0);
    
    private volatile float lastYaw = Float.MAX_VALUE;
    private volatile float lastPitch = Float.MAX_VALUE;
    private volatile Vec3d lastCameraPos = Vec3d.ZERO;
    private volatile boolean frustumDirty = true;
    
    public FrustumCuller() {
        for (int i = 0; i < 6; i++) {
            frustumPlanes[i] = new Plane();
        }
        KiumMod.LOGGER.info("Frustum culler initialized with optimized algorithms");
    }
    
    public void updateFrustum(Vec3d cameraPos, float yaw, float pitch, float fov) {
        if (shouldUpdateFrustum(cameraPos, yaw, pitch)) {
            calculateFrustumPlanes(cameraPos, yaw, pitch, fov);
            updateLastTransform(cameraPos, yaw, pitch);
            frustumDirty = false;
        }
    }
    
    private boolean shouldUpdateFrustum(Vec3d cameraPos, float yaw, float pitch) {
        if (frustumDirty) return true;
        
        double posDelta = lastCameraPos.distanceTo(cameraPos);
        float yawDelta = Math.abs(yaw - lastYaw);
        float pitchDelta = Math.abs(pitch - lastPitch);
        
        return posDelta > 0.1 || yawDelta > 0.5f || pitchDelta > 0.5f;
    }
    
    private void calculateFrustumPlanes(Vec3d cameraPos, float yaw, float pitch, float fov) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        
        Vec3d forward = new Vec3d(
            -Math.sin(yawRad) * Math.cos(pitchRad),
            -Math.sin(pitchRad),
            Math.cos(yawRad) * Math.cos(pitchRad)
        );
        
        Vec3d right = new Vec3d(
            Math.cos(yawRad),
            0,
            Math.sin(yawRad)
        );
        
        Vec3d up = forward.crossProduct(right);
        
        double halfFovTan = Math.tan(Math.toRadians(fov / 2.0));
        double aspectRatio = 16.0 / 9.0; // Default aspect ratio
        
        Vec3d nearCenter = cameraPos.add(forward.multiply(0.1));
        Vec3d farCenter = cameraPos.add(forward.multiply(1024.0 * CHUNK_SIZE));
        
        double nearHeight = 0.1 * halfFovTan;
        double nearWidth = nearHeight * aspectRatio;
        double farHeight = 1024.0 * CHUNK_SIZE * halfFovTan;
        double farWidth = farHeight * aspectRatio;
        
        Vec3d nearTopLeft = nearCenter.add(up.multiply(nearHeight)).subtract(right.multiply(nearWidth));
        Vec3d nearTopRight = nearCenter.add(up.multiply(nearHeight)).add(right.multiply(nearWidth));
        Vec3d nearBottomLeft = nearCenter.subtract(up.multiply(nearHeight)).subtract(right.multiply(nearWidth));
        Vec3d nearBottomRight = nearCenter.subtract(up.multiply(nearHeight)).add(right.multiply(nearWidth));
        
        Vec3d farTopLeft = farCenter.add(up.multiply(farHeight)).subtract(right.multiply(farWidth));
        Vec3d farTopRight = farCenter.add(up.multiply(farHeight)).add(right.multiply(farWidth));
        Vec3d farBottomLeft = farCenter.subtract(up.multiply(farHeight)).subtract(right.multiply(farWidth));
        Vec3d farBottomRight = farCenter.subtract(up.multiply(farHeight)).add(right.multiply(farWidth));
        
        frustumPlanes[0].setFromPoints(nearTopLeft, nearTopRight, farTopRight); // Top
        frustumPlanes[1].setFromPoints(nearBottomRight, nearBottomLeft, farBottomLeft); // Bottom
        frustumPlanes[2].setFromPoints(nearBottomLeft, nearTopLeft, farTopLeft); // Left
        frustumPlanes[3].setFromPoints(nearTopRight, nearBottomRight, farBottomRight); // Right
        frustumPlanes[4].setFromPoints(nearTopRight, nearTopLeft, nearBottomLeft); // Near
        frustumPlanes[5].setFromPoints(farBottomLeft, farTopLeft, farTopRight); // Far
    }
    
    private void updateLastTransform(Vec3d cameraPos, float yaw, float pitch) {
        lastCameraPos = cameraPos;
        lastYaw = yaw;
        lastPitch = pitch;
    }
    
    public boolean isChunkInFrustum(int chunkX, int chunkZ, Vec3d cameraPos) {
        processedChunks.incrementAndGet();
        
        double minX = chunkX * CHUNK_SIZE;
        double maxX = minX + CHUNK_SIZE;
        double minZ = chunkZ * CHUNK_SIZE;
        double maxZ = minZ + CHUNK_SIZE;
        double minY = -64.0;
        double maxY = 320.0;
        
        for (Plane plane : frustumPlanes) {
            if (!plane.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ)) {
                culledChunks.incrementAndGet();
                return false;
            }
        }
        
        return true;
    }
    
    public boolean isAABBInFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        for (Plane plane : frustumPlanes) {
            if (!plane.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ)) {
                return false;
            }
        }
        return true;
    }
    
    public boolean isSphereInFrustum(Vec3d center, double radius) {
        for (Plane plane : frustumPlanes) {
            if (plane.distanceToPoint(center) < -radius) {
                return false;
            }
        }
        return true;
    }
    
    public double getChunkCullingDistance(int chunkX, int chunkZ, Vec3d cameraPos) {
        double centerX = chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0;
        double centerZ = chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0;
        
        return Math.sqrt(
            Math.pow(centerX - cameraPos.x, 2) + 
            Math.pow(centerZ - cameraPos.z, 2)
        );
    }
    
    public void invalidateFrustum() {
        frustumDirty = true;
    }
    
    public long getCulledChunks() {
        return culledChunks.get();
    }
    
    public long getProcessedChunks() {
        return processedChunks.get();
    }
    
    public double getCullingEfficiency() {
        long processed = processedChunks.get();
        return processed > 0 ? (double) culledChunks.get() / processed : 0.0;
    }
    
    private static class Plane {
        private double a, b, c, d;
        
        public void setFromPoints(Vec3d p1, Vec3d p2, Vec3d p3) {
            Vec3d v1 = p2.subtract(p1);
            Vec3d v2 = p3.subtract(p1);
            Vec3d normal = v1.crossProduct(v2).normalize();
            
            this.a = normal.x;
            this.b = normal.y;
            this.c = normal.z;
            this.d = -(a * p1.x + b * p1.y + c * p1.z);
        }
        
        public double distanceToPoint(Vec3d point) {
            return a * point.x + b * point.y + c * point.z + d;
        }
        
        public boolean intersectsAABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            Vec3d pVertex = new Vec3d(
                a >= 0 ? maxX : minX,
                b >= 0 ? maxY : minY,
                c >= 0 ? maxZ : minZ
            );
            
            if (distanceToPoint(pVertex) < 0) {
                return false;
            }
            
            return true;
        }
    }
}