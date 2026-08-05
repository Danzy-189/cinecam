package dev.danzy.cinecam.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * The subject every automatic mode frames.
 *
 * <p>By default this is the player, but any picked entity can take over: a mob, a boat, a
 * minecart, an arrow in flight or another player. The reference is dropped automatically as
 * soon as the entity dies, is removed or leaves the loaded world, so the camera can never
 * end up chasing a ghost.
 */
public final class CameraTarget {
    private Entity entity;

    /** The entity to frame, falling back to the player. */
    public Entity resolve(LocalPlayer player) {
        this.validate(player);
        return this.entity == null ? player : this.entity;
    }

    /** True while a custom entity is being framed instead of the player. */
    public boolean isCustom() {
        return this.entity != null;
    }

    public Entity entity() {
        return this.entity;
    }

    public void set(Entity target) {
        this.entity = target;
    }

    public void clear() {
        this.entity = null;
    }

    /**
     * Drops an invalid target.
     *
     * @return true exactly once, on the tick the target became invalid.
     */
    public boolean poll(LocalPlayer player) {
        boolean had = this.entity != null;
        this.validate(player);
        return had && this.entity == null;
    }

    public Component displayName(LocalPlayer player) {
        Entity subject = this.resolve(player);
        if (subject == null || subject == player) {
            return Component.translatable("cinecam.target.self");
        }
        return subject.getDisplayName() == null ? subject.getName() : subject.getDisplayName();
    }

    private void validate(LocalPlayer player) {
        if (this.entity == null) {
            return;
        }
        if (player == null || this.entity.isRemoved() || !this.entity.isAlive()
                || this.entity.level() != player.level()) {
            this.entity = null;
        }
    }
}
