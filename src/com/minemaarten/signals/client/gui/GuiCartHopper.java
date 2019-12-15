package com.minemaarten.signals.client.gui;

import java.awt.Point;

import net.minecraft.client.resources.I18n;

import com.minemaarten.signals.api.access.ICartHopper.HopperMode;
import com.minemaarten.signals.client.gui.widget.GuiButtonSpecial;
import com.minemaarten.signals.client.gui.widget.IGuiWidget;
import com.minemaarten.signals.client.gui.widget.WidgetTextFieldNumber;
import com.minemaarten.signals.inventory.ContainerBase;
import com.minemaarten.signals.network.NetworkHandler;
import com.minemaarten.signals.network.PacketUpdateTextfield;
import com.minemaarten.signals.tileentity.TileEntityCartHopper;

public class GuiCartHopper extends GuiContainerBase<TileEntityCartHopper>{

    private GuiButtonSpecial modeButton, engineInteractButton;
    private WidgetTextFieldNumber minimumCartWaitTimeTextFieldNumber, maxItemTransferInactivityTextFieldNumber;

    public GuiCartHopper(TileEntityCartHopper te){
        super(new ContainerBase<>(te), te, null);
        xSize = 110;
        ySize = 118;
    }

    @Override
    public void initGui(){
        super.initGui();

        modeButton = new GuiButtonSpecial(0, guiLeft + 5, guiTop + 18, 100, 20, "<Mode>");
        modeButton.setTooltipText("<Mode>");

        engineInteractButton = new GuiButtonSpecial(1, guiLeft + 5, guiTop + 40, 100, 20, "<Interact with engine>");
        //  engineInteractButton.setTooltipText("<Interact with engine>");
        minimumCartWaitTimeTextFieldNumber = new WidgetTextFieldNumber(fontRenderer, guiLeft + 5, guiTop + 63, 100, 20);
        minimumCartWaitTimeTextFieldNumber.setTooltip(I18n.format("signals.gui.cart_hopper.minimum_cart_wait_time.tooltip"));
        minimumCartWaitTimeTextFieldNumber.setDecimals(0);
        minimumCartWaitTimeTextFieldNumber.minValue = 0;
        
        maxItemTransferInactivityTextFieldNumber = new WidgetTextFieldNumber(fontRenderer, guiLeft + 5, guiTop + 88, 100, 20);
        maxItemTransferInactivityTextFieldNumber.setTooltip(I18n.format("signals.gui.cart_hopper.maximum_item_transfer_inactivity.tooltip"));
        maxItemTransferInactivityTextFieldNumber.setDecimals(0);
        maxItemTransferInactivityTextFieldNumber.minValue = 0;
        
        addWidget(modeButton);
        addWidget(engineInteractButton);
        addWidget(minimumCartWaitTimeTextFieldNumber);
        addWidget(maxItemTransferInactivityTextFieldNumber);
    }
    
    @Override
    public void onKeyTyped(IGuiWidget widget){
        super.onKeyTyped(widget);
        if (minimumCartWaitTimeTextFieldNumber.isFocused()) {
            te.setMinimumCartWaitTime(minimumCartWaitTimeTextFieldNumber.getValue());   
            NetworkHandler.sendToServer(new PacketUpdateTextfield(te, 
                    TileEntityCartHopper.TextFieldIds.MINIMUM_CART_WAIT_TIME.getIndex()));
        } else if (maxItemTransferInactivityTextFieldNumber.isFocused()) {
            te.setMaximumItemTransferInactivityTime(maxItemTransferInactivityTextFieldNumber.getValue());
            NetworkHandler.sendToServer(new PacketUpdateTextfield(te, 
                    TileEntityCartHopper.TextFieldIds.MAX_ITEM_TRANSFER_INACTIVITY.getIndex()));
        }
    }
        
    @Override
    public void updateScreen(){
        super.updateScreen();

        HopperMode hopperMode = te.getHopperMode();
        modeButton.displayString = I18n.format("signals.gui.cart_hopper.emitRedstoneWhen." + hopperMode.toString().toLowerCase());
        modeButton.setTooltipText(I18n.format("signals.gui.cart_hopper.emitRedstoneWhen." + hopperMode.toString().toLowerCase() + ".tooltip"));

        boolean interactEngine = te.isInteractingWithEngine();
        engineInteractButton.displayString = I18n.format("signals.gui.cart_hopper.interactWith." + (interactEngine ? "cartEngine" : "cartInventory"));
        if (!minimumCartWaitTimeTextFieldNumber.isFocused()) {
            minimumCartWaitTimeTextFieldNumber.setValue(te.getMinimumCartWaitTime());    
        } 
        if (!maxItemTransferInactivityTextFieldNumber.isFocused()) {
            maxItemTransferInactivityTextFieldNumber.setValue(te.getMaximumItemTransferInactivityTime());
        }
        maxItemTransferInactivityTextFieldNumber.setEnabled(hopperMode == HopperMode.NO_ACTIVITY);
    }

    @Override
    protected boolean shouldDrawBackground(){
        return false;
    }

    @Override
    protected Point getInvTextOffset(){
        return null;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int i, int j){
        drawBackLayer();
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + 12, 0xFF222222);
        drawDarkGreyTextCentered(guiLeft + xSize / 2, guiTop, te.getBlockType().getLocalizedName());
        super.drawGuiContainerBackgroundLayer(partialTicks, i, j);
    }

}
