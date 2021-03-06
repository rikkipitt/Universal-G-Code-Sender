/*
 * GRBL serial port interface class.
 */

/*
    Copywrite 2012-2013 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.willwinder.universalgcodesender;

import com.willwinder.universalgcodesender.types.GcodeCommand;
import java.io.*;
import java.util.LinkedList;

/**
 *
 * @author wwinder
 */
public class GrblCommunicator extends AbstractSerialCommunicator {
    // Command streaming variables
    private Boolean sendPaused = false;
    private LinkedList<String> commandBuffer;     // All commands in a file
    private LinkedList<String> activeStringList;  // Currently running commands
    private int sentBufferSize = 0;
    
    private Boolean singleStepModeEnabled = false;
    
    public GrblCommunicator() {
        this.setLineTerminator("\r\n");
    }
    
    /**
     * This constructor is for dependency injection so a mock serial device can
     * act as GRBL.
     */
    protected GrblCommunicator(final InputStream in, final OutputStream out,
            LinkedList<String> cb, LinkedList<String> asl) {
        // Base constructor.
        this();
        
        this.in = in;
        this.out = out;
        this.commandBuffer = cb;
        this.activeStringList = asl;
    }
    
    /**
     * API Implementation.
     */
    @Override
    protected void commPortOpenedEvent() {
        this.commandBuffer = new LinkedList<String>();
        this.activeStringList = new LinkedList<String>();
        this.sentBufferSize = 0;
    }
    
    @Override
    protected void commPortClosedEvent() {
        this.sendPaused = false;
        this.commandBuffer = null;
        this.activeStringList = null;
    }
    
    @Override
    public void setSingleStepMode(boolean enable) {
        this.singleStepModeEnabled = enable;
    }
    
    @Override
    public boolean getSingleStepMode() {
        return this.singleStepModeEnabled;
    }
    
    /**
     * Add command to the command queue outside file mode. This is the only way
     * to send a command to the comm port without being in file mode.
     */
    @Override
    public void queueStringForComm(final String input) {        
        String commandString = input;
        
        if (! commandString.endsWith("\n")) {
            commandString += "\n";
        }
        
        // Add command to queue
        this.commandBuffer.add(commandString);
    }
       
    /*
    // TODO: Figure out why this isn't working ...
    boolean isCommPortOpen() throws NoSuchPortException {
            CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(this.commPort.getName());
            String owner = portIdentifier.getCurrentOwner();
            String thisClass = this.getClass().getName();
            
            return portIdentifier.isCurrentlyOwned() && owner.equals(thisClass);                    
    }
    */
    
    /** File Stream Methods. **/
    
    @Override
    public boolean areActiveCommands() {
        return (this.activeStringList.size() > 0);
    }
    
    // Helper for determining if commands should be throttled.
    private boolean allowMoreCommands() {
        System.out.println("Single step mode = " + this.singleStepModeEnabled);
        if (this.singleStepModeEnabled) {
            if (this.areActiveCommands()) {
                System.out.println("Allow more commands = false");
                return false;
            }
        }
        System.out.println("Allow more commands = true");
        return true;
    }
    
    /**
     * Streams anything in the command buffer to the comm port.
     */
    @Override
    public void streamCommands() {
        if (this.commandBuffer.size() == 0) {
            // NO-OP
            return;
        }
        
        if (this.sendPaused) {
            // Another NO-OP
            return;
        }
        
        // Send command if:
        // There is room in the buffer.
        // AND We are NOT in single step mode.
        // OR  We are in single command mode and there are no active commands.
        while (CommUtils.checkRoomInBuffer(this.sentBufferSize, this.commandBuffer.peek())
                && allowMoreCommands()) {
            String commandString = this.commandBuffer.pop();
            this.activeStringList.add(commandString);
            this.sentBufferSize += commandString.length();
            
            // Newlines are embedded when they get queued so just send it.
            this.sendStringToComm(commandString);
            
            GcodeCommand command = new GcodeCommand(commandString);
            command.setSent(true);
            dispatchListenerEvents(COMMAND_SENT, this.commandSentListeners, command);
        }
        
        System.out.println("Number active commands: " + this.activeStringList.size());
    }
    
    @Override
    public void pauseSend() {
        this.sendPaused = true;
    }
    
    @Override
    public void resumeSend() {
        this.sendPaused = false;
        this.streamCommands();
    }
    
    @Override
    public void cancelSend() {
        this.commandBuffer.clear();
    }
    
    /**
     * This is to allow the GRBL Ctrl-C soft reset command.
     */
    @Override
    public void softReset() {
        this.commandBuffer.clear();
        this.activeStringList.clear();
        this.sentBufferSize = 0;
    }

    /** 
     * Processes message from GRBL.
     */
    @Override
    protected void responseMessage(String response) {
        // GrblCommunicator no longer knows what to do with responses.
        dispatchListenerEvents(RAW_RESPONSE, this.commRawResponseListener, response);

        // Keep the data flow going for now.
        if (GcodeCommand.isOkErrorResponse(response)) {
            // Pop the front of the active list.
            String commandString = this.activeStringList.pop();
            this.sentBufferSize -= commandString.length();
            
            GcodeCommand command = new GcodeCommand(commandString);
            command.setResponse(response);

            dispatchListenerEvents(COMMAND_COMPLETE, this.commandCompleteListeners, command);

            if (this.sendPaused == false) {
                this.streamCommands();
            }
        }
    }
}
