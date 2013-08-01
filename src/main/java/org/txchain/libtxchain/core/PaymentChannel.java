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
import java.util.Arrays;
import java.util.List;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.core.TransactionConfidence;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.WalletTransaction;
import com.google.bitcoin.core.WalletTransaction.Pool;
import com.google.bitcoin.script.ScriptBuilder;
import com.google.bitcoin.script.ScriptOpCodes;

public class PaymentChannel {
	
	protected PaymentChannelGroup parentGroup;
	protected Transaction setupTransaction;
	protected Transaction lastSentTransaction;
	protected Transaction refundTransaction;
	protected Transaction pendingTransaction;
	protected Transaction redeemTransaction;
	protected byte[] pendingRequest;
	protected byte[] committedRequest;
	protected byte[] committedPreImage;
	State state;
	enum State {
		NEW,
		SETUP,
		ESTABLISHED,
		PENDING_COMMIT,
		EXPIRED_WAITING,
		CLOSING,
		CLOSED
	}
	int commitDepth;
	int locktimeStep;
	BigInteger peerMinDeposit;
	BigInteger minDeposit;
	BigInteger peerContribution;
	BigInteger contribution;
	BigInteger initialFee;
	BigInteger feeStep;
	boolean iAmInitiator;
	boolean iSentLast;

	PaymentChannel(PaymentChannelGroup parentGroup) {
		this.parentGroup = parentGroup;
		this.state = State.NEW;
	}
	
	public PaymentChannel(PaymentChannelGroup parentGroup, Transaction setupTransaction,
			Transaction refundTransaction, BigInteger peerMinDeposit, BigInteger minDeposit,
			BigInteger peerContribution, BigInteger contribution, BigInteger initialFee, BigInteger feeStep,
			int maxLifetime, int locktimeStep, int commitDepth) {
		this.parentGroup = parentGroup;
		this.setupTransaction = setupTransaction;
		this.refundTransaction = refundTransaction;
		this.lastSentTransaction = refundTransaction;
		this.pendingTransaction = null;
		this.commitDepth = commitDepth;
		this.minDeposit = minDeposit;
		this.peerMinDeposit = peerMinDeposit;
		this.contribution = contribution;
		this.peerContribution = peerContribution;
		this.initialFee = initialFee;
		this.feeStep = feeStep;
		this.locktimeStep = locktimeStep;
		this.iAmInitiator = false;
		getParentPeer().wallet().addWalletTransaction(
				new WalletTransaction(Pool.PENDING, setupTransaction));
		this.state = State.SETUP;
	}

	void initiate(BigInteger minDeposit, BigInteger peerMinDeposit,	BigInteger contribution,
			BigInteger peerContribution, BigInteger initialFee, BigInteger feeStep, int maxLifetime,
			int locktimeStep, int commitDepth, List<TransactionOutput> outputs) {
		try {
			this.iAmInitiator = true;
			this.commitDepth = commitDepth;
			this.minDeposit = minDeposit;
			this.peerMinDeposit = peerMinDeposit;
			this.contribution = contribution;
			this.peerContribution = peerContribution;
			this.initialFee = initialFee;
			this.feeStep = feeStep;
			this.locktimeStep = locktimeStep;
			this.setupTransaction = getPeer().reserveChannel(getParentPeerKey(),
					minDeposit, peerMinDeposit, contribution, peerContribution, initialFee, feeStep, maxLifetime,
					locktimeStep, commitDepth, outputs);
			this.state = State.SETUP;
			getParentPeer().signMyInputs(this.setupTransaction);
			this.refundTransaction = new Transaction(getParentPeer().params());
			this.refundTransaction.setLockTime(getParentPeer().chain().getBestChainHeight() + maxLifetime - 1);
			// First output in setup transaction is always contract
			this.refundTransaction.addInput(this.setupTransaction.getOutput(0));
			this.refundTransaction.getInput(0).setSequenceNumber(1);
			// First output in refund transaction is always initiator
			this.refundTransaction.addOutput(contribution.subtract(initialFee).subtract(initialFee),
				getParentPeerKey().toAddress(getParentPeer().params()));
			// Second output in refund transaction is always responder
			this.refundTransaction.addOutput(peerContribution, this.setupTransaction.getOutput(2).getScriptPubKey());
			this.refundTransaction.getInput(0).setScriptSig(
					new ScriptBuilder()
						.data(this.refundTransaction.calculateSignature(
								0,
								getParentPeerKey(), 
								this.refundTransaction.getInput(0).getConnectedOutput().getScriptPubKey(),
									SigHash.ALL, false).encodeToBitcoin())
						.build());
		} catch (Exception e) {
			// No exception handling for this demo
			e.printStackTrace();
		}
		this.refundTransaction = getPeer().createChannel(getParentPeer().getKey(), this.setupTransaction,
				this.refundTransaction, minDeposit, peerMinDeposit, contribution, peerContribution,	initialFee,
				feeStep, maxLifetime, locktimeStep, commitDepth);
		this.lastSentTransaction = this.refundTransaction;
		getParentPeer().wallet().addWalletTransaction(
				new WalletTransaction(WalletTransaction.Pool.PENDING, this.setupTransaction));
		getPeer().peerGroup().broadcastTransaction(this.setupTransaction);
	}
	
	TransactionConfidence getConfidence(Transaction tx) {
		Transaction walletTx = getParentPeer().wallet().getTransaction(tx.getHash());
		return ((walletTx == null) ? null :
			((!(walletTx.hasConfidence())) ? null : walletTx.getConfidence()));
	}
	
	State getState() {
		switch (this.state) {
		case NEW:
		case CLOSED:
		case PENDING_COMMIT:	// We need to account for a channel expiring while in PENDING_COMMIT state in production
			break;
		case SETUP:
			TransactionConfidence confidence = getConfidence(this.setupTransaction);
			if (confidence != null) {
				if ((confidence.getDepthInBlocks() >= this.commitDepth)) {
					this.state = State.ESTABLISHED;
				}
			}
			break;
		case ESTABLISHED:
			if (getParentPeer().chain().getBestChainHeight() > (this.lastSentTransaction.getLockTime())) {
				this.state = State.EXPIRED_WAITING;
			}
		case EXPIRED_WAITING:
			if (getParentPeer().chain().getBestChainHeight() > (this.lastSentTransaction.getLockTime())) {
				checkExpire();
			}
			if (getParentPeer().chain().getBestChainHeight() > (this.refundTransaction.getLockTime())) {
				expire();
			}
			break;
		case CLOSING:
			TransactionConfidence conf = getConfidence((this.redeemTransaction == null) ? this.refundTransaction
					: this.redeemTransaction);
			if (conf.getDepthInBlocks() >= this.commitDepth) {
				this.state = State.CLOSED;
			}
		}
		return this.state;
	}
	
	void checkExpire() {
		TransactionConfidence conf = getConfidence(this.refundTransaction);
		if ((conf == null) || (conf.getDepthInBlocks() == 0)) {  
			for (WalletTransaction wtx:getParentPeer().wallet().getWalletTransactions()) {
				if (wtx.getTransaction().getHash() == id()) {
					TransactionInput txin = wtx.getTransaction().getOutput(0).getSpentBy();
					if (txin != null) {
						this.refundTransaction = txin.getParentTransaction();
					}
					break;
				}
			}
		}
	}
	
	void expire() {
		getParentPeer().wallet().addWalletTransaction(
				new WalletTransaction(WalletTransaction.Pool.PENDING, this.refundTransaction));
		getParentPeer().peerGroup().broadcastTransaction(this.refundTransaction);
		this.redeemTransaction = new Transaction(getParentPeer().params());
		this.redeemTransaction.addOutput(
				this.refundTransaction.getOutput(this.iAmInitiator ? 0 : 1).getValue().subtract(this.initialFee),
				getParentPeerKey().toAddress(getParentPeer().params()));
		this.redeemTransaction.addInput(this.refundTransaction.getOutput(this.iAmInitiator ? 0 : 1));
		this.redeemTransaction.getInput(0).setScriptSig(
				new ScriptBuilder()
				.data(this.redeemTransaction.calculateSignature(0, getParentPeerKey(), null,
						this.refundTransaction.getOutput(this.iAmInitiator ? 0 : 1).getScriptBytes(), SigHash.ALL,
							false)
						.encodeToBitcoin())
				.data(this.committedPreImage)
				.build());
		getParentPeer().wallet().addWalletTransaction(
				new WalletTransaction(WalletTransaction.Pool.PENDING, this.redeemTransaction));
		getParentPeer().peerGroup().broadcastTransaction(this.redeemTransaction);
		this.state = State.CLOSING;
	}
	
	void close() {
		Transaction closingTransaction;
		// This and other similar code should really use a deep copy or serialize/deserialize
		if (this.refundTransaction.getLockTime() <= this.lastSentTransaction.getLockTime()) {
			closingTransaction = this.refundTransaction;
		} else {
			closingTransaction = this.lastSentTransaction;
		}
		closingTransaction.setLockTime(getParentPeer().chain().getBestChainHeight() - 1);
		closingTransaction.getInput(0).setSequenceNumber(TransactionInput.NO_SEQUENCE - 1);
		// It might be a good idea to consider removing the hash preimage check from the
		// criteria to close the channel, obviating the need for a redeem transaction
		try {
			closingTransaction.getInput(0).setScriptSig(new ScriptBuilder()
				.data(closingTransaction.calculateSignature(0, getParentPeerKey(),
						this.setupTransaction.getOutput(0).getScriptPubKey(),
						SigHash.ALL, false).encodeToBitcoin())
				.build());
		} catch (Exception e) {
			// No exception handling for this demo
			e.printStackTrace();
		}
		closingTransaction = getPeer().closeChannel(getParentPeerKey(), id(), closingTransaction);
		getParentPeer().wallet().addWalletTransaction(
				new WalletTransaction(Pool.PENDING, closingTransaction));
		getParentPeer().peerGroup().broadcastTransaction(closingTransaction);
		this.redeemTransaction = new Transaction(getParentPeer().params());
		this.redeemTransaction.addOutput(
				closingTransaction.getOutput(this.iAmInitiator ? 0 : 1).getValue().subtract(this.initialFee),
				getParentPeerKey().toAddress(getParentPeer().params()));
		this.redeemTransaction.addInput(closingTransaction.getOutput(this.iAmInitiator ? 0 : 1));
		this.redeemTransaction.getInput(0).setScriptSig(
				new ScriptBuilder()
				.data(this.redeemTransaction.calculateSignature(0, getParentPeerKey(), null,
						closingTransaction.getOutput(this.iAmInitiator ? 0 : 1).getScriptBytes(), SigHash.ALL, false)
						.encodeToBitcoin())
				.data(this.committedPreImage)
				.build());
		getParentPeer().wallet().addWalletTransaction(
				new WalletTransaction(WalletTransaction.Pool.PENDING, this.redeemTransaction));
		getParentPeer().peerGroup().broadcastTransaction(this.redeemTransaction);
		this.state = State.CLOSING;
	}
	
	Transaction close(Transaction closingTransaction) {
		try {
			byte[] mySignature = closingTransaction.calculateSignature(0, getParentPeerKey(),
					this.setupTransaction.getOutput(0).getScriptPubKey(),
					SigHash.ALL, false).encodeToBitcoin();
			byte[] peerSignature = closingTransaction.getInput(0).getScriptSig().getChunks().get(0).data;
			closingTransaction.getInput(0).setScriptSig(
					ScriptBuilder.createMultiSigInputScriptBytes(Arrays.asList(
							iAmInitiator ? mySignature : peerSignature,
							iAmInitiator ? peerSignature : mySignature)));
		} catch (Exception e) {
			// No exception handling for this demo
			e.printStackTrace();
		}
		getParentPeer().wallet().addWalletTransaction(
				new WalletTransaction(Pool.PENDING, closingTransaction));
		getParentPeer().peerGroup().broadcastTransaction(closingTransaction);
		this.redeemTransaction = new Transaction(getParentPeer().params());
		this.redeemTransaction.addOutput(
				closingTransaction.getOutput(this.iAmInitiator ? 0 : 1).getValue().subtract(this.initialFee),
				getParentPeerKey().toAddress(getParentPeer().params()));
		this.redeemTransaction.addInput(closingTransaction.getOutput(this.iAmInitiator ? 0 : 1));
		this.redeemTransaction.getInput(0).setScriptSig(
				new ScriptBuilder()
				.data(this.redeemTransaction.calculateSignature(0, getParentPeerKey(), null,
						closingTransaction.getOutput(this.iAmInitiator ? 0 : 1).getScriptBytes(), SigHash.ALL, false)
						.encodeToBitcoin())
				.data(this.committedPreImage)
				.build());
		getParentPeer().wallet().addWalletTransaction(
				new WalletTransaction(WalletTransaction.Pool.PENDING, this.redeemTransaction));
		getParentPeer().peerGroup().broadcastTransaction(this.redeemTransaction);
		this.state = State.CLOSING;
		return closingTransaction;
	}
	
	Sha256Hash id() {
		return this.setupTransaction.getHash();
	}
	
	Peer getParentPeer() {
		return this.parentGroup.getParentPeer();
	}
	
	ECKey getParentPeerKey() {
		return this.parentGroup.getParentPeerKey();
	}
	
	Peer getPeer() {
		return this.parentGroup.getPeer();
	}
	
	ECKey getPeerKey() {
		return this.parentGroup.getPeerKey();
	}

	public boolean canPay(BigInteger amount) {
		return ((this.state == State.ESTABLISHED) &&
				(this.pendingTransaction == null) &&
				(((this.refundTransaction.getLockTime() <= this.lastSentTransaction.getLockTime()) ?
					this.refundTransaction : this.lastSentTransaction)
					.getOutput(this.iAmInitiator ? 0 : 1)	// Initiator always has first output in refund/send TX
					.getValue().compareTo(this.minDeposit.add(this.feeStep).add(amount)) > 0));
	}

	public void pay(ECKey recipientKey, byte[] request, BigInteger amount) {
		try {
			Transaction lastTransaction = (this.iSentLast ? this.lastSentTransaction : this.refundTransaction);
			this.pendingTransaction = new Transaction(getParentPeer().params());
			this.pendingTransaction.setLockTime((this.iSentLast ?
					lastTransaction.getLockTime() : lastTransaction.getLockTime()) - this.locktimeStep);
			this.pendingTransaction.addInput(this.setupTransaction.getOutput(0));
			this.pendingTransaction.getInput(0).setSequenceNumber(this.iSentLast ?
					lastTransaction.getInput(0).getSequenceNumber() :
					lastTransaction.getInput(0).getSequenceNumber() + 1);
			this.pendingTransaction.addOutput(
					lastTransaction.getOutput(0).getValue()
					.add(this.iAmInitiator ? amount.negate() : amount)
					.subtract((this.iAmInitiator && !iSentLast) ? BigInteger.ZERO : this.feeStep),
					new ScriptBuilder()
					.op(ScriptOpCodes.OP_HASH160)
					.data(request)
					.op(ScriptOpCodes.OP_EQUALVERIFY)
					.data((this.iAmInitiator ? getParentPeerKey() : getPeerKey()).getPubKey())
					.op(ScriptOpCodes.OP_CHECKSIG)
					.build());
			this.pendingTransaction.addOutput(
					lastTransaction.getOutput(1).getValue()
					.add(this.iAmInitiator ? amount : amount.negate())
					.subtract((!this.iAmInitiator && !iSentLast) ? this.feeStep : BigInteger.ZERO),
					new ScriptBuilder()
					.op(ScriptOpCodes.OP_HASH160)
					.data(request)
					.op(ScriptOpCodes.OP_EQUALVERIFY)
					.data((this.iAmInitiator ? getPeerKey() : getParentPeerKey()).getPubKey())
					.op(ScriptOpCodes.OP_CHECKSIG)
					.build());
			this.pendingTransaction.getInput(0).setScriptSig(
					new ScriptBuilder()
					.data(this.pendingTransaction.calculateSignature(0, getParentPeerKey(),
							this.setupTransaction.getOutput(0).getScriptPubKey(), SigHash.ALL, false)
							.encodeToBitcoin())
					.build());
			this.state = State.PENDING_COMMIT;
			this.pendingRequest = request;
			this.iSentLast = true;
			getPeer().payFor(getParentPeerKey(), getPeerKey(), recipientKey, request, amount, pendingTransaction);
		} catch (Exception e) {
			// No exception handling for this demo
			e.printStackTrace();
		}
	}

	public void acceptPaymentFor(byte[] request, Transaction pendingTransaction) {
		try {
			byte[] mySignature = pendingTransaction.calculateSignature(0, getParentPeerKey(),
					pendingTransaction.getInput(0).getConnectedOutput().getScriptPubKey(),
					SigHash.ALL, false).encodeToBitcoin();
			byte[] peerSignature = pendingTransaction.getInput(0).getScriptSig().getChunks().get(0).data;
			// Since initiator's pubkey is always passed at top of stack, it has to be pushed second
			pendingTransaction.getInput(0).setScriptSig(
					ScriptBuilder.createMultiSigInputScriptBytes(Arrays.asList(
							iAmInitiator ? mySignature : peerSignature,
							iAmInitiator ? peerSignature : mySignature)));
			this.pendingTransaction = pendingTransaction;
			this.state = State.PENDING_COMMIT;
			this.pendingRequest = request;
			this.iSentLast = false;
		} catch (Exception e) {
			// No exception handling for this demo
			e.printStackTrace();
		}
	}
	
	public void commit(byte[] request, byte[] preImage) {
		if (Arrays.equals(request, this.pendingRequest)) {
			if (this.iSentLast) {
				this.lastSentTransaction = this.pendingTransaction;
			} else {
				this.refundTransaction = this.pendingTransaction;
			}
			this.pendingRequest = null;
			this.committedPreImage = preImage;
			this.committedRequest = request;
			this.pendingTransaction = null;
			this.state = State.ESTABLISHED;
		}
	}
}
