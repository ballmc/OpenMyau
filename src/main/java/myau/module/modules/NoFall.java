package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

public class NoFall extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private final TimerUtil packetDelayTimer = new TimerUtil();
    private final TimerUtil scoreboardResetTimer = new TimerUtil();

    private boolean slowFalling = false;
    private boolean lastOnGround = false;

    public final ModeProperty mode = new ModeProperty(
            "mode",
            0,
            new String[]{"PACKET", "BLINK", "NO_GROUND", "SPOOF", "BLOCK_LADDER"}
    );

    public final FloatProperty distance = new FloatProperty("distance", 3.0F, 0.0F, 20.0F);
    public final IntProperty delay = new IntProperty("delay", 0, 0, 10000);

    // BLOCK_LADDER config/state
    public final IntProperty clutchMinFall    = new IntProperty("clutchMinFall", 5, 1, 20);
    public final IntProperty clutchReach      = new IntProperty("clutchReach",   4, 3, 6);
    public final IntProperty clutchCooldownMs = new IntProperty("clutchCooldown", 800, 0, 5000);

    // How early (in ticks before impact) we start the sequence.
    public final IntProperty earlyPlaceTicks   = new IntProperty("earlyPlaceTicks", 3, 1, 8);

    // These are kept as settings but are effectively sanitized in logic so we don't spam placements.
    public final IntProperty pillarHeight      = new IntProperty("pillarHeight", 2, 1, 2);
    public final IntProperty ladderStackHeight = new IntProperty("ladderStack", 1, 1, 2);

    private final TimerUtil clutchTimer = new TimerUtil();

    // BLOCK_LADDER runtime state
    private boolean clutchArmed = false;
    /**
     * 0 = none,
     * 1 = waiting to/place support block,
     * 2 = waiting to/place ladder.
     */
    private int clutchStage = 0;
    private BlockPos plannedSupportGround = null;
    private BlockPos plannedSupportTop = null;
    private EnumFacing plannedFallDir = null;

    private int prevHotbarSlot = -1;

    public NoFall() {
        super("NoFall", false);
    }

    private boolean canTrigger() {
        return this.scoreboardResetTimer.hasTimeElapsed(3000)
                && this.packetDelayTimer.hasTimeElapsed(this.delay.getValue().longValue());
    }

    // ----------------------------------------------------------------
    // Packet hook
    // ----------------------------------------------------------------
    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.onDisabled();
            return;
        }
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) return;

        if (event.getPacket() instanceof C03PacketPlayer) {
            C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();

            switch (this.mode.getValue()) {
                case 0: // PACKET
                    if (this.slowFalling) {
                        this.slowFalling = false;
                        ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
                    } else if (!packet.isOnGround()) {
                        AxisAlignedBB aabb = mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0);
                        if (PlayerUtil.canFly(this.distance.getValue())
                                && !PlayerUtil.checkInWater(aabb)
                                && this.canTrigger()) {
                            this.packetDelayTimer.reset();
                            this.slowFalling = true;
                            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 0.5F;
                        }
                    }
                    break;

                case 1: // BLINK
                    boolean allowed = !mc.thePlayer.isOnLadder()
                            && !mc.thePlayer.capabilities.allowFlying
                            && mc.thePlayer.hurtTime == 0;

                    if (Myau.blinkManager.getBlinkingModule() != BlinkModules.NO_FALL) {
                        if (this.lastOnGround
                                && !packet.isOnGround()
                                && allowed
                                && PlayerUtil.canFly(this.distance.getValue().intValue())
                                && mc.thePlayer.motionY < 0.0) {
                            Myau.blinkManager.setBlinkState(false, Myau.blinkManager.getBlinkingModule());
                            Myau.blinkManager.setBlinkState(true, BlinkModules.NO_FALL);
                        }
                    } else if (!allowed) {
                        Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                        ChatUtil.sendFormatted(String.format("%s%s: &cFailed player check!&r", Myau.clientName, this.getName()));
                    } else if (PlayerUtil.checkInWater(mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0))) {
                        Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                        ChatUtil.sendFormatted(String.format("%s%s: &cFailed void check!&r", Myau.clientName, this.getName()));
                    } else if (packet.isOnGround()) {
                        for (Packet<?> blinkedPacket : Myau.blinkManager.blinkedPackets) {
                            if (blinkedPacket instanceof C03PacketPlayer) {
                                ((IAccessorC03PacketPlayer) blinkedPacket).setOnGround(true);
                            }
                        }
                        Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                        this.packetDelayTimer.reset();
                    }
                    this.lastOnGround = packet.isOnGround() && allowed && this.canTrigger();
                    break;

                case 2: // NO_GROUND
                    ((IAccessorC03PacketPlayer) packet).setOnGround(false);
                    break;

                case 3: // SPOOF
                    if (!packet.isOnGround()) {
                        AxisAlignedBB aabb2 = mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0);
                        if (PlayerUtil.canFly(this.distance.getValue())
                                && !PlayerUtil.checkInWater(aabb2)
                                && this.canTrigger()) {
                            this.packetDelayTimer.reset();
                            ((IAccessorC03PacketPlayer) packet).setOnGround(true);
                            mc.thePlayer.fallDistance = 0.0F;
                        }
                    }
                    break;

                case 4: // BLOCK_LADDER – fully driven by onTick
                default:
                    break;
            }
        }
    }

    // ----------------------------------------------------------------
    // Tick driver
    // ----------------------------------------------------------------
    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;

        if (ServerUtil.hasPlayerCountInfo()) {
            this.scoreboardResetTimer.reset();
        }

        if (this.mode.getValue() == 0 && this.slowFalling) {
            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(true));
            mc.thePlayer.fallDistance = 0.0F;
        }

        if (this.mode.getValue() == 4) {
            handleBlockLadderClutch();
        }
    }

    @Override
    public void onDisabled() {
        this.lastOnGround = false;
        Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);

        if (this.slowFalling) {
            this.slowFalling = false;
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
        }

        clutchArmed = false;
        clutchStage = 0;
        plannedSupportGround = null;
        plannedSupportTop = null;
        plannedFallDir = null;
        restorePrevSlot();
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) this.onDisabled();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    // ================================================================
    //                 BLOCK + LADDER CLUTCH (2-stage)
    // ================================================================
    private void handleBlockLadderClutch() {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.thePlayer.capabilities.isFlying || mc.thePlayer.isOnLadder()) return;

        // ----------------------------------------------------------------
        // Arm clutch on real fall
        // ----------------------------------------------------------------
        if (!clutchArmed) {
            if (mc.thePlayer.fallDistance >= clutchMinFall.getValue()
                    && mc.thePlayer.motionY < 0.0
                    && clutchTimer.hasTimeElapsed(clutchCooldownMs.getValue())) {
                planClutch();
            } else {
                return;
            }
        }

        if (!clutchArmed) return;

        // ----------------------------------------------------------------
        // Validate planned positions
        // ----------------------------------------------------------------
        if (plannedSupportGround == null || plannedSupportTop == null) {
            abortClutch();
            return;
        }

        // Estimate time to impact based on the planned ground
        double targetFeetY = plannedSupportGround.getY() + 1.001;
        int ticksToImpact = Math.max(1, estimateTicksUntilY(targetFeetY));

        // Gently steer towards the ladder block in the last ~5 ticks
        steerTowardsLadder(ticksToImpact);

        // If we are still too high up, just wait
        if (ticksToImpact > earlyPlaceTicks.getValue() + 1) {
            return;
        }

        // ----------------------------------------------------------------
        // Stage 1: place support block on top of ground (1 C08)
        // ----------------------------------------------------------------
        if (clutchStage == 1) {
            if (!isWithinReachCenter(plannedSupportTop)) {
                abortClutch();
                return;
            }

            int blockSlot = findAnyPlaceableBlockHotbar(true);
            if (blockSlot == -1) { // no blocks
                abortClutch();
                return;
            }

            rememberAndSwap(blockSlot);

            // This right-click is the only C08 we emit this tick.
            if (placeSolidOnTop(plannedSupportGround)) {
                clutchStage = 2; // advance to ladder stage for next tick
            } else {
                abortClutch();
            }
            return; // ensure we never also ladder-place in the same tick
        }

        // ----------------------------------------------------------------
        // Stage 2: place ONE ladder on side toward fall direction (1 C08)
        // ----------------------------------------------------------------
        if (clutchStage == 2) {
            // Pick a stable fall direction once
            if (plannedFallDir == null) {
                plannedFallDir = facingFromMotion();
                if (plannedFallDir == null) plannedFallDir = EnumFacing.SOUTH;
            }

            int ladderSlot = findLadderHotbar();
            if (ladderSlot == -1) {
                abortClutch();
                return;
            }

            // Use ladder slot, but keep prevHotbarSlot so we can restore after abortClutch()
            mc.thePlayer.inventory.currentItem = ladderSlot;
            mc.playerController.updateController();

            // We only place ONE ladder to avoid sending multiple C08 in the same tick.
            BlockPos ladderPos = plannedSupportTop.offset(plannedFallDir);
            if (BlockUtil.isReplaceable(ladderPos) && isWithinReachCenter(ladderPos)) {
                // Single right-click for this tick
                clickFace(plannedSupportTop, plannedFallDir);
            } else {
                // If we somehow can't place it where planned, fail this clutch
                abortClutch();
                return;
            }

            // Reset fall distance after placing ladder
            mc.thePlayer.fallDistance = 0.0F;
            // Done, reset all clutch state & restore hotbar
            abortClutch();
        }
    }

    /**
     * Plan the clutch geometry so the ladder ends up under the predicted landing column.
     *
     * Idea:
     *   - Predict landing column (Lx, Lz).
     *   - Choose fallDir.
     *   - Place support block one block "behind" the landing column, i.e. at
     *         (Lx - fallDir.offsetX, Lz - fallDir.offsetZ)
     *     so that the ladder at supportTop.offset(fallDir) is actually at (Lx, Lz).
     */
    private void planClutch() {
        // Find some ground below our current column just to get an approximate Y
        BlockPos baseGround = findGroundBelowWithinReach(clutchReach.getValue());
        if (baseGround == null) return;

        // Estimate when we reach that approximate Y
        double baseTargetFeetY = baseGround.getY() + 1.001;
        int ticksToImpact = Math.max(1, estimateTicksUntilY(baseTargetFeetY));

        // If we are very early in fall, wait and re-arm later
        if (ticksToImpact > earlyPlaceTicks.getValue() + 3) {
            return;
        }

        // Predict horizontal landing column
        BlockPos landingCol = predictLandingColumn(ticksToImpact);
        int landingX = landingCol.getX();
        int landingZ = landingCol.getZ();

        // Decide fall direction early using landing column
        EnumFacing fallDir = facingFromMotion();
        if (fallDir == null) {
            double vx = (landingX + 0.5) - mc.thePlayer.posX;
            double vz = (landingZ + 0.5) - mc.thePlayer.posZ;
            fallDir = facingFromVector(vx, vz);
        }
        if (fallDir == EnumFacing.UP || fallDir == EnumFacing.DOWN) {
            fallDir = EnumFacing.SOUTH;
        }

        // Prefer support so that ladder aligns with landing column.
        // Ladder will be at supportTop.offset(fallDir) ⇒ we want that to equal (landingX, landingZ).
        // So choose support column one block "behind" landing in the opposite of fallDir.
        int supportColX = landingX - fallDir.getFrontOffsetX();
        int supportColZ = landingZ - fallDir.getFrontOffsetZ();

        BlockPos supportGround = findGroundBelowWithinReachAtColumn(supportColX, supportColZ, clutchReach.getValue());
        if (supportGround == null) {
            // Fallback: use landing column as before
            supportGround = findGroundBelowWithinReachAtColumn(landingX, landingZ, clutchReach.getValue());
            if (supportGround == null) {
                // Final fallback: just use the ground directly below us
                supportGround = baseGround;
            }
        }

        BlockPos supportTop = supportGround.up();
        if (!isWithinReachCenter(supportTop)) return;

        plannedSupportGround = supportGround;
        plannedSupportTop = supportTop;
        plannedFallDir = fallDir;
        clutchArmed = true;
        clutchStage = 1; // first tick will be block placement
        clutchTimer.reset();
    }

    private void abortClutch() {
        clutchArmed = false;
        clutchStage = 0;
        plannedSupportGround = null;
        plannedSupportTop = null;
        plannedFallDir = null;
        restorePrevSlot();
        clutchTimer.reset();
    }

    // ---------------- helpers ----------------

    /**
     * In the last few ticks before impact, gently steer the player towards
     * the ladder block so we don't land perfectly centered on top of the block.
     */
    private void steerTowardsLadder(int ticksToImpact) {
        // Only bother when clutch is active and impact is soon
        if (!clutchArmed) return;
        if (plannedSupportTop == null) return;
        if (ticksToImpact > 5) return; // "last 5 blocks of the fall"

        EnumFacing dir = plannedFallDir;
        if (dir == null) {
            dir = facingFromMotion();
            if (dir == null) dir = EnumFacing.SOUTH;
            plannedFallDir = dir;
        }

        BlockPos ladderPos = plannedSupportTop.offset(dir);
        double targetX = ladderPos.getX() + 0.5;
        double targetZ = ladderPos.getZ() + 0.5;

        double dx = targetX - mc.thePlayer.posX;
        double dz = targetZ - mc.thePlayer.posZ;

        double distSq = dx * dx + dz * dz;
        if (distSq < 1.0E-4) return;

        double dist = Math.sqrt(distSq);

        // Small nudge per tick toward the ladder center
        double maxDelta = 0.15; // keep it moderate to not look blatant
        double scale = maxDelta / dist;

        mc.thePlayer.motionX += dx * scale;
        mc.thePlayer.motionZ += dz * scale;
    }

    private void clickFace(BlockPos base, EnumFacing face) {
        Vec3 faceClick = BlockUtil.getClickVec(base, face);
        Vec3 lowered = new Vec3(faceClick.xCoord, faceClick.yCoord - 0.05, faceClick.zCoord);
        aimAndRightClickLegit(base, face, lowered);
        mc.thePlayer.swingItem();
    }

    private boolean placeSolidOnTop(BlockPos ground) {
        Vec3 topClick = BlockUtil.getClickVec(ground, EnumFacing.UP);
        boolean placed = aimAndRightClickLegit(ground, EnumFacing.UP, topClick);
        if (!placed) return false;
        BlockPos top = ground.up();
        return BlockUtil.isSolid(mc.theWorld.getBlockState(top).getBlock());
    }

    private boolean isWithinReachCenter(BlockPos pos) {
        double distSq = mc.thePlayer.getDistanceSq(
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5
        );
        double maxReach = mc.playerController.getBlockReachDistance();
        double clamp = Math.min(maxReach, 4.5D);
        return distSq <= clamp * clamp;
    }

    private BlockPos findGroundBelowWithinReach(int maxDown) {
        BlockPos base = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ)
        );

        for (int dy = 0; dy <= maxDown + 1; dy++) {
            BlockPos check = base.down(dy);
            Block blockAt = mc.theWorld.getBlockState(check).getBlock();

            if (!mc.theWorld.isAirBlock(check) && BlockUtil.isSolid(blockAt)) {
                BlockPos up = check.up();
                if (BlockUtil.isReplaceable(up)) {
                    if (isWithinReachCenter(up)) return check;
                }
                return null;
            }
        }
        return null;
    }

    private BlockPos findGroundBelowWithinReachAtColumn(int colX, int colZ, int maxDown) {
        BlockPos base = new BlockPos(
                colX,
                MathHelper.floor_double(mc.thePlayer.posY),
                colZ
        );
        for (int dy = 0; dy <= maxDown + 1; dy++) {
            BlockPos check = base.down(dy);
            Block blockAt = mc.theWorld.getBlockState(check).getBlock();
            if (!mc.theWorld.isAirBlock(check) && BlockUtil.isSolid(blockAt)) {
                BlockPos up = check.up();
                if (BlockUtil.isReplaceable(up) && isWithinReachCenter(up)) {
                    return check;
                }
                return null;
            }
        }
        return null;
    }

    private int estimateTicksUntilY(double targetFeetY) {
        double y = mc.thePlayer.posY;
        double my = mc.thePlayer.motionY;
        int ticks = 0;
        while (ticks < 60 && y > targetFeetY) {
            my = (my - 0.08) * 0.98;
            y += my;
            ticks++;
        }
        return ticks;
    }

    private BlockPos predictLandingColumn(int ticks) {
        double px = mc.thePlayer.posX;
        double pz = mc.thePlayer.posZ;
        double mx = mc.thePlayer.motionX;
        double mz = mc.thePlayer.motionZ;

        if (ticks <= 0) {
            return new BlockPos(MathHelper.floor_double(px), 0, MathHelper.floor_double(pz));
        }

        double pow = Math.pow(0.98, ticks);
        double factor = (1.0 - pow) / (1.0 - 0.98);
        double predX = px + mx * factor;
        double predZ = pz + mz * factor;

        return new BlockPos(
                MathHelper.floor_double(predX),
                0,
                MathHelper.floor_double(predZ)
        );
    }

    private EnumFacing facingFromMotion() {
        double mx = mc.thePlayer.motionX;
        double mz = mc.thePlayer.motionZ;
        if (mx * mx + mz * mz < 1.0E-4) return null;
        if (Math.abs(mx) > Math.abs(mz)) {
            return mx > 0 ? EnumFacing.EAST : EnumFacing.WEST;
        } else {
            return mz > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
        }
    }

    private EnumFacing facingFromVector(double vx, double vz) {
        if (vx * vx + vz * vz < 1.0E-4) return EnumFacing.SOUTH;
        if (Math.abs(vx) > Math.abs(vz)) {
            return vx > 0 ? EnumFacing.EAST : EnumFacing.WEST;
        } else {
            return vz > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
        }
    }

    private boolean aimAndRightClickLegit(BlockPos pos, EnumFacing face, Vec3 clickVec) {
        double dx = clickVec.xCoord - mc.thePlayer.posX;
        double dy = clickVec.yCoord - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dz = clickVec.zCoord - mc.thePlayer.posZ;

        float[] rot = RotationUtil.getRotationsTo(
                dx, dy, dz,
                mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch
        );
        float yaw = rot[0];
        float pitch = rot[1];

        // If you have a silent-rot system, hook it here instead of directly changing rotation
        mc.thePlayer.rotationYaw = yaw;
        mc.thePlayer.rotationPitch = pitch;

        MovingObjectPosition mop = RotationUtil.rayTrace(
                yaw,
                pitch,
                mc.playerController.getBlockReachDistance(),
                1.0F
        );

        Vec3 hit = (mop != null
                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && pos.equals(mop.getBlockPos())
                && mop.sideHit == face)
                ? mop.hitVec
                : clickVec;

        return mc.playerController.onPlayerRightClick(
                mc.thePlayer,
                mc.theWorld,
                mc.thePlayer.getHeldItem(),
                pos,
                face,
                hit
        );
    }

    // ---- hotbar helpers ----

    private int findAnyPlaceableBlockHotbar(boolean excludeLadder) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
            if (ItemUtil.isBlock(s)) {
                Block b = ((ItemBlock) s.getItem()).getBlock();
                if (excludeLadder && b instanceof BlockLadder) continue;
                if (!BlockUtil.isInteractable(b) && BlockUtil.isSolid(b)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findLadderHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
            if (s != null && s.getItem() instanceof ItemBlock) {
                Block b = ((ItemBlock) s.getItem()).getBlock();
                if (b instanceof BlockLadder) return i;
            }
        }
        return -1;
    }

    private void rememberAndSwap(int slot) {
        if (prevHotbarSlot == -1) prevHotbarSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = slot;
        mc.playerController.updateController();
    }

    private void restorePrevSlot() {
        if (prevHotbarSlot != -1 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = prevHotbarSlot;
            mc.playerController.updateController();
            prevHotbarSlot = -1;
        }
    }
}
