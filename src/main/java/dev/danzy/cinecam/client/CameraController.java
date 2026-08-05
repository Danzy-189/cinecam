package dev.danzy.cinecam.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Вся логика режима камеры: состояние, движение, наведение и перехват управления.
 */
public final class CameraController {
    private static final CameraController INSTANCE = new CameraController();

    public final CameraSettings settings = new CameraSettings();

    private boolean active;
    private boolean cameraControl = true;
    private boolean uiHidden;
    private CameraMode mode = CameraMode.FREE;

    private CameraEntity camera;

    private Vec3 pos = Vec3.ZERO;
    private Vec3 prevPos = Vec3.ZERO;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 followOffset = new Vec3(0.0D, 1.5D, -4.0D);
    private double orbitAngle;

    private float yaw;
    private float pitch;
    private float roll;
    private float prevYaw;
    private float prevPitch;
    private float prevRoll;
    private float targetYaw;
    private float targetPitch;

    private float frozenYaw;
    private float frozenPitch;

    private CameraType savedCameraType = CameraType.FIRST_PERSON;

    private CameraController() {}

    public static CameraController get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return this.active;
    }

    public boolean isControllingCamera() {
        return this.active && this.cameraControl;
    }

    public boolean isUiHidden() {
        return this.uiHidden;
    }

    public CameraMode getMode() {
        return this.mode;
    }

    public Vec3 getPosition() {
        return this.pos;
    }

    public double speedPerSecond() {
        return this.settings.moveSpeed * 20.0D;
    }

    public double currentFov() {
        return this.settings.fov;
    }

    // ------------------------------------------------------------------ переключатели

    public void toggle() {
        if (this.active) {
            this.stop(true);
        } else {
            this.start();
        }
    }

    public void start() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || this.active) {
            return;
        }
        this.camera = new CameraEntity(minecraft.level);
        Vec3 eye = player.getEyePosition(1.0F);
        this.pos = eye;
        this.prevPos = eye;
        this.velocity = Vec3.ZERO;
        this.yaw = player.getYRot();
        this.prevYaw = this.yaw;
        this.targetYaw = this.yaw;
        this.pitch = Mth.clamp(player.getXRot(), -89.0F, 89.0F);
        this.prevPitch = this.pitch;
        this.targetPitch = this.pitch;
        this.roll = this.settings.roll;
        this.prevRoll = this.roll;
        this.followOffset = eye.subtract(player.position());
        this.orbitAngle = Mth.wrapDegrees(player.getYRot() + 180.0F);
        this.freezePlayer(player);
        this.camera.place(this.pos, this.prevPos, this.yaw, this.pitch, this.prevYaw, this.prevPitch);
        this.savedCameraType = minecraft.options.getCameraType();
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        minecraft.setCameraEntity(this.camera);
        this.active = true;
        this.cameraControl = true;
        this.message(Component.translatable("cinecam.msg.enabled"));
    }

    public void stop(boolean notify) {
        if (!this.active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        this.active = false;
        this.uiHidden = false;
        this.camera = null;
        this.velocity = Vec3.ZERO;
        LocalPlayer player = minecraft.player;
        if (player != null) {
            this.restorePlayerRotation(player);
            minecraft.setCameraEntity(player);
            if (notify) {
                this.message(Component.translatable("cinecam.msg.disabled"));
            }
        }
        minecraft.options.setCameraType(this.savedCameraType);
        this.settings.save();
    }

    public void toggleControl() {
        if (!this.active) {
            return;
        }
        this.cameraControl = !this.cameraControl;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && this.cameraControl) {
            this.freezePlayer(player);
        }
        this.message(Component.translatable(this.cameraControl ? "cinecam.msg.control.camera" : "cinecam.msg.control.player"));
    }

    public void cycleMode() {
        if (!this.active) {
            return;
        }
        this.setMode(this.mode.next());
    }

    public void setMode(CameraMode newMode) {
        this.mode = newMode;
        LocalPlayer player = Minecraft.getInstance().player;
        if (this.active && player != null) {
            if (newMode == CameraMode.FOLLOW) {
                this.followOffset = this.pos.subtract(player.position());
            } else if (newMode == CameraMode.ORBIT) {
                Vec3 delta = this.pos.subtract(player.position());
                double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
                if (horizontal > 0.1D) {
                    this.orbitAngle = Math.toDegrees(Math.atan2(delta.z, delta.x));
                    this.settings.orbitRadius = Mth.clamp(horizontal, 1.5D, 64.0D);
                    this.settings.orbitHeight = Mth.clamp(delta.y, -16.0D, 32.0D);
                }
            }
            this.message(Component.translatable("cinecam.msg.mode", newMode.title()));
        }
    }

    public void toggleUi() {
        this.uiHidden = !this.uiHidden;
    }

    public void toggleLetterbox() {
        this.settings.letterbox = !this.settings.letterbox;
    }

    public void toggleGrid() {
        this.settings.grid = !this.settings.grid;
    }

    public void recenter() {
        if (!this.active) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3 eye = player.getEyePosition(1.0F);
        this.pos = eye;
        this.prevPos = eye;
        this.velocity = Vec3.ZERO;
        this.yaw = player.getYRot();
        this.prevYaw = this.yaw;
        this.targetYaw = this.yaw;
        this.pitch = Mth.clamp(player.getXRot(), -89.0F, 89.0F);
        this.prevPitch = this.pitch;
        this.targetPitch = this.pitch;
        this.followOffset = eye.subtract(player.position());
        this.orbitAngle = Mth.wrapDegrees(player.getYRot() + 180.0F);
        if (this.camera != null) {
            this.camera.place(this.pos, this.prevPos, this.yaw, this.pitch, this.prevYaw, this.prevPitch);
        }
    }

    // ------------------------------------------------------------------ такт

    /** Вызывается до обработки клавиш игры, чтобы прицел брался от персонажа, а не от камеры. */
    public void beforeTick() {
        if (!this.active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            this.hardReset();
            return;
        }
        if (!this.cameraControl) {
            this.updatePlayerHitResult(minecraft, player);
        }
    }

    public void tick() {
        if (!this.active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || this.camera == null) {
            this.hardReset();
            return;
        }
        if (minecraft.getCameraEntity() != this.camera) {
            minecraft.setCameraEntity(this.camera);
        }
        if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        }

        this.prevPos = this.pos;
        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;
        this.prevRoll = this.roll;

        boolean readInput = this.cameraControl && minecraft.screen == null;
        Options options = minecraft.options;
        float forward = 0.0F;
        float strafe = 0.0F;
        float vertical = 0.0F;
        boolean sprint = false;
        if (readInput) {
            if (options.keyUp.isDown()) {
                forward += 1.0F;
            }
            if (options.keyDown.isDown()) {
                forward -= 1.0F;
            }
            if (options.keyLeft.isDown()) {
                strafe += 1.0F;
            }
            if (options.keyRight.isDown()) {
                strafe -= 1.0F;
            }
            if (options.keyJump.isDown()) {
                vertical += 1.0F;
            }
            if (options.keyShift.isDown()) {
                vertical -= 1.0F;
            }
            sprint = options.keySprint.isDown();
            if (CineCamKeys.ROLL_LEFT.isDown()) {
                this.settings.roll = Mth.clamp(this.settings.roll - 1.0F, -180.0F, 180.0F);
            }
            if (CineCamKeys.ROLL_RIGHT.isDown()) {
                this.settings.roll = Mth.clamp(this.settings.roll + 1.0F, -180.0F, 180.0F);
            }
        }

        float smoothing = Mth.clamp(this.settings.smoothing, 0.0F, 0.95F);
        double moveAlpha = Math.max(0.05D, 1.0D - smoothing);
        double speed = this.settings.moveSpeed * (sprint ? 3.0D : 1.0D);

        Vec3 wish = Vec3.ZERO;
        if (this.mode != CameraMode.ORBIT) {
            Vec3 look = Vec3.directionFromRotation(this.settings.pitchFlight ? this.pitch : 0.0F, this.yaw);
            Vec3 left = Vec3.directionFromRotation(0.0F, this.yaw - 90.0F);
            Vec3 raw = look.scale(forward).add(left.scale(strafe)).add(0.0D, vertical, 0.0D);
            if (raw.lengthSqr() > 1.0E-6D) {
                wish = raw.normalize().scale(speed);
            }
        }
        this.velocity = this.velocity.add(wish.subtract(this.velocity).scale(moveAlpha));
        if (this.velocity.lengthSqr() < 1.0E-9D) {
            this.velocity = Vec3.ZERO;
        }

        if (this.mode == CameraMode.FREE || this.mode == CameraMode.TRACK) {
            this.pos = this.pos.add(this.velocity);
        } else if (this.mode == CameraMode.FOLLOW) {
            this.followOffset = this.followOffset.add(this.velocity);
            Vec3 desired = player.position().add(this.followOffset);
            this.pos = this.pos.add(desired.subtract(this.pos).scale(Math.max(0.06D, 1.0D - smoothing)));
        } else {
            if (readInput) {
                this.settings.orbitRadius = Mth.clamp(this.settings.orbitRadius - forward * 0.25D, 1.5D, 64.0D);
                this.settings.orbitHeight = Mth.clamp(this.settings.orbitHeight + vertical * 0.25D, -16.0D, 32.0D);
                this.orbitAngle += strafe * 2.0D;
            }
            this.orbitAngle = Mth.wrapDegrees(this.orbitAngle + this.settings.orbitSpeed / 20.0D);
            double radians = Math.toRadians(this.orbitAngle);
            Vec3 desired = player.position().add(
                    Math.cos(radians) * this.settings.orbitRadius,
                    this.settings.orbitHeight,
                    Math.sin(radians) * this.settings.orbitRadius);
            this.pos = this.pos.add(desired.subtract(this.pos).scale(Math.max(0.06D, 1.0D - smoothing)));
        }

        float rotationAlpha = Math.max(0.05F, 1.0F - smoothing);
        if (this.mode.autoAim()) {
            Vec3 aim = player.position().add(0.0D, this.settings.aimHeight, 0.0D).subtract(this.pos);
            double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
            if (horizontal > 1.0E-4D || Math.abs(aim.y) > 1.0E-4D) {
                this.targetYaw = (float) (Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90.0D);
                this.targetPitch = (float) (-Math.toDegrees(Math.atan2(aim.y, horizontal)));
            }
        }
        this.yaw = this.yaw + Mth.wrapDegrees(this.targetYaw - this.yaw) * rotationAlpha;
        this.pitch = Mth.clamp(this.pitch + (this.targetPitch - this.pitch) * rotationAlpha, -90.0F, 90.0F);
        this.roll = this.roll + (this.settings.roll - this.roll) * rotationAlpha;

        this.camera.place(this.pos, this.prevPos, this.yaw, this.pitch, this.prevYaw, this.prevPitch);
    }

    // ------------------------------------------------------------------ кадр

    public void applyCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!this.active) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (this.cameraControl) {
            float deltaYaw = Mth.wrapDegrees(player.getYRot() - this.frozenYaw);
            float deltaPitch = player.getXRot() - this.frozenPitch;
            if (deltaYaw != 0.0F || deltaPitch != 0.0F) {
                if (!this.mode.autoAim()) {
                    this.targetYaw = Mth.wrapDegrees(this.targetYaw + deltaYaw);
                    this.targetPitch = Mth.clamp(this.targetPitch + deltaPitch, -90.0F, 90.0F);
                }
                this.restorePlayerRotation(player);
            }
        }
        float partialTick = (float) event.getPartialTick();
        event.setYaw(this.renderYaw(partialTick));
        event.setPitch(this.renderPitch(partialTick));
        event.setRoll(this.renderRoll(partialTick));
    }

    private boolean instantRotation() {
        return !this.mode.autoAim() && this.settings.smoothing <= 0.02F;
    }

    private float renderYaw(float partialTick) {
        return this.instantRotation() ? this.targetYaw : Mth.rotLerp(partialTick, this.prevYaw, this.yaw);
    }

    private float renderPitch(float partialTick) {
        return this.instantRotation() ? this.targetPitch : Mth.lerp(partialTick, this.prevPitch, this.pitch);
    }

    private float renderRoll(float partialTick) {
        return Mth.lerp(partialTick, this.prevRoll, this.roll);
    }

    // ------------------------------------------------------------------ ввод

    public boolean handleScroll(double delta) {
        if (!this.isControllingCamera() || delta == 0.0D) {
            return false;
        }
        if (Screen.hasControlDown()) {
            this.settings.fov = Mth.clamp(this.settings.fov - delta * 2.0D, 10.0D, 130.0D);
            this.settings.customFov = true;
        } else {
            this.settings.moveSpeed = Mth.clamp(this.settings.moveSpeed * Math.pow(1.15D, delta), 0.02D, 4.0D);
        }
        return true;
    }

    private void freezePlayer(LocalPlayer player) {
        this.frozenYaw = Mth.wrapDegrees(player.getYRot());
        this.frozenPitch = Mth.clamp(player.getXRot(), -80.0F, 80.0F);
        this.restorePlayerRotation(player);
    }

    private void restorePlayerRotation(LocalPlayer player) {
        player.setYRot(this.frozenYaw);
        player.setXRot(this.frozenPitch);
        player.yRotO = this.frozenYaw;
        player.xRotO = this.frozenPitch;
        player.setYHeadRot(this.frozenYaw);
        player.yHeadRotO = this.frozenYaw;
        player.yBodyRot = this.frozenYaw;
        player.yBodyRotO = this.frozenYaw;
    }

    private void updatePlayerHitResult(Minecraft minecraft, LocalPlayer player) {
        double blockReach = player.isCreative() ? 5.0D : 4.5D;
        double entityReach = 3.0D;
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 view = player.getViewVector(1.0F);
        HitResult blockHit = player.pick(blockReach, 1.0F, false);
        double blockDistance = blockHit.getLocation().distanceToSqr(eye);
        Vec3 end = eye.add(view.scale(entityReach));
        AABB box = player.getBoundingBox().expandTowards(view.scale(entityReach)).inflate(1.0D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, eye, end, box, entity -> !entity.isSpectator() && entity.isPickable(), entityReach * entityReach);
        if (entityHit != null && entityHit.getLocation().distanceToSqr(eye) < blockDistance) {
            minecraft.hitResult = entityHit;
            minecraft.crosshairPickEntity = entityHit.getEntity();
        } else {
            minecraft.hitResult = blockHit;
            minecraft.crosshairPickEntity = null;
        }
    }

    private void hardReset() {
        this.active = false;
        this.uiHidden = false;
        this.camera = null;
        this.velocity = Vec3.ZERO;
    }

    private void message(Component component) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(component, true);
        }
    }
}
