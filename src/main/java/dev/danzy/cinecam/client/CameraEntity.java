package dev.danzy.cinecam.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Невидимая клиентская сущность, которая работает "штативом": Minecraft рисует мир
 * из её позиции, пока настоящий игрок остаётся на месте.
 * В мир она не добавляется, тикается вручную из {@link CameraController}.
 */
public class CameraEntity extends Entity {
    public CameraEntity(Level level) {
        super(EntityType.MARKER, level);
        this.noPhysics = true;
        this.noCulling = true;
    }

    /** Записывает текущую и предыдущую позицию, чтобы ванильная камера сама всё интерполировала. */
    public void place(Vec3 current, Vec3 previous, float yaw, float pitch, float previousYaw, float previousPitch) {
        this.setPos(current.x, current.y, current.z);
        this.xo = previous.x;
        this.yo = previous.y;
        this.zo = previous.z;
        this.xOld = previous.x;
        this.yOld = previous.y;
        this.zOld = previous.z;
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.yRotO = previousYaw;
        this.xRotO = previousPitch;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public boolean isInvisible() {
        return true;
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return false;
    }
}
