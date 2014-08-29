/****************************************************************************
 * Copyright (C) 2012-2014 ecsec GmbH.
 * All rights reserved.
 * Contact: ecsec GmbH (info@ecsec.de)
 *
 * This file is part of the Open eCard App.
 *
 * GNU General Public License Usage
 * This file may be used under the terms of the GNU General Public
 * License version 3.0 as published by the Free Software Foundation
 * and appearing in the file LICENSE.GPL included in the packaging of
 * this file. Please review the following information to ensure the
 * GNU General Public License version 3.0 requirements will be met:
 * http://www.gnu.org/copyleft/gpl.html.
 *
 * Other Usage
 * Alternatively, this file may be used in accordance with the terms
 * and conditions contained in a signed written agreement between
 * you and ecsec GmbH.
 *
 ***************************************************************************/

package org.openecard.sal.protocol.eac.gui;

import iso.std.iso_iec._24727.tech.schema.DIDAuthenticationDataType;
import iso.std.iso_iec._24727.tech.schema.EstablishChannel;
import iso.std.iso_iec._24727.tech.schema.EstablishChannelResponse;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;
import javax.xml.parsers.ParserConfigurationException;
import org.openecard.common.ECardConstants;
import org.openecard.common.I18n;
import org.openecard.common.WSHelper;
import org.openecard.common.WSHelper.WSException;
import org.openecard.common.anytype.AuthDataMap;
import org.openecard.common.anytype.AuthDataResponse;
import org.openecard.common.interfaces.Dispatcher;
import org.openecard.common.interfaces.DispatcherException;
import org.openecard.common.util.ByteUtils;
import org.openecard.gui.StepResult;
import org.openecard.gui.definition.PasswordField;
import org.openecard.gui.executor.ExecutionResults;
import org.openecard.gui.executor.StepAction;
import org.openecard.gui.executor.StepActionResult;
import org.openecard.gui.executor.StepActionResultStatus;
import org.openecard.sal.protocol.eac.EACData;
import org.openecard.sal.protocol.eac.anytype.PACEInputType;
import org.openecard.sal.protocol.eac.anytype.PasswordID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * StepAction for capturing the user PIN on the EAC GUI.
 *
 * @author Tobias Wich <tobias.wich@ecsec.de>
 * @author Hans-Martin Haase <hans-martin.haase@ecsec.de>
 */
public class PINStepAction extends StepAction {

    private static final Logger logger = LoggerFactory.getLogger(PINStepAction.class);
    
    private static final byte[] BLOCKED = new byte[] {(byte) 0x63, (byte) 0xC0};
    private static final byte[] DEAKTIVATED = new byte[] {(byte) 0x62, (byte) 0x83};
    private static final byte[] RC3 = new byte[] {(byte) 0x90, (byte) 0x00};
    private static final byte[] RC1 = new byte[] {(byte) 0x63, (byte) 0xC1};
    private static final byte[] RC2 = new byte[] {(byte) 0x63, (byte) 0xC2};

    private static final String PIN_ID_CAN = "2";

    private final EACData eacData;
    private final boolean capturePin;
    private final byte[] slotHandle;
    private final Dispatcher dispatcher;
    private final PINStep step;
    private final I18n lang = I18n.getTranslation("pace");

    private int retryCounter;

    public PINStepAction(EACData eacData, boolean capturePin, byte[] slotHandle, Dispatcher dispatcher, PINStep step,
	    byte[] status) {
	super(step);
	this.eacData = eacData;
	this.capturePin = capturePin;
	this.slotHandle = slotHandle;
	this.dispatcher = dispatcher;
	this.step = step;

	// check pin status
	if (Arrays.equals(status, BLOCKED)) {
	    retryCounter = 3;
	} else if (Arrays.equals(status, RC3)) {
	    retryCounter = 0;
	} else if (Arrays.equals(status, RC2)) {
	    retryCounter = 1;
	    step.updateAttemptsDisplay(2);
	} else if (Arrays.equals(status, RC1)) {
	    retryCounter = 2;
	    step.updateAttemptsDisplay(1);
	    step.addCANEntry();
	} else if (Arrays.equals(status, DEAKTIVATED)) {
	    retryCounter = -1;
	}
    }

    @Override
    public StepActionResult perform(Map<String, ExecutionResults> oldResults, StepResult result) {
	if (result.isBack()) {
	    return new StepActionResult(StepActionResultStatus.BACK);
	}
	
	if (retryCounter == 2) {
	    try {
		EstablishChannelResponse response = performPACEWithCAN(oldResults);
		if (response == null) {
		    logger.debug("The CAN does not meet the format requirements.");
		    return new StepActionResult(StepActionResultStatus.REPEAT);
		}

		WSHelper.checkResult(response);
	    } catch (DispatcherException | InvocationTargetException ex) {
		logger.error("Failed to dispatch the EstablishChannel request.", ex);
	    } catch (WSException ex) {
		logger.error("Failed to authenticate with the given CAN.", ex);
		return new StepActionResult(StepActionResultStatus.REPEAT);
	    }
	}

	if (retryCounter < 3) {
	    try {
		EstablishChannelResponse establishChannelResponse = performPACEWithPIN(oldResults);
		WSHelper.checkResult(establishChannelResponse);
		eacData.paceResponse = establishChannelResponse;
		// PACE completed successfully, proceed with next step
		return new StepActionResult(StepActionResultStatus.NEXT);
	    } catch (WSException ex) {
		if (capturePin) {
		    if (retryCounter < 3) {
			retryCounter++;
			step.updateAttemptsDisplay(3 - retryCounter);
			if (retryCounter == 2) {
			    step.addCANEntry();
			}

			if (retryCounter == 3) {
			    logger.warn("Wrong PIN entered. The PIN is blocked.");
			    return new StepActionResult(StepActionResultStatus.REPEAT, 
				    new ErrorStep(lang.translationForKey("step_error_title_blocked"),
					    lang.translationForKey("step_error_pin_blocked")));
			}
			logger.info("Wrong PIN entered, trying again (try number {}).", retryCounter);
			return new StepActionResult(StepActionResultStatus.REPEAT);
		    } else {
			logger.warn("Wrong PIN entered. The PIN is blocked.");
			return new StepActionResult(StepActionResultStatus.REPEAT, 
				new ErrorStep(lang.translationForKey("step_error_title_blocked"),
					lang.translationForKey("step_error_pin_blocked")));
		    }
		} else {
		    logger.warn("PIN not entered successfully in terminal.");
		    return new StepActionResult(StepActionResultStatus.CANCEL);
		}
	    } catch (DispatcherException | InvocationTargetException ex) {
		logger.error("Failed to dispatch EstablishChannelCommand.", ex);
		return new StepActionResult(StepActionResultStatus.CANCEL);
	    }
	} else {
	    logger.error("The PIN is block and can't be used for authentication.");
	    return new StepActionResult(StepActionResultStatus.NEXT, 
		    new ErrorStep(lang.translationForKey("step_error_title_blocked"),
			    lang.translationForKey("step_error_pin_blocked")));
	}
    }

    private EstablishChannelResponse performPACEWithPIN(Map<String, ExecutionResults> oldResults) 
	    throws DispatcherException, InvocationTargetException {
	DIDAuthenticationDataType protoData = eacData.didRequest.getAuthenticationProtocolData();
	AuthDataMap paceAuthMap;
	try {
	    paceAuthMap = new AuthDataMap(protoData);
	} catch (ParserConfigurationException ex) {
	    logger.error("Failed to read EAC Protocol data.", ex);
	    return null;
	}
	AuthDataResponse paceInputMap = paceAuthMap.createResponse(protoData);

	if (capturePin) {
	    ExecutionResults executionResults = oldResults.get(getStepID());
	    PasswordField p = (PasswordField) executionResults.getResult(PINStep.PIN_FIELD);
	    String pin = p.getValue();
	    // let the user enter the pin again, when there is none entered
	    // TODO: check pin length and possibly allowed charset with CardInfo file
	    if (pin.isEmpty()) {
		return null;
	    } else {
		paceInputMap.addElement(PACEInputType.PIN, pin);
	    }
	}

	// perform PACE
	paceInputMap.addElement(PACEInputType.PIN_ID, PasswordID.parse(eacData.pinID).getByteAsString());
	paceInputMap.addElement(PACEInputType.CHAT, eacData.selectedCHAT.toString());
	String certDesc = ByteUtils.toHexString(eacData.rawCertificateDescription);
	paceInputMap.addElement(PACEInputType.CERTIFICATE_DESCRIPTION, certDesc);
	EstablishChannel eChannel = createEstablishChannelStructure(paceInputMap);
	return (EstablishChannelResponse) dispatcher.deliver(eChannel);
    }

    private EstablishChannelResponse performPACEWithCAN(Map<String, ExecutionResults> oldResults)
	    throws DispatcherException, InvocationTargetException {
	DIDAuthenticationDataType paceInput = new DIDAuthenticationDataType();
	paceInput.setProtocol(ECardConstants.Protocol.PACE);
	AuthDataMap tmp;
	try {
	    tmp = new AuthDataMap(paceInput);
	} catch (ParserConfigurationException ex) {
	    logger.error("Failed to read empty Protocol data.", ex);
	    return null;
	}

	AuthDataResponse paceInputMap = tmp.createResponse(paceInput);
	if (capturePin) {
	    ExecutionResults executionResults = oldResults.get(getStepID());
	    PasswordField canField = (PasswordField) executionResults.getResult(PINStep.CAN_FIELD);
	    String canValue = canField.getValue();

	    if (canValue.length() != 6) {
		// let the user enter the can again, when input verification failed
		return null;
	    } else {
		paceInputMap.addElement(PACEInputType.PIN, canValue);
	    }
	}
	paceInputMap.addElement(PACEInputType.PIN_ID, PIN_ID_CAN);

	// perform PACE by EstablishChannelCommand
	EstablishChannel eChannel = createEstablishChannelStructure(paceInputMap);
	return (EstablishChannelResponse) dispatcher.deliver(eChannel);
    }

    private EstablishChannel createEstablishChannelStructure(AuthDataResponse paceInputMap) {
	// EstablishChannel
	EstablishChannel establishChannel = new EstablishChannel();
	establishChannel.setSlotHandle(slotHandle);
	establishChannel.setAuthenticationProtocolData(paceInputMap.getResponse());
	establishChannel.getAuthenticationProtocolData().setProtocol(ECardConstants.Protocol.PACE);
	return establishChannel;
    }

}
