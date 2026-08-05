package dev.danzy.cinecam.client.path;

import net.minecraft.world.phys.Vec3;

/** A camera pose sampled from a path at some point in time. */
public record PathSample(Vec3 position, float yaw, float pitch, float roll, double fov) {}
