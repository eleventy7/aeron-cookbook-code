/*
 * Copyright 2019-2020 Shaun Laurens.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aeroncookbook.cluster.rfq.statemachine;

import com.aeroncookbook.cluster.rfq.domain.gen.RfqFlyweight;
import com.aeroncookbook.cluster.rfq.domain.gen.RfqResponseFlyweight;
import com.aeroncookbook.cluster.rfq.domain.gen.RfqResponseSequence;
import com.aeroncookbook.cluster.rfq.domain.gen.RfqResponsesRepository;
import com.aeroncookbook.cluster.rfq.domain.gen.RfqSequence;
import com.aeroncookbook.cluster.rfq.domain.gen.RfqsRepository;
import com.aeroncookbook.cluster.rfq.gen.AcceptRfqCommand;
import com.aeroncookbook.cluster.rfq.gen.CancelRfqCommand;
import com.aeroncookbook.cluster.rfq.gen.CounterRfqCommand;
import com.aeroncookbook.cluster.rfq.gen.CreateRfqCommand;
import com.aeroncookbook.cluster.rfq.gen.QuoteRequestEvent;
import com.aeroncookbook.cluster.rfq.gen.QuoteRfqCommand;
import com.aeroncookbook.cluster.rfq.gen.RejectRfqCommand;
import com.aeroncookbook.cluster.rfq.gen.RfqAcceptedEvent;
import com.aeroncookbook.cluster.rfq.gen.RfqCanceledEvent;
import com.aeroncookbook.cluster.rfq.gen.RfqCreatedEvent;
import com.aeroncookbook.cluster.rfq.gen.RfqErrorEvent;
import com.aeroncookbook.cluster.rfq.gen.RfqQuotedEvent;
import com.aeroncookbook.cluster.rfq.instrument.gen.Instrument;
import com.aeroncookbook.cluster.rfq.instruments.Instruments;
import com.aeroncookbook.cluster.rfq.statemachine.actors.Requester;
import com.aeroncookbook.cluster.rfq.statemachine.actors.Responder;
import com.aeroncookbook.cluster.rfq.statemachine.actors.RfqActor;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqAccepted;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqCanceled;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqCompleted;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqCountered;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqCreated;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqExpired;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqQuoted;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqRejected;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqState;
import com.aeroncookbook.cluster.rfq.statemachine.states.RfqStates;
import com.aeroncookbook.cluster.rfq.util.Snapshotable;
import io.aeron.ExclusivePublication;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.Int2ObjectHashMap;

import java.util.List;

public class Rfqs extends Snapshotable
{
    private static final String UNKNOWN_RFQ = "Unknown RFQ";
    private static final String UNKNOWN_CUSIP = "Unknown CUSIP";
    private static final String SYSTEM_AT_CAPACITY = "System at capacity";
    private static final String MIN_SIZE = "RFQ is for smaller quantity than instrument min size";
    private static final String CANNOT_CANCEL_RFQ_NO_RELATION_TO_USER = "Cannot cancel RFQ, no relation to user";
    private static final String CANNOT_REJECT_RFQ_NO_RELATION_TO_USER = "Cannot reject RFQ, no relation to user";
    private static final String ILLEGAL_TRANSITION = "Illegal transition";
    private static final String CANNOT_ACCEPT_RFQ_NO_RELATION_TO_USER = "Cannot accept RFQ, no relation to user";
    private static final String CANNOT_COUNTER_RFQ_NO_RELATION_TO_USER = "Cannot counter RFQ, no relation to user";
    private static final String CANNOT_QUOTE_RFQ_OTHER_USER_ALEADY_RESPONDED = "Cannot quote RFQ, RFQ already taken";

    private final Instruments instruments;
    private final ClusterProxy clusterProxy;
    private final RfqsRepository rfqsRepository;
    private final RfqResponsesRepository rfqResponsesRepository;
    private final RfqSequence rfqSequence;
    private final RfqResponseSequence rfqResponseSequence;
    private final Int2ObjectHashMap<RfqState> stateMachineStates;
    private final DirectBuffer bufferInvite;
    private final DirectBuffer bufferError;
    private final DirectBuffer bufferCanceledRfqEvent;
    private final DirectBuffer bufferCreatedRfqEvent;
    private final DirectBuffer bufferQuotedRfqEvent;
    private final DirectBuffer bufferAcceptedRfqEvent;
    private final QuoteRequestEvent quoteRequestEvent;
    private final RfqErrorEvent rfqErrorEvent;
    private final RfqCanceledEvent rfqCanceledEvent;
    private final RfqCreatedEvent rfqCreatedEvent;
    private final RfqQuotedEvent rfqQuotedEvent;
    private final RfqAcceptedEvent rfqAcceptedEvent;

    public Rfqs(Instruments instruments, ClusterProxy clusterProxy, int capacity)
    {
        this.instruments = instruments;
        this.clusterProxy = clusterProxy;

        this.rfqsRepository = RfqsRepository.createWithCapacity(capacity);
        this.rfqResponsesRepository = RfqResponsesRepository.createWithCapacity(capacity * 10);
        this.rfqSequence = RfqSequence.INSTANCE();
        this.rfqResponseSequence = RfqResponseSequence.INSTANCE();

        this.stateMachineStates = new Int2ObjectHashMap<>();
        buildStates(stateMachineStates);

        quoteRequestEvent = new QuoteRequestEvent();
        bufferInvite = new ExpandableArrayBuffer(QuoteRequestEvent.BUFFER_LENGTH);
        quoteRequestEvent.setBufferWriteHeader(bufferInvite, 0);

        rfqErrorEvent = new RfqErrorEvent();
        bufferError = new ExpandableArrayBuffer(RfqErrorEvent.BUFFER_LENGTH);
        rfqErrorEvent.setBufferWriteHeader(bufferError, 0);

        rfqCanceledEvent = new RfqCanceledEvent();
        bufferCanceledRfqEvent = new ExpandableArrayBuffer(RfqCanceledEvent.BUFFER_LENGTH);
        rfqCanceledEvent.setBufferWriteHeader(bufferCanceledRfqEvent, 0);

        rfqCreatedEvent = new RfqCreatedEvent();
        bufferCreatedRfqEvent = new ExpandableArrayBuffer(RfqCreatedEvent.BUFFER_LENGTH);
        rfqCreatedEvent.setBufferWriteHeader(bufferCreatedRfqEvent, 0);

        rfqQuotedEvent = new RfqQuotedEvent();
        bufferQuotedRfqEvent = new ExpandableArrayBuffer(RfqQuotedEvent.BUFFER_LENGTH);
        rfqQuotedEvent.setBufferWriteHeader(bufferQuotedRfqEvent, 0);

        rfqAcceptedEvent = new RfqAcceptedEvent();
        bufferAcceptedRfqEvent = new ExpandableArrayBuffer(RfqAcceptedEvent.BUFFER_LENGTH);
        rfqAcceptedEvent.setBufferWriteHeader(bufferQuotedRfqEvent, 0);
    }

    public void createRfq(CreateRfqCommand createRfqCommand, long timestamp, long clusterSession)
    {
        final int nextSequence = rfqSequence.nextRfqIdSequence();
        final RfqFlyweight rfq = rfqsRepository.appendWithKey(nextSequence);

        if (rfq == null)
        {
            replyError(-1, SYSTEM_AT_CAPACITY, createRfqCommand.readClOrdId());
            return;
        }

        Instrument forCusip = instruments.getForCusip(createRfqCommand.readCusip());

        if (forCusip == null)
        {
            replyError(-1, UNKNOWN_CUSIP, createRfqCommand.readClOrdId());
            return;
        }

        if (createRfqCommand.readQuantity() < forCusip.readMinSize())
        {
            replyError(-1, MIN_SIZE, createRfqCommand.readClOrdId());
            return;
        }

        //store in the repository
        rfq.writeCreationTime(timestamp);
        rfq.writeRequesterClOrdId(createRfqCommand.readClOrdId());
        rfq.writeExpiryTime(createRfqCommand.readExpireTimeMs());
        rfq.writeQuantity(createRfqCommand.readQuantity());
        rfq.writeRequester(0);
        rfq.writeState(RfqStates.CREATED.getStateId());
        rfq.writeSide(createRfqCommand.readSide());
        rfq.writeRequester(createRfqCommand.readUserId());
        rfq.writeSecurityId(forCusip.readSecurityId());
        rfq.writeClusterSession(clusterSession);

        //this approach uses specific messages for invite + rfq created. Contrast this approach to quoteRfq which
        //relies on the gateways to perform more logic.

        //inform rfq creator that they have a new RFQ
        rfqCreatedEvent.writeClOrdId(createRfqCommand.readClOrdId());
        rfqCreatedEvent.writeRfqId(nextSequence);
        clusterProxy.reply(bufferCreatedRfqEvent, 0, RfqCreatedEvent.BUFFER_LENGTH);

        //invite everyone else
        broadcastInviteToEveryoneExcept(rfq, createRfqCommand.readUserId());
    }

    public void cancelRfq(CancelRfqCommand cancelRfqCommand, long timestamp)
    {
        RfqFlyweight rfqToCancel = rfqsRepository.getByKey(cancelRfqCommand.readRfqId());
        if (rfqToCancel == null)
        {
            replyError(cancelRfqCommand.readRfqId(), UNKNOWN_RFQ, "");
            return;
        }

        //only requester can cancel
        if (rfqToCancel.readRequester() != cancelRfqCommand.readUserId())
        {
            replyError(cancelRfqCommand.readRfqId(), CANNOT_CANCEL_RFQ_NO_RELATION_TO_USER, "");
            return;
        }

        final RfqActor actor = getActorForUserThisRfq(rfqToCancel, cancelRfqCommand.readUserId());
        if (actor == null)
        {
            replyError(cancelRfqCommand.readRfqId(), CANNOT_CANCEL_RFQ_NO_RELATION_TO_USER, "");
            return;
        }

        if (actor.canCancel() && rfqCanTransitionToState(rfqToCancel, RfqStates.CANCELED))
        {
            //update repository
            rfqToCancel.writeState(transitionTo(rfqToCancel, RfqStates.CANCELED));
            rfqToCancel.writeLastUpdate(timestamp);
            rfqToCancel.writeLastUpdateUser(cancelRfqCommand.readUserId());

            //inform user who canceled RFQ it is now canceled
            rfqCanceledEvent.writeClOrdId(rfqToCancel.readRequesterClOrdId());
            rfqCanceledEvent.writeRfqId(cancelRfqCommand.readRfqId());
            clusterProxy.reply(bufferCanceledRfqEvent, 0, RfqCanceledEvent.BUFFER_LENGTH);
        }
    }

    public void rejectRfq(RejectRfqCommand rejectRfqCommand, long timestamp)
    {
        RfqFlyweight rfqToReject = rfqsRepository.getByKey(rejectRfqCommand.readRfqId());

        if (rfqToReject == null)
        {
            replyError(rejectRfqCommand.readRfqId(), UNKNOWN_RFQ, "");
            return;
        }

        final RfqActor actor = getActorForUserThisRfq(rfqToReject, rejectRfqCommand.readUserId());

        if (actor == null)
        {
            replyError(rejectRfqCommand.readRfqId(), CANNOT_REJECT_RFQ_NO_RELATION_TO_USER, "");
            return;
        }

        if (rfqCanTransitionToState(rfqToReject, RfqStates.REJECTED))
        {
            rfqToReject.writeState(transitionTo(rfqToReject, RfqStates.REJECTED));
            rfqToReject.writeLastUpdate(timestamp);
            rfqToReject.writeLastUpdateUser(rejectRfqCommand.readUserId());

        } else
        {
            replyError(rejectRfqCommand.readRfqId(), ILLEGAL_TRANSITION, "");
            return;
        }
    }

    public void acceptRfq(AcceptRfqCommand acceptRfqCommand, long timestamp, long clusterSession)
    {
        RfqFlyweight rfqToAccept = rfqsRepository.getByKey(acceptRfqCommand.readRfqId());

        if (rfqToAccept == null)
        {
            replyError(acceptRfqCommand.readRfqId(), UNKNOWN_RFQ, "");
            return;
        }

        if (!rfqCanTransitionToState(rfqToAccept, RfqStates.ACCEPTED))
        {
            replyError(acceptRfqCommand.readRfqId(), ILLEGAL_TRANSITION, "");
            return;
        }

        final RfqActor actor = getActorForUserThisRfq(rfqToAccept, acceptRfqCommand.readUserId());
        if (actor == null)
        {
            replyError(acceptRfqCommand.readRfqId(), CANNOT_ACCEPT_RFQ_NO_RELATION_TO_USER, "");
            return;
        }

        //prevent accept from your own quote
        if (rfqToAccept.readLastUpdateUser() == acceptRfqCommand.readUserId())
        {
            replyError(acceptRfqCommand.readRfqId(), ILLEGAL_TRANSITION, "");
            return;
        }

        if (!validAccept(acceptRfqCommand))
        {
            replyError(acceptRfqCommand.readRfqId(), CANNOT_ACCEPT_RFQ_NO_RELATION_TO_USER, "");
            return;
        }

        if (actor.canAccept())
        {
            //append a response
            final int responseId = rfqResponseSequence.nextRfqResponseIdSequence();
            RfqResponseFlyweight rfqResponseFlyweight = rfqResponsesRepository.appendWithKey(responseId);

            if (rfqResponseFlyweight == null)
            {
                replyError(acceptRfqCommand.readRfqId(), SYSTEM_AT_CAPACITY, "");
                return;
            }

            rfqResponseFlyweight.writeCreationTime(timestamp);
            rfqResponseFlyweight.writeRfqId(acceptRfqCommand.readRfqId());
            rfqResponseFlyweight.writeUser(acceptRfqCommand.readUserId());
            rfqResponseFlyweight.writeClusterSession(clusterSession);
            if (actor.isRequester())
            {
                rfqResponseFlyweight.writeResponseType(RfqResponseType.REQUESTOR_ACCEPTED.getResponseTypeId());
            } else
            {
                rfqResponseFlyweight.writeResponseType(RfqResponseType.RESPONDER_ACCEPTED.getResponseTypeId());
            }

            //update the RFQ to Accepted
            rfqToAccept.writeState(transitionTo(rfqToAccept, RfqStates.ACCEPTED));
            rfqToAccept.writeLastUpdateUser(acceptRfqCommand.readUserId());
            rfqToAccept.writeLastUpdate(timestamp);

            rfqAcceptedEvent.writeRequesterClOrdId(rfqToAccept.readRequesterClOrdId());
            rfqAcceptedEvent.writeAcceptedByUserId(acceptRfqCommand.readUserId());
            rfqAcceptedEvent.writeRequesterUserId(rfqToAccept.readRequester());
            rfqAcceptedEvent.writeResponderUserId(rfqToAccept.readResponder());
            rfqAcceptedEvent.writePrice(rfqToAccept.readLastPrice());
            clusterProxy.broadcast(bufferQuotedRfqEvent, 0, RfqQuotedEvent.BUFFER_LENGTH);
        }

    }

    private boolean validAccept(AcceptRfqCommand acceptRfqCommand)
    {
        //creates garbage.
        //ordered list of responses; newest is last
        List<Integer> responses = rfqResponsesRepository.getAllWithIndexRfqIdValue(acceptRfqCommand.readRfqId());

        RfqResponseFlyweight lastResponse =
            rfqResponsesRepository.getByBufferOffset(responses.get(responses.size() - 1));

        //no quotes
        if (lastResponse == null)
        {
            return false;
        }

        //can only respond to latest quote
        if (lastResponse.readId() != acceptRfqCommand.readRfqQuoteId())
        {
            return false;
        }

        //has to be different user, and the last thing done must have been a quote or a counter
        return lastResponse.readUser() != acceptRfqCommand.readUserId()
            && (lastResponse.readResponseType() == RfqResponseType.QUOTE_FROM_RESPONDER.getResponseTypeId()
            || lastResponse.readResponseType() == RfqResponseType.REQUESTOR_COUNTERED.getResponseTypeId()
            || lastResponse.readResponseType() == RfqResponseType.RESPONDER_COUNTERED.getResponseTypeId());
    }

    public void counterRfq(CounterRfqCommand counterRfqCommand, long timestamp)
    {
        RfqFlyweight rfqToCounter = rfqsRepository.getByKey(counterRfqCommand.readRfqId());

        if (rfqToCounter == null)
        {
            replyError(counterRfqCommand.readRfqId(), UNKNOWN_RFQ, "");
            return;
        }

        final RfqActor actor = getActorForUserThisRfq(rfqToCounter, counterRfqCommand.readUserId());

        if (actor == null)
        {
            replyError(counterRfqCommand.readRfqId(), CANNOT_COUNTER_RFQ_NO_RELATION_TO_USER, "");
            return;
        }

    }

    public void quoteRfq(QuoteRfqCommand quoteRfqCommand, long timestamp, long clusterSession)
    {
        RfqFlyweight rfqToQuote = rfqsRepository.getByKey(quoteRfqCommand.readRfqId());
        if (rfqToQuote == null)
        {
            replyError(quoteRfqCommand.readRfqId(), UNKNOWN_RFQ, "");
            return;
        }

        final RfqActor actor = getActorForUserThisRfq(rfqToQuote, quoteRfqCommand.readResponderId());
        if (actor == null)
        {
            //when the first quote comes in, the RFQ is stamped to their user. Subsequent users have no relation to
            //the rfq.
            replyError(quoteRfqCommand.readRfqId(), CANNOT_QUOTE_RFQ_OTHER_USER_ALEADY_RESPONDED, "");
            return;
        }

        if (actor.canQuote() && rfqCanTransitionToState(rfqToQuote, RfqStates.QUOTED))
        {
            //append a response
            final int responseId = rfqResponseSequence.nextRfqResponseIdSequence();
            RfqResponseFlyweight rfqResponseFlyweight = rfqResponsesRepository.appendWithKey(responseId);

            if (rfqResponseFlyweight == null)
            {
                replyError(quoteRfqCommand.readRfqId(), SYSTEM_AT_CAPACITY, "");
                return;
            }

            rfqResponseFlyweight.writeCreationTime(timestamp);
            rfqResponseFlyweight.writeRfqId(quoteRfqCommand.readRfqId());
            rfqResponseFlyweight.writeUser(quoteRfqCommand.readResponderId());
            rfqResponseFlyweight.writePrice(quoteRfqCommand.readPrice());
            rfqResponseFlyweight.writeClusterSession(clusterSession);
            rfqResponseFlyweight.writeResponseType(RfqResponseType.QUOTE_FROM_RESPONDER.getResponseTypeId());

            //update the RFQ to Quoted
            rfqToQuote.writeState(transitionTo(rfqToQuote, RfqStates.QUOTED));
            rfqToQuote.writeLastUpdateUser(quoteRfqCommand.readResponderId());
            rfqToQuote.writeLastUpdate(timestamp);
            rfqToQuote.writeLastPrice(quoteRfqCommand.readPrice());
            rfqToQuote.writeResponder(quoteRfqCommand.readResponderId());

            //broadcast response, for gateways to route only to the specific users given
            //contrast this to rfq created, which is a one-by-one approach
            //typically use one style in a state machine; difference shown to illustrate how it's done
            rfqQuotedEvent.writePrice(quoteRfqCommand.readPrice());
            rfqQuotedEvent.writeRequesterId(rfqToQuote.readRequester());
            rfqQuotedEvent.writeResponderId(quoteRfqCommand.readResponderId());
            rfqQuotedEvent.writeRfqQuoteId(responseId);
            rfqQuotedEvent.writeRfqId(rfqToQuote.readId());
            clusterProxy.broadcast(bufferQuotedRfqEvent, 0, RfqQuotedEvent.BUFFER_LENGTH);
        } else
        {
            replyError(quoteRfqCommand.readRfqId(), "RFQ not accepting quotes at this time", "");
        }
    }

    public void cancelOnClusterSessionDisconnect(long timestamp, long session)
    {
        rfqsRepository.allItems().forEachRemaining(rfqFlyweight -> cancelRfqAfterDisconnect(rfqFlyweight,
            session, timestamp));
    }

    private void cancelRfqAfterDisconnect(RfqFlyweight rfqFlyweight, long session, long timestamp)
    {
        if ((rfqFlyweight.readClusterSession() == session)
            && shouldBeCancelledOnDisconnect(rfqFlyweight))
        {
            rfqFlyweight.writeState(transitionTo(rfqFlyweight, RfqStates.CANCELED));
            rfqFlyweight.writeLastUpdate(timestamp);
            rfqFlyweight.writeLastUpdateUser(Integer.MAX_VALUE);
        }
    }

    private boolean shouldBeCancelledOnDisconnect(RfqFlyweight rfqFlyweight)
    {
        return rfqCanTransitionToState(rfqFlyweight, RfqStates.CANCELED);
    }

    private void replyError(int rfqId, String message, String clOrdId)
    {
        rfqErrorEvent.writeRfqId(rfqId);
        rfqErrorEvent.writeError(message);
        rfqErrorEvent.writeClOrdId(clOrdId);
        clusterProxy.reply(bufferError, 0, RfqErrorEvent.BUFFER_LENGTH);
    }

    private void broadcastInviteToEveryoneExcept(RfqFlyweight rfq, int requesterUserId)
    {
        quoteRequestEvent.writeRfqId(rfq.readId());
        quoteRequestEvent.writeBroadcastExcludeUserId(requesterUserId);
        quoteRequestEvent.writeExpireTimeMs(rfq.readExpiryTime());
        quoteRequestEvent.writeQuantity(rfq.readQuantity());
        quoteRequestEvent.writeSide(invertSide(rfq));
        quoteRequestEvent.writeSecurityId(rfq.readSecurityId());

        clusterProxy.broadcast(bufferInvite, 0, QuoteRequestEvent.BUFFER_LENGTH);
    }

    private RfqActor getActorForUserThisRfq(RfqFlyweight rfq, int userId)
    {
        final RfqActor actor;
        if (rfq.readRequester() == userId)
        {
            actor = Requester.INSTANCE;
        } else if (rfq.readResponder() == userId || rfq.readResponder() == 0)
        {
            actor = Responder.INSTANCE;
        } else
        {
            actor = null;
        }

        return actor;
    }

    private short transitionTo(RfqFlyweight rfqToTransition, RfqStates destinationState)
    {
        return stateMachineStates.get(rfqToTransition.readState()).transitionTo(destinationState).getCurrentStateId();
    }

    public void snapshotTo(ExclusivePublication snapshotPublication)
    {
        //
    }

    @Override
    public void loadFromSnapshot(DirectBuffer buffer, int offset)
    {
        //
    }

    private boolean rfqCanTransitionToState(RfqFlyweight rfqToTransition, RfqStates destinationState)
    {
        return stateMachineStates.get(rfqToTransition.readState()).canTransitionTo(destinationState);
    }

    private void buildStates(Int2ObjectHashMap<RfqState> stateMachineStates)
    {
        stateMachineStates.put(RfqStates.CREATED.getStateId(), RfqCreated.INSTANCE);
        stateMachineStates.put(RfqStates.QUOTED.getStateId(), RfqQuoted.INSTANCE);
        stateMachineStates.put(RfqStates.COUNTERED.getStateId(), RfqCountered.INSTANCE);
        stateMachineStates.put(RfqStates.ACCEPTED.getStateId(), RfqAccepted.INSTANCE);
        stateMachineStates.put(RfqStates.REJECTED.getStateId(), RfqRejected.INSTANCE);
        stateMachineStates.put(RfqStates.EXPIRED.getStateId(), RfqExpired.INSTANCE);
        stateMachineStates.put(RfqStates.CANCELED.getStateId(), RfqCanceled.INSTANCE);
        stateMachineStates.put(RfqStates.COMPLETED.getStateId(), RfqCompleted.INSTANCE);
    }

    private String invertSide(RfqFlyweight rfq)
    {
        if ("B".equalsIgnoreCase(rfq.readSide()))
        {
            return "S";
        }
        return "B";
    }

    public RfqFlyweight getRfq(int id)
    {
        return rfqsRepository.getByKey(id);
    }
}