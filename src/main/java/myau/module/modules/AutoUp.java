package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.*;

public class AutoUp extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // 0 = SILENT, 1 = LOCK_VIEW
    public final ModeProperty rotations       = new ModeProperty("rotations", 0, new String[]{"SILENT", "LOCK_VIEW"});
    public final BooleanProperty autoJump     = new BooleanProperty("auto-jump", true);
    public final BooleanProperty toolSwitch   = new BooleanProperty("tool-switch", true);
    public final BooleanProperty autoBlockSwitch = new BooleanProperty("auto-block-switch", true);
    public final BooleanProperty swing        = new BooleanProperty("swing", true);

    private int savedSlot = -1;

    private enum Mode {
        MINE,   // mine exactly one ceiling block
        PILLAR  // jump once + place once
    }

    private Mode mode = Mode.MINE;
    private BlockPos targetCeiling = null;

    // pillar state
    private boolean jumpedThisCycle = false;
    private boolean placedThisCycle = false;
    private BlockPos pillarBase = null; // block we jumped from / are pillaring on

    public AutoUp() {
        super("AutoUp", false);
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer != null) {
            savedSlot = mc.thePlayer.inventory.currentItem;
        } else {
            savedSlot = -1;
        }
        mode = Mode.MINE;
        targetCeiling = null;
        jumpedThisCycle = false;
        placedThisCycle = false;
        pillarBase = null;
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null && savedSlot != -1) {
            mc.thePlayer.inventory.currentItem = savedSlot;
        }
        targetCeiling = null;
        jumpedThisCycle = false;
        placedThisCycle = false;
        pillarBase = null;
    }

    // -------- helpers --------

    private BlockPos getFeet() {
        return new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
    }

    private BlockPos getCeilingPos(BlockPos feet) {
        // “one block directly above your head” (2 above feet for a 2-block-tall player)
        return feet.up(2);
    }

    private boolean isSolid(Block b) {
        if (b == null) return false;
        Material m = b.getMaterial();
        return m.isSolid() && !m.isLiquid();
    }

    private boolean isSolidAt(BlockPos pos) {
        Block b = mc.theWorld.getBlockState(pos).getBlock();
        return isSolid(b);
    }

    private int findBestToolSlot(Block block) {
        if (!toolSwitch.getValue()) return -1;

        int bestSlot = -1;
        float bestSpeed = 1.0F;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null) continue;
            float speed = stack.getStrVsBlock(block);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private int findBlockSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null || stack.stackSize <= 0) continue;
            Item item = stack.getItem();
            if (item instanceof ItemBlock) {
                Block b = ((ItemBlock) item).getBlock();
                if (isSolid(b)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void applyRotations(UpdateEvent event, float yaw, float pitch) {
        pitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);

        event.setRotation(yaw, pitch, 6);
        if (rotations.getValue() == 1) { // LOCK_VIEW
            Myau.rotationManager.setRotation(yaw, pitch, 6, true);
        }

        // move-fix always ON when rotations spoofing is active with priority 3
        if (RotationState.isActived()
                && RotationState.getPriority() == 3.0F
                && MoveUtil.isForwardPressed()) {

            event.setPervRotation(yaw, 3);
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    private void mineCeilingNow(UpdateEvent event, float yaw, BlockPos ceilingPos) {
        Block block = mc.theWorld.getBlockState(ceilingPos).getBlock();
        if (!isSolid(block)) return;

        int toolSlot = findBestToolSlot(block);
        if (toolSlot != -1) {
            mc.thePlayer.inventory.currentItem = toolSlot;
        }

        // look straight up
        float pitchUp = -90.0F;
        applyRotations(event, yaw, pitchUp);

        mc.playerController.onPlayerDamageBlock(ceilingPos, EnumFacing.DOWN);
        if (swing.getValue()) {
            mc.thePlayer.swingItem();
        } else {
            PacketUtil.sendPacket(new C0APacketAnimation());
        }
    }

    // -------- main logic --------

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.currentScreen != null) return;

        float yaw = event.getYaw();
        BlockPos feet = getFeet();
        BlockPos ceiling = getCeilingPos(feet);
        Block ceilingBlock = mc.theWorld.getBlockState(ceiling).getBlock();

        switch (mode) {
            case MINE: {
                // target = exactly one block above head
                if (targetCeiling == null || !targetCeiling.equals(ceiling)) {
                    targetCeiling = ceiling;
                }

                // ----- CASE 1: still a solid ceiling block: mine it -----
                if (isSolid(ceilingBlock)) {
                    if (mc.thePlayer.onGround) {
                        // full-speed mining like holding LMB straight up
                        mineCeilingNow(event, yaw, targetCeiling);
                    }
                    return;
                }

                // ----- CASE 2: ceiling just became air while on ground → INSTANT jump & start pillar -----
                if (!isSolid(ceilingBlock) && mc.thePlayer.onGround) {
                    int blockSlot = findBlockSlot();
                    if (blockSlot == -1) {
                        // no blocks to pillar with
                        return;
                    }

                    // base block we stand on right now
                    pillarBase = new BlockPos(feet.getX(), feet.getY() - 1, feet.getZ());

                    if (autoBlockSwitch.getValue()) {
                        mc.thePlayer.inventory.currentItem = blockSlot;
                    }

                    if (autoJump.getValue()) {
                        mc.thePlayer.jump();
                        jumpedThisCycle = true;
                        placedThisCycle = false;
                        mode = Mode.PILLAR;
                    }

                    return;
                }

                break;
            }

            case PILLAR: {
                int blockSlot = findBlockSlot();
                if (blockSlot == -1) {
                    // no blocks, nothing we can do
                    return;
                }

                // safety: refresh base while on ground and not mid-cycle
                if (mc.thePlayer.onGround && !jumpedThisCycle) {
                    pillarBase = new BlockPos(feet.getX(), feet.getY() - 1, feet.getZ());
                }

                // A) if something desynced and we are back on ground with no jump registered,
                //    restart from MINE logic and mine immediately
                if (!jumpedThisCycle && mc.thePlayer.onGround) {
                    mode = Mode.MINE;
                    targetCeiling = getCeilingPos(getFeet());
                    if (isSolidAt(targetCeiling)) {
                        mineCeilingNow(event, yaw, targetCeiling);
                    }
                    return;
                }

                // B) while we are in the air and haven’t placed yet, spam place under us
                if (jumpedThisCycle && !placedThisCycle && !mc.thePlayer.onGround) {
                    if (autoBlockSwitch.getValue()) {
                        mc.thePlayer.inventory.currentItem = blockSlot;
                    }

                    ItemStack held = mc.thePlayer.getHeldItem();
                    if (held != null && held.getItem() instanceof ItemBlock && pillarBase != null && isSolidAt(pillarBase)) {
                        // look straight down
                        float pitchDown = 90.0F;
                        applyRotations(event, yaw, pitchDown);

                        EnumFacing side = EnumFacing.UP;

                        // "Spam" a few slightly different hitVecs in the same tick
                        for (int i = 0; i < 3 && !placedThisCycle; i++) {
                            double jitter = 0.001D * i;

                            Vec3 hitVec = new Vec3(
                                    pillarBase.getX() + 0.5D + jitter,
                                    pillarBase.getY() + 1.0D - 0.001D,
                                    pillarBase.getZ() + 0.5D - jitter
                            );

                            if (mc.playerController.onPlayerRightClick(
                                    mc.thePlayer, mc.theWorld, held,
                                    pillarBase, side, hitVec
                            )) {
                                if (swing.getValue()) {
                                    mc.thePlayer.swingItem();
                                } else {
                                    PacketUtil.sendPacket(new C0APacketAnimation());
                                }
                                placedThisCycle = true;
                            }
                        }
                    }
                }

                // C) once we’ve jumped & placed and landed, immediately start mining new ceiling (no extra tick)
                if (jumpedThisCycle && placedThisCycle && mc.thePlayer.onGround) {
                    BlockPos newFeet = getFeet();
                    BlockPos newCeiling = getCeilingPos(newFeet);

                    if (isSolidAt(newCeiling)) {
                        mode = Mode.MINE;
                        targetCeiling = newCeiling;

                        // immediate first hit on the new block in the SAME tick we land
                        mineCeilingNow(event, yaw, targetCeiling);
                    } else {
                        // still no ceiling – keep pillaring
                        mode = Mode.PILLAR;
                    }

                    jumpedThisCycle = false;
                    placedThisCycle = false;
                    pillarBase = new BlockPos(newFeet.getX(), newFeet.getY() - 1, newFeet.getZ());
                }
                break;
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!isEnabled()) return;

        // move-fix always on when rotations are active with prio 3
        if (RotationState.isActived()
                && RotationState.getPriority() == 3.0F
                && MoveUtil.isForwardPressed()) {

            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }
}
