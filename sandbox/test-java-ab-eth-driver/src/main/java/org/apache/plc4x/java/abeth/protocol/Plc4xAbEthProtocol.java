/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package org.apache.plc4x.java.abeth.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.plc4x.java.abeth.model.AbEthField;
import org.apache.plc4x.java.abeth.readwrite.*;
import org.apache.plc4x.java.api.exceptions.PlcProtocolException;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcRequest;
import org.apache.plc4x.java.api.messages.PlcResponse;
import org.apache.plc4x.java.api.model.PlcField;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.base.PlcMessageToMessageCodec;
import org.apache.plc4x.java.base.events.ConnectEvent;
import org.apache.plc4x.java.base.events.ConnectedEvent;
import org.apache.plc4x.java.base.messages.DefaultPlcReadResponse;
import org.apache.plc4x.java.base.messages.InternalPlcReadRequest;
import org.apache.plc4x.java.base.messages.PlcRequestContainer;
import org.apache.plc4x.java.base.messages.items.BaseDefaultFieldItem;
import org.apache.plc4x.java.base.messages.items.DefaultBooleanFieldItem;
import org.apache.plc4x.java.base.messages.items.DefaultShortFieldItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Plc4xAbEthProtocol extends PlcMessageToMessageCodec<CIPEncapsulationPacket, PlcRequestContainer> {

    private static final Logger logger = LoggerFactory.getLogger(Plc4xAbEthProtocol.class);

    private static final AtomicInteger transactionCounterGenerator = new AtomicInteger(10);
    private static final short[] emptySenderContext = new short[] {(short) 0x00 ,(short) 0x00 ,(short) 0x00,
        (short) 0x00,(short) 0x00,(short) 0x00, (short) 0x00,(short) 0x00};

    private long sessionHandle;
    private Map<Integer, PlcRequestContainer> requests;

    public Plc4xAbEthProtocol() {
        this.requests = new HashMap<>();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // If the connection has just been established, start setting up the connection
        // by sending a connection request to the plc.
        if (evt instanceof ConnectEvent) {
            logger.debug("AB-ETH Sending Connection Request");
            // Open the session on ISO Transport Protocol first.
            CIPEncapsulationConnectionRequest connectionRequest = new CIPEncapsulationConnectionRequest(0L, 0L,
                emptySenderContext, 0L);
            ctx.channel().writeAndFlush(connectionRequest);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, PlcRequestContainer msg, List<Object> out) throws Exception {
        PlcRequest request = msg.getRequest();

        // reset counter since two byte values are possible in DF1
        if (transactionCounterGenerator.get() > 65000) {
            transactionCounterGenerator.set(10);
        }

        if (request instanceof PlcReadRequest) {
            PlcReadRequest readRequest = (PlcReadRequest) msg.getRequest();

            for (String fieldName : readRequest.getFieldNames()) {
                PlcField field = readRequest.getField(fieldName);
                if (!(field instanceof AbEthField)) {
                    throw new PlcProtocolException("The field should have been of type AbEthField");
                }
                AbEthField abEthField = (AbEthField) field;

                DF1RequestProtectedTypedLogicalRead logicalRead = new DF1RequestProtectedTypedLogicalRead(
                    abEthField.getByteSize(), abEthField.getFileNumber(), abEthField.getFileType().getTypeCode(),
                    abEthField.getElementNumber(), (short) 0); // Subelementnumber default to zero
                // TODO: make target and origin address changeable
                DF1RequestMessage requestMessage = new DF1CommandRequestMessage(
                    (short) 8, (short) 5, (short) 0, transactionCounterGenerator.incrementAndGet(), logicalRead);
                CIPEncapsulationReadRequest read = new CIPEncapsulationReadRequest(
                    sessionHandle, 0, emptySenderContext, 0, requestMessage);

                requests.put(requestMessage.getTransactionCounter(), msg);

                out.add(read);
            }
        } else {
            ctx.fireExceptionCaught(
                new PlcProtocolException("Unsupported request type " + request.getClass().getName()));
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, CIPEncapsulationPacket packet, List<Object> out) throws Exception {
        if(packet instanceof CIPEncapsulationConnectionResponse) {
            CIPEncapsulationConnectionResponse connectionResponse = (CIPEncapsulationConnectionResponse) packet;
            // Save the session handle
            sessionHandle = connectionResponse.getSessionHandle();

            // Tell Netty we're finished connecting
            ctx.channel().pipeline().fireUserEventTriggered(new ConnectedEvent());
        } else {
            // We're currently just expecting responses.
            if (!(packet instanceof CIPEncapsulationReadResponse)) {
                return;
            }
            CIPEncapsulationReadResponse cipResponse = (CIPEncapsulationReadResponse) packet;
            int transactionCounter = cipResponse.getResponse().getTransactionCounter();
            if(!requests.containsKey(transactionCounter)) {
                ctx.fireExceptionCaught(
                    new PlcProtocolException(
                        "Couldn't find request for response with transaction counter " + transactionCounter));
                return;
            }

            PlcRequestContainer requestContainer = requests.remove(transactionCounter);
            PlcRequest request = requestContainer.getRequest();
            PlcResponse response = null;
            if (request instanceof PlcReadRequest) {
                response = decodeReadResponse(cipResponse, requestContainer);
            } else {
                ctx.fireExceptionCaught(
                    new PlcProtocolException("Unsupported request type " + request.getClass().getName()));
            }

            // Confirm the response being handled.
            if (response != null) {
                requestContainer.getResponseFuture().complete(response);
            }
        }
    }

    private PlcResponse decodeReadResponse(
        CIPEncapsulationReadResponse plcReadResponse, PlcRequestContainer requestContainer) {

        InternalPlcReadRequest plcReadRequest = (InternalPlcReadRequest) requestContainer.getRequest();

        Map<String, Pair<PlcResponseCode, BaseDefaultFieldItem>> values = new HashMap<>();
        for (String fieldName : plcReadRequest.getFieldNames()) {
            AbEthField field = (AbEthField) plcReadRequest.getField(fieldName);
            PlcResponseCode responseCode = decodeResponseCode(plcReadResponse.getResponse().getStatus());

            BaseDefaultFieldItem fieldItem = null;
            if (responseCode == PlcResponseCode.OK) {
                try {
                    switch (field.getFileType()) {
                        case INTEGER: // output as single bytes
                            if(plcReadResponse.getResponse() instanceof DF1CommandResponseMessageProtectedTypedLogicalRead) {
                                DF1CommandResponseMessageProtectedTypedLogicalRead df1PTLR = (DF1CommandResponseMessageProtectedTypedLogicalRead) plcReadResponse.getResponse();
                                short[] data = df1PTLR.getData();
                                Short[] convData = new Short[data.length];
                                for(int i = 0; i < data.length; i++) {
                                    convData[i] = data[i];
                                }
                                fieldItem = new DefaultShortFieldItem(convData);
                            }
                            break;
                        case SINGLEBIT:
                            if(plcReadResponse.getResponse() instanceof DF1CommandResponseMessageProtectedTypedLogicalRead) {
                                DF1CommandResponseMessageProtectedTypedLogicalRead df1PTLR = (DF1CommandResponseMessageProtectedTypedLogicalRead) plcReadResponse.getResponse();
                                short[] data = df1PTLR.getData();
                                if (field.getBitNumber() < 8) {
                                    fieldItem = new DefaultBooleanFieldItem((data[0] & (1 <<  field.getBitNumber())) != 0);          // read from first byte
                                } else {
                                    fieldItem = new DefaultBooleanFieldItem((data[1] & (1 << (field.getBitNumber() - 8) )) != 0);   // read from second byte
                                }
                            }
                            break;
                        default:
                            logger.warn("Problem during decoding of field {}: Decoding of file type not implemented; " +
                                "FieldInformation: {}", fieldName, field);
                    }
                }
                catch (Exception e) {
                    logger.warn("Some other error occurred casting field {}, FieldInformation: {}",fieldName, field,e);
                }
            }
            Pair<PlcResponseCode, BaseDefaultFieldItem> result = new ImmutablePair<>(responseCode, fieldItem);
            values.put(fieldName, result);
        }

        return new DefaultPlcReadResponse(plcReadRequest, values);
    }

    private PlcResponseCode decodeResponseCode(short status) {
        if(status == 0) {
            return PlcResponseCode.OK;
        }
        return PlcResponseCode.NOT_FOUND;
    }

    private BaseDefaultFieldItem decodeReadResponseUnsignedByteField(AbEthField field, ByteBuf data) {
        Short[] shorts = null;//readAllValues(Short.class, field, i -> data.readUnsignedByte());
        return new DefaultShortFieldItem(shorts);
    }

}
