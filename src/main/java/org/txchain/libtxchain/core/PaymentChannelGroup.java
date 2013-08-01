/*
 * This file was originally written by Alex Akselrod.
 * LinkedIn connection requests welcome at
 * http://www.linkedin.com/in/alexakselrodrva
 * 
 * This is free and unencumbered software released into the public domain.
 * 
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 * 
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 * 
 * For more information, please refer to <http://unlicense.org/>
 */

package org.txchain.libtxchain.core;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;

import org.txchain.libtxchain.core.PaymentChannel.State;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionOutput;

public class PaymentChannelGroup {
	protected ECKey peerKey;
	protected Peer parentPeer;
	protected Peer peer;
	protected ECKey parentPeerKey;
	protected HashMap<Sha256Hash, PaymentChannel> channels;
	protected HashMap<byte[], Sha256Hash> channelsByPayment;
	protected boolean canPay;

	public PaymentChannelGroup(Peer parentPeer, Peer peer, ECKey peerKey) {
		this.parentPeer = parentPeer;
		this.peer = peer;
		this.peerKey = peerKey;
		this.parentPeerKey = parentPeer.getKey();
		this.channels = new HashMap<Sha256Hash, PaymentChannel>();
		this.channelsByPayment = new HashMap<byte[], Sha256Hash>();
		this.canPay = false;
	}

	void setupNewChannel(BigInteger minDeposit, BigInteger peerMinDeposit, BigInteger contribution,
			BigInteger peerContribution, BigInteger initialFee, BigInteger feeStep, int maxLifetime,
			int locktimeStep, int commitDepth, List<TransactionOutput> outputs) {
		PaymentChannel channel = new PaymentChannel(this);
		channel.initiate(minDeposit, peerMinDeposit, contribution,
				peerContribution, initialFee, feeStep, maxLifetime, locktimeStep, commitDepth, outputs);
		this.channels.put(channel.id(), channel);
	}
	
	void addChannel(Transaction setupTransaction, Transaction refundTransaction, BigInteger peerMinDeposit,
			BigInteger minDeposit, BigInteger peerContribution, BigInteger contribution, BigInteger initialFee,
			BigInteger feeStep, int maxLifetime, int locktimeStep, int commitDepth) {
		PaymentChannel channel = new PaymentChannel(this, setupTransaction, refundTransaction, peerMinDeposit,
				minDeposit,	peerContribution, contribution, initialFee, feeStep, maxLifetime, locktimeStep,
				commitDepth);
		this.channels.put(channel.id(), channel);
	}

	public void closeAllChannels() {
		for (Sha256Hash id:this.channels.keySet()) {
			this.channels.get(id).close();
		}
	}
	
	Transaction closeChannel(Sha256Hash id, Transaction closingTransaction) {
		this.channels.get(id).close(closingTransaction);
		return closingTransaction;
	}
	
	void checkChannelStates() {
		boolean canPay = false;
		for (PaymentChannel channel:this.channels.values()) {
			PaymentChannel.State state = channel.getState();
			if (state == PaymentChannel.State.ESTABLISHED)
				canPay = true;
			if (state == PaymentChannel.State.CLOSED)
				this.channels.remove(channel.id());
		}
		this.canPay = canPay;
	}
	
	boolean canPay() {
		return this.canPay;
	}
	
	ECKey getPeerKey() {
		return this.peerKey;
	}
	
	Peer getPeer() {
		return this.peer;
	}
	
	Peer getParentPeer() {
		return this.parentPeer;
	}
	
	ECKey getParentPeerKey() {
		return this.parentPeerKey;
	}

	public boolean hasOpenChannels() {
		for (PaymentChannel channel:this.channels.values()) {
			if (channel.state != State.CLOSED) return true;
		}
		return false;
	}

	public void pay(ECKey recipientKey, byte[] request, BigInteger amount) {
		for (PaymentChannel chan:this.channels.values()) {
			if (chan.canPay(amount)) {
				this.channelsByPayment.put(request, chan.id());
				chan.pay(recipientKey, request, amount);
				break;
			}
		}
	}

	public void acceptPaymentFor(byte[] request, Transaction pendingTransaction) {
		PaymentChannel chan = this.channels.get(pendingTransaction.getInput(0).getOutpoint().getHash());
		if (chan.state == State.ESTABLISHED) {
			this.channelsByPayment.put(request, chan.id());
			chan.acceptPaymentFor(request, pendingTransaction);
		}
	}
	
	public void commit(byte[] request, byte[] preImage) {
		Sha256Hash id = this.channelsByPayment.get(request);
		if (id != null) {
			this.channels.get(id).commit(request, preImage);
			this.peer.commit(request, preImage);
			this.channelsByPayment.remove(request);
		}
	}
}
