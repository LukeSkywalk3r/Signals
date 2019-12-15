package com.minemaarten.signals.tileentity;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.Lists;
import com.minemaarten.signals.api.ICartHopperBehaviour;
import com.minemaarten.signals.api.IRail;
import com.minemaarten.signals.api.access.ICartHopper;
import com.minemaarten.signals.capabilities.CapabilityMinecartDestination;
import com.minemaarten.signals.init.ModBlocks;
import com.minemaarten.signals.network.GuiSynced;
import com.minemaarten.signals.rail.RailManager;
import com.minemaarten.signals.rail.network.mc.RailNetworkManager;
import com.minemaarten.signals.tileentity.carthopperbehaviour.CartHopperBehaviourItems;

public class TileEntityCartHopper extends TileEntityBase implements ITickable, IGUIButtonSensitive, ICartHopper, 
	IItemHandler, IGUITextFieldSensitive {
    
    public enum TextFieldIds{
        MAX_ITEM_TRANSFER_INACTIVITY(0),
        MINIMUM_CART_WAIT_TIME(1),
        ;
        
        private final int index;
        
        private TextFieldIds(final int index) {
            this.index = index;
        }
        
        public int getIndex() {
            return index;
        }
    }

    @GuiSynced
    private HopperMode hopperMode = HopperMode.CART_FULL;
    /**
     * When true, the Cart Engine capability will be filled/emptied instead.
     */
    @GuiSynced
    private boolean interactEngine;
    /**
     * The direction the cart is pushed in when activated.
     */
    private EnumFacing pushDir = EnumFacing.NORTH;
    private EntityMinecart managingCart;
    private UUID managingCartId;
    private boolean pushedLastTick;
    private int lastComparatorInputOverride;
    private boolean firstTick = true;
    private boolean extract;
    private long lastActivityAt = 0;
    @GuiSynced
    private int maximumItemTransferInactivityTime = 0;
    private long cartArrivedAt = 0;
    @GuiSynced
    private int minimumCartWaitTime = 0;
    
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag){
        tag.setByte("hopperMode", (byte)hopperMode.ordinal());
        tag.setBoolean("interactEngine", interactEngine);
        tag.setByte("pushDir", (byte)pushDir.ordinal());
        tag.setBoolean("pushedLastTick", pushedLastTick);
        tag.setBoolean("lastActivityAt", lastActivityAt > 0);
        tag.setBoolean("cartArrivedAt", cartArrivedAt > 0);
        tag.setInteger("maxInactivity", maximumItemTransferInactivityTime);
        tag.setInteger("minimumCartWaitTime", minimumCartWaitTime);
        return super.writeToNBT(tag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag){
        super.readFromNBT(tag);
        hopperMode = HopperMode.values()[tag.getByte("hopperMode")];
        interactEngine = tag.getBoolean("interactEngine");
        pushDir = EnumFacing.VALUES[tag.getByte("pushDir")];
        pushedLastTick = tag.getBoolean("pushedLastTick");
        lastActivityAt = tag.getBoolean("lastActivityAt") ? System.currentTimeMillis() : 0;
        cartArrivedAt = tag.getBoolean("cartArrivedAt") ? System.currentTimeMillis() : 0; 
        maximumItemTransferInactivityTime = tag.getInteger("maxInactivity");
        minimumCartWaitTime = tag.getInteger("minimumCartWaitTime");
    }

    public void updateCartAbove(){
        boolean hasNetworkRailAbove = RailNetworkManager.getInstance(world.isRemote).getRail(getWorld(), getPos().up()) != null;
        if(hasNetworkRailAbove) {
            extract = true;
        } else {
            //Try to look up a rail using block states.
            IBlockState state = world.getBlockState(getPos().up());
            IRail r = RailManager.getInstance().getRail(world, getPos().up(), state);
            extract = r != null; //Extract when a rail is found
        }
    }

    @Override
    public void update(){
        if(!getWorld().isRemote) {
            if(firstTick) {
                firstTick = false;
                updateCartAbove();
            }

            if(managingCartId != null) {
                List<EntityMinecart> carts = getWorld().getEntities(EntityMinecart.class, input -> input.getPersistentID().equals(managingCartId));
                managingCart = carts.isEmpty() ? null : carts.get(0);
                managingCartId = null;
            }

            updateManagingCart(new AxisAlignedBB(extract ? getPos().up() : getPos().down()));

            boolean shouldPush;
            if(managingCart != null) {
                if(isDisabled()) {
                    shouldPush = true;
                } else {
                    shouldPush = tryTransfer(extract);
                }
            } else {
                shouldPush = false;
                lastActivityAt = 0;
            }
            if(shouldPush && !pushedLastTick) pushCart();
            boolean notifyNeighbors = shouldPush != pushedLastTick;
            pushedLastTick = shouldPush;
            if(notifyNeighbors) {
                getWorld().notifyNeighborsOfStateChange(getPos(), getBlockType(), true);
            }
            int comparatorInputOverride = getComparatorInputOverride();
            if(lastComparatorInputOverride != comparatorInputOverride) {
                world.updateComparatorOutputLevel(pos, ModBlocks.CART_HOPPER);
                lastComparatorInputOverride = comparatorInputOverride;
            }
        }
    }

    private boolean isDisabled(){
        for(EnumFacing facing : EnumFacing.HORIZONTALS) {
            if(getWorld().getRedstonePower(pos.offset(facing), facing) > 0) return true;
        }
        return false;
    }

    public boolean emitsRedstone(){
        return pushedLastTick;
    }

    @Override
    public HopperMode getHopperMode(){
        return hopperMode;
    }

    @Override
    public void setHopperMode(HopperMode hopperMode){
        Validate.notNull(hopperMode);
        this.hopperMode = hopperMode;
    }

    @Override
    public boolean isInteractingWithEngine(){
        return interactEngine;
    }

    @Override
    public void setInteractingWithEngine(boolean interactWithEngine){
        this.interactEngine = interactWithEngine;
    }

    private void pushCart(){
        managingCart.motionX += pushDir.getFrontOffsetX() * 0.1;
        managingCart.motionZ += pushDir.getFrontOffsetZ() * 0.1;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean tryTransfer(boolean extract){
        boolean active = false, empty = false, full = false;
        List<Pair<TileEntity, EnumFacing>> filters = Lists.newArrayList();
        for(EnumFacing dir : EnumFacing.HORIZONTALS) {
            TileEntity filter = getWorld().getTileEntity(getPos().offset(dir));
            if(filter != null) filters.add(new ImmutablePair<>(filter, dir));
        }

        for(ICartHopperBehaviour hopperBehaviour : getApplicableHopperBehaviours()) {
            Capability<?> cap = hopperBehaviour.getCapability();
            Object cart = null;
            if(interactEngine && hopperBehaviour instanceof CartHopperBehaviourItems) {
                if(managingCart.hasCapability(CapabilityMinecartDestination.INSTANCE, null)) {
                    cart = managingCart.getCapability(CapabilityMinecartDestination.INSTANCE, null).getEngineItemHandler();
                } else {
                    continue;
                }
            } else {
                cart = managingCart.getCapability(cap, null);
            }
            Object te = getCapabilityAt(cap, extract ? EnumFacing.DOWN : EnumFacing.UP);
            if(te != null && hopperBehaviour.tryTransfer(extract ? cart : te, extract ? te : cart, filters)) active = true;
            if(hopperMode == HopperMode.CART_EMPTY && hopperBehaviour.isCartEmpty(cart, filters)) empty = true;
            if(hopperMode == HopperMode.CART_FULL && hopperBehaviour.isCartFull(cart)) full = true;
        }
        return hopperMode == HopperMode.NO_ACTIVITY 
                ? !active && cartCanLeaveByTime() && cartCanLeaveByExternalItemTransfer() 
                : empty || full;
    }

    private List<ICartHopperBehaviour<?>> getApplicableHopperBehaviours(){
        Stream<ICartHopperBehaviour<?>> behaviours = RailManager.getInstance().getHopperBehaviours().stream();
        behaviours = behaviours.filter(hopperBehaviour -> interactEngine && hopperBehaviour instanceof CartHopperBehaviourItems || managingCart.hasCapability(hopperBehaviour.getCapability(), null));
        return behaviours.collect(Collectors.toList());
    }

    private void updateManagingCart(AxisAlignedBB aabb){
        if(managingCart != null) {
            if(managingCart.isDead || !managingCart.getEntityBoundingBox().intersects(aabb)) {
                managingCart = null;
                cartArrivedAt = 0;
            }
        }
        if(managingCart == null) {
            List<EntityMinecart> carts = getWorld().getEntitiesWithinAABB(EntityMinecart.class, aabb);
            if(!carts.isEmpty()) {
                managingCart = carts.get(0);
                pushDir = managingCart.getAdjustedHorizontalFacing();
                cartArrivedAt = System.currentTimeMillis();
            }
        }
    }

    private <T> T getCapabilityAt(Capability<T> cap, EnumFacing dir){
        BlockPos pos = getPos().offset(dir);
        TileEntity te = getWorld().getTileEntity(pos);
        return te != null && te.hasCapability(cap, dir.getOpposite()) ? te.getCapability(cap, dir.getOpposite()) : null;
    }

    @Override
    public void handleGUIButtonPress(EntityPlayer player, int... data){
        switch(data[0]){
            case 0:
                hopperMode = HopperMode.values()[(hopperMode.ordinal() + 1) % HopperMode.values().length];
                break;
            case 1:
                interactEngine = !interactEngine;
                break;
            default:
                return;
        }
        markDirty();
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing){
        if(capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return getCapability(capability, facing) != null;
        }
        return super.hasCapability(capability, facing);
    }

    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing){
      if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
          return (T)this;
      return super.getCapability(capability, facing);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public int getComparatorInputOverride(){
        if(managingCart != null) {
            if(interactEngine) {
                CapabilityMinecartDestination destCap = managingCart.getCapability(CapabilityMinecartDestination.INSTANCE, null);
                if(destCap != null && destCap.isMotorized()) {
                    return Container.calcRedstoneFromInventory(destCap.getFuelInv());
                } else {
                    return 0;
                }
            } else {
                int comparatorValue = 0;
                for(ICartHopperBehaviour hopperBehaviour : getApplicableHopperBehaviours()) {
                    Capability<?> cap = hopperBehaviour.getCapability();
                    Object capabilityValue = managingCart.getCapability(cap, null);
                    if(capabilityValue != null) {
                        int behaviourComparatorValue = hopperBehaviour.getComparatorInputOverride(capabilityValue);
                        comparatorValue = Math.max(comparatorValue, behaviourComparatorValue);
                    }
                }
                return comparatorValue;
            }
        }
        return 0;
    }
    
    private boolean cartCanLeaveByTime(){
        return cartArrivedAt == 0 || cartArrivedAt + minimumCartWaitTime < System.currentTimeMillis();
    }
    
    private boolean cartCanLeaveByExternalItemTransfer() {
        return lastActivityAt == 0 || lastActivityAt + maximumItemTransferInactivityTime < System.currentTimeMillis();
    }    

    private IItemHandler getCartItemHandler(){
        if (managingCart != null) {
            if (interactEngine) {
                CapabilityMinecartDestination destCap = managingCart
                        .getCapability(CapabilityMinecartDestination.INSTANCE, null);
                if (destCap != null)
                    return destCap.getEngineItemHandler();
            } else {
                return managingCart.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY,
                        extract ? EnumFacing.DOWN : EnumFacing.UP);
            }
        }
        return null;
    }

    @Override
    public int getSlots(){
        IItemHandler handler = getCartItemHandler();
        if (handler == null)
            return 0;
        return handler.getSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot){
        IItemHandler handler = getCartItemHandler();
        if (handler == null)
            return ItemStack.EMPTY;
        return handler.getStackInSlot(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate){
        IItemHandler handler = getCartItemHandler();
        if (handler == null)
            return stack;
        ItemStack leftOver = handler.insertItem(slot, stack, simulate);
        if (!simulate && !ItemStack.areItemStacksEqual(leftOver, stack))
            lastActivityAt = System.currentTimeMillis();
        return leftOver;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate){
        IItemHandler handler = getCartItemHandler();
        if (handler == null) 
            return ItemStack.EMPTY;
        ItemStack extracted =  handler.extractItem(slot, amount, simulate);
        if (!simulate && !extracted.isEmpty() && extracted.getCount() > 0)
            lastActivityAt = System.currentTimeMillis();
        return extracted;
    }

    @Override
    public int getSlotLimit(int slot){
        IItemHandler handler = getCartItemHandler();
        if (handler == null)
            return 0;
        return handler.getSlotLimit(slot);
    }
    
    public int getMinimumCartWaitTime(){
        return minimumCartWaitTime;
    }
    
    public void setMinimumCartWaitTime(int value){
        if (value < 0) value = 0;
        if (minimumCartWaitTime != value) {
            minimumCartWaitTime = value;
            markDirty();
        }
    }
    
    public int getMaximumItemTransferInactivityTime(){
        return maximumItemTransferInactivityTime;
    }
    
    public void setMaximumItemTransferInactivityTime(int value){
        if (value < 0) value = 0;
        if (maximumItemTransferInactivityTime != value) {
            maximumItemTransferInactivityTime = value;
            markDirty();
        }
    }

    @Override
    public void setText(int textFieldID, String text){
        if (textFieldID == TextFieldIds.MAX_ITEM_TRANSFER_INACTIVITY.getIndex())
            setMaximumItemTransferInactivityTime(Integer.parseInt(text));
        else if (textFieldID == TextFieldIds.MINIMUM_CART_WAIT_TIME.getIndex())
            setMinimumCartWaitTime(Integer.parseInt(text));
    }

    @Override
    public String getText(int textFieldID){
        if (textFieldID == TextFieldIds.MAX_ITEM_TRANSFER_INACTIVITY.getIndex())
            return Integer.toString(getMaximumItemTransferInactivityTime());
        else if (textFieldID == TextFieldIds.MINIMUM_CART_WAIT_TIME.getIndex())
            return Integer.toString(getMinimumCartWaitTime());
        return null;
    }
}
