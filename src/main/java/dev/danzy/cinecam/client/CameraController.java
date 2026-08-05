package dev.danzy.cinecam.client;

import dev.danzy.cinecam.client.path.Keyframe;
import dev.danzy.cinecam.client.path.PathManager;
import dev.danzy.cinecam.client.path.PathSample;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** All CineCam state and movement logic. Client side only. */
public class CameraController {
    private static final CameraController INSTANCE = new CameraController();

    /** Ticks the follow camera waits after a manual orbit before it aligns again. */
    private static final int MANUAL_HOLD = 30;
    /** How far the target picker reaches. */
    private static final double PICK_RANGE = 128.0D;

    public final CameraSettings settings = new CameraSettings();

    private final CameraTarget target = new CameraTarget();

    private boolean active;
    private boolean cameraControl;
    private boolean uiHidden;
    private CameraMode mode = CameraMode.FREE;
    private CameraEntity camera;

    private Vec3 position = Vec3.ZERO;
    private Vec3 prevPosition = Vec3.ZERO;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 aimPoint = Vec3.ZERO;
    private double orbitAngle;

    /** Measured movement of the framed subject, valid for remote entities too. */
    private Vec3 subjectMotion = Vec3.ZERO;
    private Vec3 subjectLastPos;

    /** Third person rig: orbit angles, smoothed arm length and smoothed pivot height. */
    private float followYaw;
    private float followPitch;
    private double followArm;
    private double followPivotY;
    private int followManual;

    /** Field of view dictated by the path while it plays, or -1. */
    private double playbackFov = -1.0D;

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

    public boolean isPlayingPath() {
        return this.active && PathManager.get().isPlaying();
    }

    public CameraMode getMode() {
        return this.mode;
    }

    public Vec3 getPosition() {
        return this.position;
    }

    public float getYaw() {
        return this.yaw;
    }

    public float getPitch() {
        return this.pitch;
    }

    public float getRoll() {
        return this.roll;
    }

    public CameraTarget target() {
        return this.target;
    }

    public Component targetName() {
        return this.target.displayName(Minecraft.getInstance().player);
    }

    public double speedPerSecond() {
        return this.settings.moveSpeed * 20.0D;
    }

    public double currentFov() {
        if (this.playbackFov > 0.0D && PathManager.get().isPlaying()) {
            return this.playbackFov;
        }
        if (this.settings.customFov) {
            return this.settings.fov;
        }
        return Minecraft.getInstance().options.fov().get().doubleValue();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

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
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        Entity subject = this.target.resolve(player);
        this.camera = new CameraEntity(level);
        this.position = player.getEyePosition(1.0F);
        this.prevPosition = this.position;
        this.velocity = Vec3.ZERO;
        this.yaw = Mth.wrapDegrees(player.getYRot());
        this.pitch = Mth.clamp(player.getXRot(), -89.0F, 89.0F);
        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;
        this.targetYaw = this.yaw;
        this.targetPitch = this.pitch;
        this.roll = this.settings.roll;
        this.prevRoll = this.roll;
        this.orbitAngle = Mth.wrapDegrees(subject.getYRot() + 180.0F);
        this.subjectLastPos = null;
        this.subjectMotion = Vec3.ZERO;
        this.resetFollowRig(subject);
        this.aimPoint = this.aimPointOf(subject);
        this.freezePlayer(player);
        this.pushToEntity();
        this.savedCameraType = minecraft.options.getCameraType();
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        minecraft.setCameraEntity(this.camera);
        this.active = true;
        this.cameraControl = true;
        this.uiHidden = false;
        message(player, Component.translatable("cinecam.msg.enabled"));
    }

    public void stop(boolean notify) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        PathManager.get().stop();
        this.playbackFov = -1.0D;
        this.active = false;
        this.cameraControl = false;
        this.uiHidden = false;
        this.camera = null;
        this.velocity = Vec3.ZERO;
        if (player != null) {
            this.restorePlayerRotation(player);
            minecraft.setCameraEntity(player);
        }
        minecraft.options.setCameraType(this.savedCameraType);
        this.settings.save();
        if (notify && player != null) {
            message(player, Component.translatable("cinecam.msg.disabled"));
        }
    }

    private void hardReset() {
        Minecraft minecraft = Minecraft.getInstance();
        PathManager.get().stop();
        this.playbackFov = -1.0D;
        this.active = false;
        this.cameraControl = false;
        this.uiHidden = false;
        this.camera = null;
        this.velocity = Vec3.ZERO;
        this.target.clear();
        if (minecraft.player != null) {
            minecraft.setCameraEntity(minecraft.player);
        }
        minecraft.options.setCameraType(this.savedCameraType);
    }

    // ------------------------------------------------------------------
    // Commands bound to keys
    // ------------------------------------------------------------------

    public void toggleControl() {
        if (!this.active) {
            return;
        }
        this.cameraControl = !this.cameraControl;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            if (this.cameraControl) {
                this.freezePlayer(player);
            }
            message(player, Component.translatable(this.cameraControl
                    ? "cinecam.msg.control.camera"
                    : "cinecam.msg.control.player"));
        }
    }

    public void cycleMode() {
        this.setMode(this.mode.next());
    }

    public void setMode(CameraMode newMode) {
        this.mode = newMode;
        LocalPlayer player = Minecraft.getInstance().player;
        if (!this.active || player == null) {
            return;
        }
        Entity subject = this.target.resolve(player);
        this.anchorToSubject(subject);
        this.velocity = Vec3.ZERO;
        if (newMode == CameraMode.FOLLOW) {
            // Adopt whatever framing the camera already has so the switch does not jump.
            this.adoptFollowRig(subject);
        }
        message(player, Component.translatable("cinecam.msg.mode", newMode.title()));
    }

    public void toggleUi() {
        if (!this.active) {
            return;
        }
        this.uiHidden = !this.uiHidden;
    }

    public void recenter() {
        if (!this.active) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Entity subject = this.target.resolve(player);
        this.position = player.getEyePosition(1.0F);
        this.prevPosition = this.position;
        this.velocity = Vec3.ZERO;
        this.yaw = Mth.wrapDegrees(player.getYRot());
        this.pitch = Mth.clamp(player.getXRot(), -89.0F, 89.0F);
        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;
        this.targetYaw = this.yaw;
        this.targetPitch = this.pitch;
        this.orbitAngle = Mth.wrapDegrees(subject.getYRot() + 180.0F);
        this.resetFollowRig(subject);
        this.pushToEntity();
    }

    public void toggleLetterbox() {
        this.settings.letterbox = !this.settings.letterbox;
        this.settings.save();
    }

    public void toggleGrid() {
        this.settings.grid = !this.settings.grid;
        this.settings.save();
    }

    public boolean handleScroll(double delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!this.active || !this.cameraControl || delta == 0.0D || minecraft.screen != null) {
            return false;
        }
        if (PathManager.get().isPlaying()) {
            return true;
        }
        if (Screen.hasControlDown()) {
            this.settings.customFov = true;
            this.settings.fov = Mth.clamp(this.settings.fov - delta * 2.0D, 10.0D, 130.0D);
        } else if (this.mode == CameraMode.FOLLOW) {
            // In a third person rig the wheel is the zoom, exactly like every other game.
            this.settings.followDistance = Mth.clamp(this.settings.followDistance - delta * 0.5D, 0.5D, 24.0D);
        } else {
            this.settings.moveSpeed = Mth.clamp(this.settings.moveSpeed * Math.pow(1.15D, delta), 0.02D, 4.0D);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Target picking
    // ------------------------------------------------------------------

    /**
     * Picks whatever the current view is pointing at as the new subject.
     *
     * <p>The ray starts at the camera while the camera is being flown and at the player's eyes
     * otherwise, so the pick always matches what is on screen. Terrain blocks the ray, and
     * pointing at nothing hands the camera back to the player.
     */
    public void pickTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }

        Vec3 origin;
        Vec3 direction;
        if (this.active && this.cameraControl) {
            origin = this.position;
            direction = Vec3.directionFromRotation(this.pitch, this.yaw);
        } else {
            origin = player.getEyePosition(1.0F);
            direction = player.getViewVector(1.0F);
        }

        Vec3 end = origin.add(direction.scale(PICK_RANGE));
        HitResult blockHit = level.clip(new ClipContext(origin, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (blockHit.getType() != HitResult.Type.MISS) {
            end = blockHit.getLocation();
        }
        AABB box = new AABB(origin, end).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, origin, end, box,
                candidate -> !candidate.isSpectator() && candidate.isPickable() && candidate != this.camera,
                PICK_RANGE * PICK_RANGE);

        if (hit != null) {
            Entity picked = hit.getEntity();
            this.target.set(picked);
            this.onTargetChanged(player);
            message(player, Component.translatable("cinecam.msg.target.set", picked.getDisplayName()));
        } else if (this.target.isCustom()) {
            this.target.clear();
            this.onTargetChanged(player);
            message(player, Component.translatable("cinecam.msg.target.cleared"));
        } else {
            message(player, Component.translatable("cinecam.msg.target.none"));
        }
    }

    public void clearTarget() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!this.target.isCustom()) {
            return;
        }
        this.target.clear();
        if (player != null) {
            this.onTargetChanged(player);
            message(player, Component.translatable("cinecam.msg.target.cleared"));
        }
    }

    /** Re-hangs the rigs around the new subject without moving the camera. */
    private void onTargetChanged(LocalPlayer player) {
        this.subjectLastPos = null;
        this.subjectMotion = Vec3.ZERO;
        if (!this.active) {
            return;
        }
        Entity subject = this.target.resolve(player);
        this.anchorToSubject(subject);
        this.adoptFollowRig(subject);
    }

    /** Rebuilds the orbit parameters from where the camera already hangs. */
    private void anchorToSubject(Entity subject) {
        Vec3 offset = this.position.subtract(subject.position());
        double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        if (horizontal > 0.1D) {
            this.settings.orbitRadius = Mth.clamp(horizontal, 1.5D, 64.0D);
            this.orbitAngle = Math.toDegrees(Math.atan2(offset.z, offset.x));
        }
        this.settings.orbitHeight = Mth.clamp(offset.y, -16.0D, 32.0D);
    }

    // ------------------------------------------------------------------
    // Camera paths
    // ------------------------------------------------------------------

    /** Stores the current pose as a keyframe at the end of the path. */
    public void captureKeyframe() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!this.active) {
            message(player, Component.translatable("cinecam.msg.path.inactive"));
            return;
        }
        PathManager paths = PathManager.get();
        int index = paths.capture(this.position, this.yaw, this.pitch, this.roll,
                this.currentFov(), this.settings.pathDefaultDuration);
        message(player, Component.translatable("cinecam.msg.keyframe.added",
                index + 1, paths.path().size()));
    }

    /** Moves the live camera onto a stored keyframe so it can be reviewed or re-shot. */
    public void applyKeyframe(Keyframe keyframe) {
        if (keyframe == null) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!this.active) {
            this.start();
        }
        PathManager.get().stop();
        this.playbackFov = -1.0D;
        this.position = keyframe.position;
        this.prevPosition = this.position;
        this.velocity = Vec3.ZERO;
        this.yaw = keyframe.yaw;
        this.pitch = keyframe.pitch;
        this.roll = keyframe.roll;
        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;
        this.prevRoll = this.roll;
        this.targetYaw = this.yaw;
        this.targetPitch = this.pitch;
        if (this.settings.customFov) {
            this.settings.fov = Mth.clamp(keyframe.fov, 10.0D, 130.0D);
        }
        this.adoptFollowRig(this.target.resolve(player));
        this.pushToEntity();
    }

    public void togglePlayback() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        PathManager paths = PathManager.get();
        if (paths.isPlaying()) {
            this.endPlayback(player, true);
            return;
        }
        if (paths.path().isEmpty()) {
            message(player, Component.translatable("cinecam.msg.path.empty"));
            return;
        }
        if (!this.active) {
            this.start();
        }
        if (!paths.play()) {
            return;
        }
        // Jump straight onto the first pose so the opening frame is already correct.
        PathSample first = paths.path().sample(0.0D);
        if (first != null) {
            this.position = first.position();
            this.prevPosition = this.position;
            this.yaw = first.yaw();
            this.pitch = first.pitch();
            this.roll = first.roll();
            this.prevYaw = this.yaw;
            this.prevPitch = this.pitch;
            this.prevRoll = this.roll;
            this.targetYaw = this.yaw;
            this.targetPitch = this.pitch;
            this.playbackFov = first.fov();
            this.pushToEntity();
        }
        message(player, Component.translatable("cinecam.msg.path.playing",
                paths.path().size(), String.format(java.util.Locale.ROOT, "%.1f", paths.path().duration())));
    }

    /** Hands control back after playback, keeping the final framing. */
    private void endPlayback(LocalPlayer player, boolean notify) {
        PathManager paths = PathManager.get();
        paths.stop();
        this.playbackFov = -1.0D;
        this.targetYaw = this.yaw;
        this.targetPitch = this.pitch;
        this.velocity = Vec3.ZERO;
        this.adoptFollowRig(this.target.resolve(player));
        if (notify) {
            message(player, Component.translatable("cinecam.msg.path.stopped"));
        }
    }

    /**
     * Playback owns the camera completely: position, angles, roll and field of view all come
     * from the spline, so a take is identical every single time it is played.
     */
    private void tickPlayback(LocalPlayer player) {
        PathManager paths = PathManager.get();
        PathSample sample = paths.advance(this.settings.pathSpeed / 20.0D);
        if (sample == null) {
            this.playbackFov = -1.0D;
            return;
        }
        this.position = sample.position();
        this.velocity = Vec3.ZERO;
        Entity subject = this.target.resolve(player);
        this.aimPoint = this.aimPointOf(subject);
        if (paths.path().aimTarget()) {
            // A dolly with a live subject: the rails are scripted, the framing is not.
            Vec3 aim = this.aimPoint.subtract(this.position);
            double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
            if (horizontal > 1.0E-4D || Math.abs(aim.y) > 1.0E-4D) {
                this.yaw = (float) (Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90.0D);
                this.pitch = (float) (-Math.toDegrees(Math.atan2(aim.y, horizontal)));
            }
        } else {
            this.yaw = sample.yaw();
            this.pitch = sample.pitch();
        }
        this.roll = sample.roll();
        this.playbackFov = sample.fov();
        this.targetYaw = this.yaw;
        this.targetPitch = this.pitch;
        this.followManual = MANUAL_HOLD;
        this.pushToEntity();
        if (paths.consumeFinished()) {
            this.endPlayback(player, false);
            message(player, Component.translatable("cinecam.msg.path.finished"));
        }
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /** Runs before the vanilla client tick: fixes what the player is aiming at. */
    public void beforeTick() {
        if (!this.active || this.cameraControl) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        double reach = player.isCreative() ? 5.0D : 4.5D;
        HitResult blockHit = player.pick(reach, 1.0F, false);
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 view = player.getViewVector(1.0F);
        Vec3 end = eye.add(view.scale(3.0D));
        AABB box = player.getBoundingBox().expandTowards(view.scale(3.0D)).inflate(1.0D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(player, eye, end, box,
                candidate -> !candidate.isSpectator() && candidate.isPickable(), 9.0D);
        HitResult result = blockHit;
        Entity picked = null;
        if (entityHit != null) {
            double entityDistance = eye.distanceToSqr(entityHit.getLocation());
            double blockDistance = blockHit == null ? Double.MAX_VALUE : eye.distanceToSqr(blockHit.getLocation());
            if (entityDistance < blockDistance) {
                result = entityHit;
                picked = entityHit.getEntity();
            }
        }
        minecraft.hitResult = result;
        minecraft.crosshairPickEntity = picked;
    }

    public void tick() {
        if (!this.active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || this.camera == null
                || this.camera.level() != minecraft.level) {
            this.hardReset();
            return;
        }
        if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        }
        if (minecraft.getCameraEntity() != this.camera) {
            minecraft.setCameraEntity(this.camera);
        }

        if (this.target.poll(player)) {
            this.subjectLastPos = null;
            this.subjectMotion = Vec3.ZERO;
            message(player, Component.translatable("cinecam.msg.target.lost"));
        }

        this.prevPosition = this.position;
        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;
        this.prevRoll = this.roll;

        Entity subject = this.target.resolve(player);
        this.trackSubjectMotion(subject);

        if (PathManager.get().isPlaying()) {
            this.tickPlayback(player);
            return;
        }

        Options options = minecraft.options;
        boolean acceptInput = this.cameraControl && minecraft.screen == null;
        double forward = 0.0D;
        double strafe = 0.0D;
        double vertical = 0.0D;
        boolean sprint = false;
        if (acceptInput) {
            if (options.keyUp.isDown()) {
                forward += 1.0D;
            }
            if (options.keyDown.isDown()) {
                forward -= 1.0D;
            }
            if (options.keyLeft.isDown()) {
                strafe += 1.0D;
            }
            if (options.keyRight.isDown()) {
                strafe -= 1.0D;
            }
            if (options.keyJump.isDown()) {
                vertical += 1.0D;
            }
            if (options.keyShift.isDown()) {
                vertical -= 1.0D;
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
        double chaseAlpha = Math.max(0.06D, 1.0D - smoothing);
        double speed = this.settings.moveSpeed * (sprint ? 3.0D : 1.0D);

        Vec3 wish = Vec3.ZERO;
        if (forward != 0.0D || strafe != 0.0D || vertical != 0.0D) {
            Vec3 look = Vec3.directionFromRotation(this.settings.pitchFlight ? this.pitch : 0.0F, this.yaw);
            Vec3 left = Vec3.directionFromRotation(0.0F, this.yaw - 90.0F);
            Vec3 raw = look.scale(forward).add(left.scale(strafe)).add(0.0D, vertical, 0.0D);
            if (raw.lengthSqr() > 1.0E-6D) {
                wish = raw.normalize().scale(speed);
            }
        }
        this.velocity = this.velocity.add(wish.subtract(this.velocity).scale(moveAlpha));

        this.aimPoint = this.aimPointOf(subject);

        switch (this.mode) {
            case FREE:
            case TRACK:
                this.position = this.position.add(this.velocity);
                break;
            case FOLLOW: {
                this.velocity = Vec3.ZERO;
                this.updateFollow(minecraft.level, subject, acceptInput, forward, strafe, vertical, speed, chaseAlpha);
                break;
            }
            case ORBIT: {
                this.velocity = Vec3.ZERO;
                if (acceptInput) {
                    if (forward != 0.0D) {
                        this.settings.orbitRadius = Mth.clamp(this.settings.orbitRadius - forward * speed, 1.5D, 64.0D);
                    }
                    if (vertical != 0.0D) {
                        this.settings.orbitHeight = Mth.clamp(this.settings.orbitHeight + vertical * speed, -16.0D, 32.0D);
                    }
                    if (strafe != 0.0D) {
                        this.orbitAngle = Mth.wrapDegrees(this.orbitAngle + strafe * 2.0D);
                    }
                }
                this.orbitAngle = Mth.wrapDegrees(this.orbitAngle + this.settings.orbitSpeed / 20.0D);
                double radians = Math.toRadians(this.orbitAngle);
                Vec3 desired = subject.position().add(
                        Math.cos(radians) * this.settings.orbitRadius,
                        this.settings.orbitHeight,
                        Math.sin(radians) * this.settings.orbitRadius);
                this.position = this.position.add(desired.subtract(this.position).scale(chaseAlpha));
                break;
            }
            default:
                break;
        }

        float rotAlpha = (float) Math.max(0.05D, 1.0D - smoothing);
        if (this.mode.autoAim()) {
            Vec3 aim = this.aimPoint.subtract(this.position);
            double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
            if (horizontal > 1.0E-4D || Math.abs(aim.y) > 1.0E-4D) {
                this.targetYaw = (float) (Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90.0D);
                this.targetPitch = (float) (-Math.toDegrees(Math.atan2(aim.y, horizontal)));
            }
        }
        this.yaw = approachAngle(this.yaw, this.targetYaw, rotAlpha);
        this.pitch = Mth.clamp(this.pitch + (this.targetPitch - this.pitch) * rotAlpha, -90.0F, 90.0F);
        this.roll += (this.settings.roll - this.roll) * rotAlpha;
        this.pushToEntity();
    }

    // ------------------------------------------------------------------
    // Subject helpers
    // ------------------------------------------------------------------

    /**
     * Aim height scales with the subject, so the same setting frames a player at chest height,
     * a boat just above the deck and a dragon somewhere near its neck.
     */
    private double aimHeightFor(Entity subject) {
        double scale = Mth.clamp(subject.getBbHeight() / 1.8D, 0.25D, 6.0D);
        return this.settings.aimHeight * scale;
    }

    private Vec3 aimPointOf(Entity subject) {
        return subject.position().add(0.0D, this.aimHeightFor(subject), 0.0D);
    }

    /** Extra arm length so the rig clears bulky subjects instead of sitting inside them. */
    private static double followPadding(Entity subject) {
        double size = Math.max(subject.getBbWidth(), subject.getBbHeight());
        return Mth.clamp((size - 1.8D) * 0.6D, 0.0D, 16.0D);
    }

    /**
     * Measures how far the subject travelled this tick.
     *
     * <p>{@code getDeltaMovement} is only meaningful for the local player: remote entities are
     * interpolated towards packet positions and usually report zero, which would stop the
     * follow camera from ever swinging behind a mob or a boat.
     */
    private void trackSubjectMotion(Entity subject) {
        Vec3 now = subject.position();
        if (this.subjectLastPos == null) {
            this.subjectMotion = Vec3.ZERO;
        } else {
            Vec3 delta = now.subtract(this.subjectLastPos);
            // Ignore teleports, they are not movement the camera should chase.
            this.subjectMotion = delta.lengthSqr() > 100.0D ? Vec3.ZERO : delta;
        }
        this.subjectLastPos = now;
    }

    // ------------------------------------------------------------------
    // Third person follow rig
    // ------------------------------------------------------------------

    /**
     * A spring arm anchored to the subject instead of a fixed world offset. The arm keeps its
     * own orbit angles, drifts back behind the subject while it moves, is pulled in by blocks
     * and damps the vertical motion so jumps and stairs do not shake the frame.
     */
    private void updateFollow(ClientLevel level, Entity subject, boolean acceptInput,
            double forward, double strafe, double vertical, double speed, double chaseAlpha) {
        CameraSettings config = this.settings;

        if (acceptInput) {
            if (forward != 0.0D) {
                config.followDistance = Mth.clamp(config.followDistance - forward * speed, 0.5D, 24.0D);
            }
            if (strafe != 0.0D) {
                this.followYaw = Mth.wrapDegrees(this.followYaw - (float) (strafe * 2.5D));
                this.followManual = MANUAL_HOLD;
            }
            if (vertical != 0.0D) {
                this.followPitch = Mth.clamp(this.followPitch - (float) (vertical * 1.5D), -80.0F, 80.0F);
                config.followPitch = this.followPitch;
                this.followManual = MANUAL_HOLD;
            }
        }

        if (this.followManual > 0) {
            this.followManual--;
        } else if (config.followAlign > 0.0F) {
            // Swing behind the subject, faster the faster it moves. This is what makes the
            // camera turn with the character instead of sliding sideways next to them.
            double motion = Math.sqrt(this.subjectMotion.horizontalDistanceSqr());
            double drive = Mth.clamp(motion / 0.12D, 0.0D, 1.0D);
            float alpha = (float) (config.followAlign * 0.30D * (0.20D + 0.80D * drive));
            this.followYaw = approachAngle(this.followYaw, Mth.wrapDegrees(subject.getYRot()), alpha);
            this.followPitch += (config.followPitch - this.followPitch) * alpha * 0.5F;
        }

        double targetPivotY = subject.getY() + this.aimHeightFor(subject);
        double pivotDelta = targetPivotY - this.followPivotY;
        if (Math.abs(pivotDelta) > 4.0D) {
            this.followPivotY = targetPivotY;
        } else {
            this.followPivotY += pivotDelta * (subject.onGround() ? 0.5D : 0.18D);
        }

        Vec3 look = Vec3.directionFromRotation(this.followPitch, this.followYaw);
        Vec3 side = Vec3.directionFromRotation(0.0F, this.followYaw + 90.0F);
        Vec3 anchor = new Vec3(subject.getX(), this.followPivotY, subject.getZ())
                .add(side.scale(config.followShoulder));

        double wanted = config.followDistance + followPadding(subject);
        if (config.followCollision) {
            wanted = clipArm(level, subject, anchor, look, wanted);
        }
        // Snap in when a wall appears, glide back out when it is gone.
        double armAlpha = wanted < this.followArm ? 0.7D : 0.12D;
        this.followArm += (wanted - this.followArm) * armAlpha;

        Vec3 desired = anchor.subtract(look.scale(this.followArm));
        if (desired.distanceToSqr(this.position) > 256.0D) {
            this.position = desired;
        } else {
            this.position = this.position.add(desired.subtract(this.position).scale(chaseAlpha));
        }
        this.aimPoint = anchor;
    }

    /** Vanilla style zoom clipping: eight offset rays so the arm never pokes through a wall. */
    private static double clipArm(ClientLevel level, Entity subject, Vec3 anchor, Vec3 look, double wanted) {
        double best = wanted;
        for (int corner = 0; corner < 8; corner++) {
            double offsetX = ((corner & 1) * 2 - 1) * 0.12D;
            double offsetY = (((corner >> 1) & 1) * 2 - 1) * 0.12D;
            double offsetZ = (((corner >> 2) & 1) * 2 - 1) * 0.12D;
            Vec3 from = anchor.add(offsetX, offsetY, offsetZ);
            Vec3 to = from.subtract(look.scale(wanted));
            HitResult hit = level.clip(new ClipContext(from, to,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, subject));
            if (hit.getType() != HitResult.Type.MISS) {
                double distance = hit.getLocation().distanceTo(anchor);
                if (distance < best) {
                    best = distance;
                }
            }
        }
        return Math.max(0.25D, best - 0.15D);
    }

    /** Puts the rig straight behind the subject at the configured distance. */
    private void resetFollowRig(Entity subject) {
        this.followYaw = Mth.wrapDegrees(subject.getYRot());
        this.followPitch = Mth.clamp(this.settings.followPitch, -80.0F, 80.0F);
        this.followArm = this.settings.followDistance + followPadding(subject);
        this.followPivotY = subject.getY() + this.aimHeightFor(subject);
        this.followManual = 0;
    }

    /** Rebuilds the rig from where the camera currently hangs, so switching modes is seamless. */
    private void adoptFollowRig(Entity subject) {
        Vec3 anchor = this.aimPointOf(subject);
        Vec3 offset = this.position.subtract(anchor);
        double length = offset.length();
        if (length < 0.3D) {
            this.resetFollowRig(subject);
            return;
        }
        this.followYaw = (float) Math.toDegrees(Math.atan2(offset.x, -offset.z));
        this.followPitch = Mth.clamp(
                (float) Math.toDegrees(Math.asin(Mth.clamp(offset.y / length, -1.0D, 1.0D))), -80.0F, 80.0F);
        this.settings.followDistance = Mth.clamp(length - followPadding(subject), 0.5D, 24.0D);
        this.followArm = length;
        this.followPivotY = anchor.y;
        this.followManual = MANUAL_HOLD;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

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
                // While a path plays the mouse must not touch the shot, but the player still
                // has to be kept still.
                if (!PathManager.get().isPlaying()) {
                    if (this.mode == CameraMode.FOLLOW) {
                        // The mouse orbits the rig, like the right stick in a third person game.
                        this.followYaw = Mth.wrapDegrees(this.followYaw + deltaYaw);
                        this.followPitch = Mth.clamp(this.followPitch + deltaPitch, -80.0F, 80.0F);
                        this.followManual = MANUAL_HOLD;
                    } else if (!this.mode.autoAim()) {
                        this.targetYaw = Mth.wrapDegrees(this.targetYaw + deltaYaw);
                        this.targetPitch = Mth.clamp(this.targetPitch + deltaPitch, -90.0F, 90.0F);
                    }
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
        if (PathManager.get().isPlaying()) {
            // Spline angles are updated once per tick and must be interpolated between frames.
            return false;
        }
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void pushToEntity() {
        if (this.camera == null) {
            return;
        }
        this.camera.setPos(this.position.x, this.position.y, this.position.z);
        this.camera.xo = this.prevPosition.x;
        this.camera.yo = this.prevPosition.y;
        this.camera.zo = this.prevPosition.z;
        this.camera.xOld = this.prevPosition.x;
        this.camera.yOld = this.prevPosition.y;
        this.camera.zOld = this.prevPosition.z;
        this.camera.setYRot(this.yaw);
        this.camera.setXRot(this.pitch);
        this.camera.yRotO = this.prevYaw;
        this.camera.xRotO = this.prevPitch;
    }

    private void freezePlayer(LocalPlayer player) {
        this.frozenYaw = Mth.wrapDegrees(player.getYRot());
        this.frozenPitch = Mth.clamp(player.getXRot(), -90.0F, 90.0F);
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

    private static float approachAngle(float current, float target, float alpha) {
        return current + Mth.wrapDegrees(target - current) * alpha;
    }

    private static void message(LocalPlayer player, Component component) {
        player.displayClientMessage(component, true);
    }
}
