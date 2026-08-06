package dev.danzy.cinecam.client;

import dev.danzy.cinecam.client.path.AnchorFrame;
import dev.danzy.cinecam.client.path.CameraPath;
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
import net.minecraft.world.entity.LivingEntity;
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

    /** Ticks the chase camera waits after a manual orbit before it straightens up again. */
    private static final int MANUAL_HOLD = 12;
    /** How far the target picker reaches. */
    private static final double PICK_RANGE = 128.0D;
    /** Horizontal speed treated as full throttle by the rig, in blocks per tick. */
    private static final double FULL_SPEED = 0.216D;

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

    // Third person chase rig.
    /** Heading the rig is locked to: the subject's own facing, damped by the stiffness. */
    private float rigYaw;
    /** Manual yaw offset away from dead centre behind the subject. Recentres to zero. */
    private float orbitYaw;
    /** Manual pitch offset away from the resting rig pitch. Recentres to zero. */
    private float orbitPitch;
    /**
     * Vertical angle the rig actually used on the last frame, whichever side of the control
     * switch it came from. Handing the mouse over reads it, so neither direction jumps.
     */
    private float followViewPitch;
    /** Smoothed arm length, smoothed pivot height and smoothed forward lead. */
    private double followArm;
    private double followPivotY;
    private double followLead;
    /** Smoothed 0..1 throttle factor and its signed counterpart (negative when reversing). */
    private double driveSmooth;
    private double driveSigned;
    private int followManual;
    private boolean followReady;

    /** Field of view dictated by the path while it plays, or -1. */
    private double playbackFov = -1.0D;
    /**
     * True while the camera is parked on a keyframe or on a scrubbed point of the path. The
     * chase rig stands down in that state, otherwise it would drag the camera off the pose the
     * operator is inspecting.
     */
    private boolean pathPreview;
    /** Partial tick of the frame being rendered, so anchors can be interpolated smoothly. */
    private float framePartial = 1.0F;

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
        this.pathPreview = false;
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
        this.followReady = false;
        this.pathPreview = false;
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
        this.followReady = false;
        this.pathPreview = false;
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

    /**
     * Swaps the mouse and keyboard between the camera and the player.
     *
     * <p>In the chase rig the vertical angle is owned by whoever holds the mouse, so the
     * angle is handed across on every switch: the player's look starts exactly where the
     * camera was pointing, and coming back turns that look into an orbit offset. Without this
     * the shot would snap on every press.
     */
    public void toggleControl() {
        if (!this.active) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        boolean toCamera = !this.cameraControl;
        if (player != null && this.mode == CameraMode.FOLLOW) {
            float resting = this.clampFollowPitch(this.settings.followPitch);
            if (toCamera) {
                float up = Mth.clamp(this.settings.followPitchUp, 0.0F, 85.0F);
                float down = Mth.clamp(this.settings.followPitchDown, 0.0F, 85.0F);
                this.orbitPitch = Mth.clamp(this.followViewPitch - resting, -down - resting, up - resting);
            } else {
                float handover = this.clampFollowPitch(this.followViewPitch);
                player.setXRot(handover);
                player.xRotO = handover;
                this.orbitPitch = 0.0F;
                this.followViewPitch = handover;
            }
        }
        this.cameraControl = toCamera;
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
        this.pathPreview = false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (!this.active || player == null) {
            return;
        }
        Entity subject = this.target.resolve(player);
        this.anchorToSubject(subject);
        this.velocity = Vec3.ZERO;
        if (newMode == CameraMode.FOLLOW) {
            // Adopt whatever framing the camera already has so the switch does not jump,
            // then let the auto centre walk it back behind the subject.
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
        this.pathPreview = false;
        Entity subject = this.target.resolve(player);
        if (this.mode == CameraMode.FOLLOW) {
            // In a chase rig recentering means "snap straight behind the back", not
            // "teleport onto the player".
            this.resetFollowRig(subject);
            if (!this.cameraControl && subject == player) {
                // The player owns the vertical angle right now, so level their look instead.
                float resting = this.clampFollowPitch(this.settings.followPitch);
                player.setXRot(resting);
                player.xRotO = resting;
                this.followViewPitch = resting;
            }
            return;
        }
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
        this.driveSmooth = 0.0D;
        this.driveSigned = 0.0D;
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

    /**
     * The frame an attached path is measured against: the subject's interpolated position and
     * its body heading. Heads are ignored on purpose, because a glance sideways must not swing
     * a whole flight path around.
     */
    public AnchorFrame anchorFrame() {
        return this.anchorFrame(this.framePartial);
    }

    public AnchorFrame anchorFrame(float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return AnchorFrame.NONE;
        }
        Entity subject = this.target.resolve(player);
        if (subject == null) {
            return AnchorFrame.NONE;
        }
        float partial = Mth.clamp(partialTick, 0.0F, 1.0F);
        Vec3 origin = new Vec3(
                Mth.lerp((double) partial, subject.xo, subject.getX()),
                Mth.lerp((double) partial, subject.yo, subject.getY()),
                Mth.lerp((double) partial, subject.zo, subject.getZ()));
        return AnchorFrame.of(origin, anchorYaw(subject, partial));
    }

    private static float anchorYaw(Entity subject, float partial) {
        if (subject instanceof LivingEntity living) {
            return Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot);
        }
        return Mth.rotLerp(partial, subject.yRotO, subject.getYRot());
    }

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
                this.currentFov(), this.settings.pathDefaultDuration, this.anchorFrame(1.0F));
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
        CameraPath path = PathManager.get().path();
        AnchorFrame frame = this.anchorFrame(1.0F);
        this.playbackFov = -1.0D;
        this.position = path.worldPosition(keyframe.position, frame);
        this.prevPosition = this.position;
        this.velocity = Vec3.ZERO;
        this.yaw = path.worldYaw(keyframe.yaw, frame);
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
        this.pathPreview = true;
        this.pushToEntity();
    }

    /**
     * Parks the camera at an arbitrary time on the path. Dragging the timeline calls this on
     * every mouse move, so the operator previews the shot instead of imagining it.
     */
    public void previewPath(double seconds) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!this.active) {
            this.start();
        }
        PathManager paths = PathManager.get();
        paths.stop();
        paths.seek(seconds);
        PathSample sample = paths.sampleAt(paths.time(), this.anchorFrame(1.0F));
        if (sample == null) {
            return;
        }
        this.playbackFov = -1.0D;
        this.position = sample.position();
        this.prevPosition = this.position;
        this.velocity = Vec3.ZERO;
        this.yaw = sample.yaw();
        this.pitch = sample.pitch();
        this.roll = sample.roll();
        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;
        this.prevRoll = this.roll;
        this.targetYaw = this.yaw;
        this.targetPitch = this.pitch;
        if (this.settings.customFov) {
            this.settings.fov = Mth.clamp(sample.fov(), 10.0D, 130.0D);
        }
        this.pathPreview = true;
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
        this.pathPreview = false;
        // Jump straight onto the first pose so the opening frame is already correct.
        PathSample first = paths.sampleAt(0.0D, this.anchorFrame(1.0F));
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
     * from the spline, so a take is identical every single time it is played. On an attached
     * path the spline itself rides along with the subject, which is what makes a flyaround of a
     * moving boat possible.
     */
    private void tickPlayback(LocalPlayer player) {
        PathManager paths = PathManager.get();
        PathSample sample = paths.advance(this.settings.pathSpeed / 20.0D, this.anchorFrame(1.0F));
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

        // Touching the controls takes the camera off a parked keyframe.
        if (this.pathPreview && (forward != 0.0D || strafe != 0.0D || vertical != 0.0D)) {
            this.pathPreview = false;
        }

        float smoothing = Mth.clamp(this.settings.smoothing, 0.0F, 0.95F);
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

        // Pulling away and settling down are two different feelings, so they get two
        // different ramps: a punchy start with a long soft stop is what an operator does on a
        // real slider, and one shared "smoothing" number could never express it.
        boolean braking = wish.lengthSqr() <= this.velocity.lengthSqr();
        double moveAlpha = rampAlpha(braking ? this.settings.moveDecel : this.settings.moveAccel);
        this.velocity = this.velocity.add(wish.subtract(this.velocity).scale(moveAlpha));
        if (wish.lengthSqr() < 1.0E-9D && this.velocity.lengthSqr() < 1.0E-6D) {
            // Kill the last millimetre of drift so a locked off shot is genuinely locked off.
            this.velocity = Vec3.ZERO;
        }

        this.aimPoint = this.aimPointOf(subject);

        float rotAlpha = (float) Math.max(0.05D, 1.0D - smoothing);

        switch (this.mode) {
            case FREE:
            case TRACK:
                this.position = this.position.add(this.velocity);
                break;
            case FOLLOW: {
                this.velocity = Vec3.ZERO;
                this.tickFollow(subject, acceptInput, forward, strafe, vertical, speed);
                // The chase rig itself is rebuilt frame by frame in updateFrame(), so the
                // tick only advances the roll and leaves the pose alone.
                this.roll += (this.settings.roll - this.roll) * rotAlpha;
                return;
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
     * The heading the chase camera hides behind.
     *
     * <p>For the local player that is the look direction, because that is what steering
     * actually is in Minecraft. For everything else it is the body rotation, which does not
     * twitch when a mob glances sideways. Entities that carry no meaningful rotation of their
     * own, such as a lit stick of TNT, fall back to the direction they are travelling in.
     */
    private float subjectFacing(Entity subject) {
        if (subject == Minecraft.getInstance().player) {
            return Mth.wrapDegrees(subject.getYRot());
        }
        if (subject instanceof LivingEntity living) {
            return Mth.wrapDegrees(living.yBodyRot);
        }
        if (this.subjectMotion.horizontalDistanceSqr() > 2.5E-3D) {
            return (float) Mth.wrapDegrees(
                    Math.toDegrees(Math.atan2(-this.subjectMotion.x, this.subjectMotion.z)));
        }
        return Mth.wrapDegrees(subject.getYRot());
    }

    /**
     * Measures how far the subject travelled this tick.
     *
     * <p>{@code getDeltaMovement} is only meaningful for the local player: remote entities are
     * interpolated towards packet positions and usually report zero, which would stop the
     * follow camera from ever reacting to a mob or a boat.
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
    // Third person chase rig
    // ------------------------------------------------------------------

    /** Keeps the chase pitch inside the operator's own up and down limits. */
    private float clampFollowPitch(float pitch) {
        float up = Mth.clamp(this.settings.followPitchUp, 0.0F, 85.0F);
        float down = Mth.clamp(this.settings.followPitchDown, 0.0F, 85.0F);
        return Mth.clamp(pitch, -down, up);
    }

    /**
     * Vertical angle for the chase rig while the player is the one being driven.
     *
     * <p>Steering a character in third person means the mouse points the camera, so the rig
     * simply takes the player's own look angle. The same up and down limits apply, and the
     * look itself is clamped rather than only the camera: if the head were allowed past the
     * stop, the crosshair would keep climbing while the picture stayed put, and every hit
     * would land somewhere other than where it was aimed.
     */
    private float drivenPitch(LocalPlayer player) {
        float limited = this.clampFollowPitch(player.getXRot());
        if (player.getXRot() != limited) {
            player.setXRot(limited);
        }
        float previous = this.clampFollowPitch(player.xRotO);
        if (player.xRotO != previous) {
            // Keeps the render interpolation inside the limit too, so the stop does not
            // shimmer while the mouse is pushed against it.
            player.xRotO = previous;
        }
        return limited;
    }

    /**
     * Tick half of the chase rig: keyboard input, the manual hold timer and the throttle
     * factor that drives both the look ahead and how briskly the camera straightens up.
     */
    private void tickFollow(Entity subject, boolean acceptInput,
            double forward, double strafe, double vertical, double speed) {
        CameraSettings config = this.settings;
        if (!this.followReady) {
            this.resetFollowRig(subject);
        }

        if (acceptInput) {
            if (forward != 0.0D) {
                config.followDistance = Mth.clamp(config.followDistance - forward * speed, 0.5D, 24.0D);
            }
            if (strafe != 0.0D) {
                this.orbitYaw = Mth.wrapDegrees(this.orbitYaw - (float) (strafe * 2.5D));
                this.followManual = MANUAL_HOLD;
            }
            if (vertical != 0.0D) {
                // The keys move the resting height of the rig, the mouse only borrows it.
                config.followPitch = this.clampFollowPitch(config.followPitch - (float) (vertical * 1.5D));
            }
        }

        if (this.followManual > 0) {
            this.followManual--;
        }

        double motion = Math.sqrt(this.subjectMotion.horizontalDistanceSqr());
        double drive = Mth.clamp(motion / FULL_SPEED, 0.0D, 1.0D);
        this.driveSmooth += (drive - this.driveSmooth) * 0.12D;

        // Signed throttle: reversing pulls the pivot slightly back instead of forward.
        Vec3 facing = Vec3.directionFromRotation(0.0F, this.rigYaw);
        double along = this.subjectMotion.x * facing.x + this.subjectMotion.z * facing.z;
        this.driveSigned = Mth.clamp(along / FULL_SPEED, -0.4D, 1.0D);
    }

    /**
     * Frame half of the chase rig.
     *
     * <p>Everything that the eye can see is rebuilt here rather than in the tick: at 20 ticks
     * a second a rig that only moves on tick boundaries feels like dragging the view through
     * treacle, which is exactly what a third person camera must not do. The pose is written
     * straight into the camera entity with the previous position set equal to the current one,
     * so {@code Camera#setup} interpolates onto precisely this pose no matter what partial
     * tick it is handed.
     */
    public void updateFrame(float partialTick, float frameTicks) {
        this.framePartial = Mth.clamp(partialTick, 0.0F, 1.0F);
        if (!this.active || this.camera == null || this.mode != CameraMode.FOLLOW || this.pathPreview) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || PathManager.get().isPlaying()) {
            return;
        }

        this.consumeMouse(player);

        Entity subject = this.target.resolve(player);
        if (!this.followReady) {
            this.resetFollowRig(subject);
        }

        float partial = this.framePartial;
        float step = Mth.clamp(frameTicks, 0.0F, 5.0F);
        CameraSettings config = this.settings;

        // 1. Lock the rig onto the subject's heading. This is the whole point of the fix:
        // it no longer waits for the subject to be moving, so turning on the spot turns
        // the camera with the back.
        float facing = this.subjectFacing(subject);
        float stiffness = Mth.clamp(config.followStiffness, 0.0F, 1.0F);
        if (stiffness >= 0.999F) {
            this.rigYaw = facing;
        } else if (stiffness > 0.0F) {
            this.rigYaw = approachAngle(this.rigYaw, facing,
                    frameAlpha(0.06F + 0.94F * stiffness * stiffness, step));
        }

        // 2. Straighten up after a manual orbit, briskly at speed and lazily at a standstill.
        if (this.followManual <= 0 && config.followRecenter > 0.0F) {
            float rate = frameAlpha(
                    (float) (config.followRecenter * (0.02D + 0.28D * this.driveSmooth)), step);
            this.orbitYaw -= this.orbitYaw * rate;
            this.orbitPitch -= this.orbitPitch * rate * 0.5F;
            if (Math.abs(this.orbitYaw) < 0.05F) {
                this.orbitYaw = 0.0F;
            }
        }

        // 3. Pivot: the subject's interpolated position with a damped height, so stairs and
        // jumps do not shake the frame.
        double subjectX = Mth.lerp((double) partial, subject.xo, subject.getX());
        double subjectY = Mth.lerp((double) partial, subject.yo, subject.getY());
        double subjectZ = Mth.lerp((double) partial, subject.zo, subject.getZ());
        double wantedPivotY = subjectY + this.aimHeightFor(subject);
        if (Math.abs(wantedPivotY - this.followPivotY) > 4.0D) {
            this.followPivotY = wantedPivotY;
        } else {
            this.followPivotY += (wantedPivotY - this.followPivotY)
                    * frameAlpha(subject.onGround() ? 0.55F : 0.20F, step);
        }

        // 4. Look ahead: at speed the pivot slides forward so more of the road is on screen.
        double wantedLead = config.followLookAhead * this.driveSigned;
        this.followLead += (wantedLead - this.followLead) * frameAlpha(0.09F, step);

        float yawTotal = Mth.wrapDegrees(this.rigYaw + this.orbitYaw);
        // 5. Vertical angle. Two owners, one pair of limits: the mouse feeds the orbit offset
        // while the camera is being flown, and the player's own look drives it while the
        // character is being steered.
        float pitchTotal;
        if (!this.cameraControl && subject == player) {
            pitchTotal = this.drivenPitch(player);
            this.orbitPitch = 0.0F;
        } else {
            pitchTotal = this.clampFollowPitch(config.followPitch + this.orbitPitch);
        }
        this.followViewPitch = pitchTotal;
        Vec3 look = Vec3.directionFromRotation(pitchTotal, yawTotal);
        Vec3 side = Vec3.directionFromRotation(0.0F, yawTotal + 90.0F);
        Vec3 lead = Vec3.directionFromRotation(0.0F, this.rigYaw).scale(this.followLead);
        Vec3 pivot = new Vec3(subjectX, this.followPivotY, subjectZ).add(lead);
        Vec3 anchor = pivot.add(side.scale(config.followShoulder));

        // 6. Spring arm, pulled in by walls and eased back out when they are gone.
        double wanted = config.followDistance + followPadding(subject);
        if (config.followCollision) {
            wanted = clipArm(minecraft.level, subject, anchor, look, wanted);
        }
        this.followArm += (wanted - this.followArm)
                * frameAlpha(wanted < this.followArm ? 0.75F : 0.14F, step);

        Vec3 desired = anchor.subtract(look.scale(this.followArm));
        this.prevPosition = this.position;
        this.position = desired;
        this.aimPoint = pivot;

        // 7. Frame the pivot exactly. With no shoulder offset this is the rig angle itself,
        // so the subject is pinned dead centre instead of sliding around.
        Vec3 aim = pivot.subtract(desired);
        double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
        float lookYaw = yawTotal;
        float lookPitch = pitchTotal;
        if (horizontal > 1.0E-4D || Math.abs(aim.y) > 1.0E-4D) {
            lookYaw = (float) (Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90.0D);
            lookPitch = (float) (-Math.toDegrees(Math.atan2(aim.y, horizontal)));
        }
        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;
        this.yaw = lookYaw;
        this.pitch = lookPitch;
        this.targetYaw = lookYaw;
        this.targetPitch = lookPitch;

        this.pushFrameToEntity();
    }

    /**
     * Reads this frame's mouse movement off the frozen player and turns it into an orbit.
     *
     * <p>The player is turned by vanilla once per frame, before anything is rendered, so this
     * is genuine per frame input rather than the tick sized steps the rig used to take.
     */
    private boolean consumeMouse(LocalPlayer player) {
        if (!this.cameraControl) {
            return false;
        }
        float deltaYaw = Mth.wrapDegrees(player.getYRot() - this.frozenYaw);
        float deltaPitch = player.getXRot() - this.frozenPitch;
        if (deltaYaw == 0.0F && deltaPitch == 0.0F) {
            return false;
        }
        if (!PathManager.get().isPlaying()) {
            this.applyOrbitInput(deltaYaw, deltaPitch);
        }
        this.restorePlayerRotation(player);
        return true;
    }

    /**
     * Mouse look for the chase rig: a temporary offset from the subject's back, kept inside
     * the configured up and down limits so the view can never tip over the top or sink under
     * the floor.
     */
    private void applyOrbitInput(float deltaYaw, float deltaPitch) {
        float sensitivity = (float) Mth.clamp(this.settings.followSensitivity, 0.1D, 4.0D);
        this.orbitYaw = Mth.wrapDegrees(this.orbitYaw + deltaYaw * sensitivity);
        float resting = this.clampFollowPitch(this.settings.followPitch);
        float up = Mth.clamp(this.settings.followPitchUp, 0.0F, 85.0F);
        float down = Mth.clamp(this.settings.followPitchDown, 0.0F, 85.0F);
        this.orbitPitch = Mth.clamp(this.orbitPitch + deltaPitch * sensitivity,
                -down - resting, up - resting);
        this.followManual = MANUAL_HOLD;
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
        this.rigYaw = this.subjectFacing(subject);
        this.orbitYaw = 0.0F;
        this.orbitPitch = 0.0F;
        this.followViewPitch = this.clampFollowPitch(this.settings.followPitch);
        this.followArm = this.settings.followDistance + followPadding(subject);
        this.followPivotY = subject.getY() + this.aimHeightFor(subject);
        this.followLead = 0.0D;
        this.driveSmooth = 0.0D;
        this.driveSigned = 0.0D;
        this.followManual = 0;
        this.followReady = true;
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
        float worldYaw = (float) Math.toDegrees(Math.atan2(offset.x, -offset.z));
        float worldPitch = Mth.clamp(
                (float) Math.toDegrees(Math.asin(Mth.clamp(offset.y / length, -1.0D, 1.0D))), -85.0F, 85.0F);
        float resting = this.clampFollowPitch(this.settings.followPitch);
        float up = Mth.clamp(this.settings.followPitchUp, 0.0F, 85.0F);
        float down = Mth.clamp(this.settings.followPitchDown, 0.0F, 85.0F);
        this.rigYaw = this.subjectFacing(subject);
        this.orbitYaw = Mth.wrapDegrees(worldYaw - this.rigYaw);
        this.orbitPitch = Mth.clamp(worldPitch - resting, -down - resting, up - resting);
        this.followViewPitch = this.clampFollowPitch(resting + this.orbitPitch);
        this.settings.followDistance = Mth.clamp(length - followPadding(subject), 0.5D, 24.0D);
        this.followArm = length;
        this.followPivotY = anchor.y;
        this.followLead = 0.0D;
        this.followManual = MANUAL_HOLD;
        this.followReady = true;
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
                // While a path plays or the camera is parked on a keyframe the mouse must not
                // touch the shot, but the player still has to be kept still.
                if (!PathManager.get().isPlaying() && !this.pathPreview) {
                    if (this.mode == CameraMode.FOLLOW) {
                        // Normally already consumed for this frame by updateFrame().
                        this.applyOrbitInput(deltaYaw, deltaPitch);
                    } else if (!this.mode.autoAim()) {
                        this.targetYaw = Mth.wrapDegrees(this.targetYaw + deltaYaw);
                        this.targetPitch = Mth.clamp(this.targetPitch + deltaPitch, -90.0F, 90.0F);
                    }
                }
                this.restorePlayerRotation(player);
            }
        } else if (this.mode == CameraMode.FOLLOW && this.followReady && !this.pathPreview
                && !PathManager.get().isPlaying() && this.target.resolve(player) == player) {
            // Second line of defence for the driven player: this fires after the mouse has
            // been applied for the frame, so the limit holds even if the rig was not solved.
            this.drivenPitch(player);
        }
        float partialTick = (float) event.getPartialTick();
        if (this.mode == CameraMode.FOLLOW && this.followReady && !PathManager.get().isPlaying()) {
            // The chase rig is already solved for this exact frame, so it must not be
            // interpolated a second time.
            event.setYaw(this.yaw);
            event.setPitch(this.pitch);
            event.setRoll(this.renderRoll(partialTick));
            return;
        }
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

    /**
     * Hands the camera entity a pose that is already correct for this frame. Both the current
     * and the previous position are set to it, so vanilla's interpolation is a no-op.
     */
    private void pushFrameToEntity() {
        if (this.camera == null) {
            return;
        }
        this.camera.setPos(this.position.x, this.position.y, this.position.z);
        this.camera.xo = this.position.x;
        this.camera.yo = this.position.y;
        this.camera.zo = this.position.z;
        this.camera.xOld = this.position.x;
        this.camera.yOld = this.position.y;
        this.camera.zOld = this.position.z;
        this.camera.setYRot(this.yaw);
        this.camera.setXRot(this.pitch);
        this.camera.yRotO = this.yaw;
        this.camera.xRotO = this.pitch;
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

    /**
     * Turns a ramp time in seconds into a per tick approach factor. The ramp is how long the
     * camera takes to cover roughly ninety five percent of a speed change, which is the number
     * an operator can actually feel and dial in.
     */
    private static double rampAlpha(double seconds) {
        double clamped = Mth.clamp(seconds, 0.0D, 3.0D);
        if (clamped <= 0.02D) {
            return 1.0D;
        }
        return Mth.clamp(1.0D - Math.exp(-3.0D / (20.0D * clamped)), 0.02D, 1.0D);
    }

    /** Converts a per tick smoothing factor into one for a frame of the given length. */
    private static float frameAlpha(float perTick, float frameTicks) {
        if (perTick <= 0.0F || frameTicks <= 0.0F) {
            return 0.0F;
        }
        if (perTick >= 1.0F) {
            return 1.0F;
        }
        return 1.0F - (float) Math.pow(1.0F - perTick, frameTicks);
    }

    private static float approachAngle(float current, float target, float alpha) {
        return current + Mth.wrapDegrees(target - current) * alpha;
    }

    private static void message(LocalPlayer player, Component component) {
        player.displayClientMessage(component, true);
    }
}
