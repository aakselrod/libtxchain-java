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

import java.io.File;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

import com.google.bitcoin.core.AbstractBlockChainListener;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.StoredBlock;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.Wallet.SendRequest;
import com.google.bitcoin.crypto.TransactionSignature;
import com.google.bitcoin.kits.WalletAppKit;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.script.ScriptBuilder;

public class Peer extends WalletAppKit {

	private static final SecureRandom secureRandom = new SecureRandom();

	protected HashMap<byte[], PaymentChannelGroup> connections;
	protected HashMap<byte[], byte[]> paymentHashes;
	protected HashMap<byte[], ECKey> inboundConnectionsByPayment;
	protected HashMap<byte[], ECKey> outboundConnectionsByPayment;

	public Peer(NetworkParameters params, File directory, String filePrefix) {
		super(params, directory, filePrefix);
		super.setAutoSave(true);
		this.connections = new HashMap<byte[], PaymentChannelGroup>();
		this.paymentHashes = new HashMap<byte[], byte[]>();
		this.inboundConnectionsByPayment = new HashMap<byte[], ECKey>();
		this.outboundConnectionsByPayment = new HashMap<byte[], ECKey>();
	}

	String getFilePrefix() {
		return this.filePrefix;
	}

	public ECKey connectTo(Peer peer) {
		ECKey peerKey = peer.hello(this, getKey());
		if (!this.connections.containsKey(peerKey.getPubKey())) {
			PaymentChannelGroup grp = new PaymentChannelGroup(this, peer,
					peerKey);
			this.connections.put(peerKey.getPubKey(), grp);
		}
		return peerKey;
	}

	public ECKey hello(Peer peer, ECKey peerKey) {
		if (!this.connections.containsKey(peerKey.getPubKey())) {
			PaymentChannelGroup grp = new PaymentChannelGroup(this, peer,
					peerKey);
			this.connections.put(peerKey.getPubKey(), grp);
		}
		return getKey();
	}

	public void setupChannel(ECKey peerKey, BigInteger minDeposit,
			BigInteger peerMinDeposit, BigInteger contribution,
			BigInteger peerContribution, BigInteger initialFee,
			BigInteger feeStep, int maxLifetime, int locktimeStep,
			int commitDepth) {
		this.connections.get(peerKey.getPubKey()).setupNewChannel(minDeposit,
				peerMinDeposit, contribution, peerContribution, initialFee,
				feeStep, maxLifetime, locktimeStep, commitDepth,
				chooseOutputs(contribution));
	}

	public byte[] getNewCommitHash() {
		byte[] preImage = new byte[20];
		byte[] commitHash;
		secureRandom.nextBytes(preImage);
		commitHash = Utils.sha256hash160(preImage);
		this.paymentHashes.put(commitHash, preImage);
		return commitHash;
	}

	public byte[] getPreImage(byte[] commitHash) {
		return this.paymentHashes.get(commitHash);
	}

	private List<TransactionOutput> chooseOutputs(BigInteger value) {
		SendRequest tempRequest = SendRequest.to(getKey().toAddress(params),
				value);
		this.vWallet.completeTx(tempRequest);
		List<TransactionOutput> outputs = new ArrayList<TransactionOutput>();
		for (TransactionInput in : tempRequest.tx.getInputs()) {
			outputs.add(in.getConnectedOutput());
		}
		return outputs;
	}

	void signMyInputs(Transaction tx) {
		// This is to sign only MY OWN inputs in a transaction
		try {
			for (int i = 0; i < tx.getInputs().size(); i++) {
				TransactionInput in = tx.getInput(i);
				TransactionOutput cOut = in.getConnectedOutput();
				Script pubKey = cOut.getScriptPubKey();
				if (cOut.isMine(this.vWallet)) {
					TransactionSignature sig = tx.calculateSignature(i,
							getKey(), pubKey, SigHash.ALL, false);
					if (pubKey.isSentToAddress()
							&& (pubKey.getToAddress(params).equals(getKey()
									.toAddress(params)))) {
						in.setScriptSig(new ScriptBuilder()
								.data(sig.encodeToBitcoin())
								.data(getKey().getPubKey()).build());
					} else if (pubKey.isSentToRawPubKey()
							&& (pubKey.getPubKey().equals(getKey().getPubKey()))) {
						in.setScriptSig(new ScriptBuilder().data(
								sig.encodeToBitcoin()).build());
					}
				}
			}
		} catch (Exception e) {
			// No exception handling for this demo
			e.printStackTrace();
		}
	}

	public ECKey getKey() {
		return this.vWallet.getKeys().get(0);
	}

	Transaction reserveChannel(ECKey peerKey, BigInteger peerMinDeposit,
			BigInteger minDeposit, BigInteger peerContribution,
			BigInteger contribution, BigInteger initialFee, BigInteger feeStep,
			int maxLifetime, int locktimeStep, int commitDepth,
			List<TransactionOutput> peerOutputs) {
		List<TransactionOutput> outputs = chooseOutputs(contribution);
		BigInteger peerOutputTotal = BigInteger.ZERO;
		BigInteger outputTotal = BigInteger.ZERO;
		Transaction proposedTransaction = new Transaction(params);
		for (TransactionOutput out : outputs) {
			proposedTransaction.addInput(out);
			outputTotal = outputTotal.add(out.getValue());
		}
		for (TransactionOutput out : peerOutputs) {
			proposedTransaction.addInput(out);
			peerOutputTotal = peerOutputTotal.add(out.getValue());
		}
		proposedTransaction.setLockTime(vChain.getBestChainHeight() - 1);
		for (TransactionInput in : proposedTransaction.getInputs()) {
			in.setSequenceNumber(1);
		}
		// First output in setup transaction is always contract (2-of-2
		// multisig) with initiator's key first
		proposedTransaction.addOutput(contribution.add(peerContribution)
				.subtract(initialFee), ScriptBuilder
				.createMultiSigOutputScript(2,
						Arrays.asList(peerKey, getKey())));
		// Second output in setup transaction is always to the initiator
		proposedTransaction.addOutput(
				peerOutputTotal.subtract(peerContribution),
				peerKey.toAddress(params));
		// Third output in setup transaction is always to the responder
		proposedTransaction.addOutput(outputTotal.subtract(contribution),
				getKey().toAddress(params));
		// Order of transactions can be mixed for privacy in the future; this
		// would prevent the transactions from
		// being used for reputation and routing
		signMyInputs(proposedTransaction);
		return proposedTransaction;
	}

	Transaction createChannel(ECKey peerKey, Transaction setupTransaction,
			Transaction refundTransaction, BigInteger peerMinDeposit,
			BigInteger minDeposit, BigInteger peerContribution,
			BigInteger contribution, BigInteger initialFee, BigInteger feeStep,
			int maxLifetime, int locktimeStep, int commitDepth) {
		try {
			refundTransaction.getInput(0).setScriptSig(
					ScriptBuilder.createMultiSigInputScriptBytes(Arrays.asList(
							refundTransaction.getInput(0).getScriptSig().getChunks().get(0).data,
							refundTransaction.calculateSignature(
									0,
									getKey(),
									refundTransaction.getInput(0)
											.getConnectedOutput()
											.getScriptPubKey(), SigHash.ALL,
									false).encodeToBitcoin())));
			this.connections.get(peerKey.getPubKey()).addChannel(
					setupTransaction, refundTransaction, peerMinDeposit,
					minDeposit, peerContribution, contribution, initialFee,
					feeStep, maxLifetime, locktimeStep, commitDepth);
		} catch (Exception e) {
			// No exception handling for this demo
			e.printStackTrace();
		}
		return refundTransaction;
	}

	public void closeAllChannelsWith(ECKey peerKey) {
		this.connections.get(peerKey.getPubKey()).closeAllChannels();
	}

	public Transaction closeChannel(ECKey peerKey, Sha256Hash id,
			Transaction closingTransaction) {
		PaymentChannelGroup grp = this.connections.get(peerKey.getPubKey());
		closingTransaction = grp.closeChannel(id, closingTransaction);
		return closingTransaction;
	}

	public boolean canPay(ECKey peerKey) {
		return this.connections.get(peerKey.getPubKey()).canPay();
	}

	public boolean hasOpenChannels() {
		for (PaymentChannelGroup conn : this.connections.values()) {
			if (conn.hasOpenChannels())
				return true;
		}
		return false;
	}

	@Override
	protected void startUp() throws Exception {
		super.startUp();
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					Peer.this.stopAndWait();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});
		if (this.vWallet.getKeychainSize() == 0)
			this.vWallet.addKey(new ECKey());
		this.vChain.addListener(new AbstractBlockChainListener() {
			@Override
			public void notifyNewBestBlock(StoredBlock block) {
				new Thread() {
					@Override
					public void run() {
						for (PaymentChannelGroup peerGroup : connections
								.values()) {
							peerGroup.checkChannelStates();
						}
					}
				}.start();
			}
		});
	}

	@Override
	protected void shutDown() throws Exception {
		super.shutDown();
	}

	public void pay(ECKey intermediaryKey, ECKey recipientKey, byte[] request,
			BigInteger amount) {
		if (!this.outboundConnectionsByPayment.containsKey(request)) {
			this.outboundConnectionsByPayment.put(request, intermediaryKey);
			this.connections.get(intermediaryKey.getPubKey()).pay(recipientKey, request, amount);
		} // Should have an "else" to throw a transaction indicating a cycle in the payment routing
	}

	public void payFor(ECKey peerKey, ECKey intermediaryKey,
			ECKey recipientKey, byte[] request, BigInteger amount,
			Transaction pendingTransaction) {
		// We should check for correct input (signatures, hash consistency, and amounts)
		// here and everywhere else, but that's a to-do for production-ready code
		if (!this.inboundConnectionsByPayment.containsKey(request)) {
			this.inboundConnectionsByPayment.put(request, peerKey);
			this.connections.get(peerKey.getPubKey()).acceptPaymentFor(request,
					pendingTransaction);
			if (Arrays.equals(intermediaryKey.getPubKey(), getKey().getPubKey())) {
				// Are we the target intermediary? If not, we don't want to do
				// anything - no routing support yet - we just wait for a rollback, at least until
				// there's error handling and/or routing functionality
				if (!Arrays.equals(recipientKey.getPubKey(), getKey().getPubKey())) {
					// Don't pass on payments for which we're the payee. For more
					// privacy, this should take into account that different PaymentChannel
					// instances can be using different keys
					this.pay(recipientKey, recipientKey, request, amount);
				}
			}
			// Default case if we aren't the target intermediary - we assume we're
			// the payee
			// Now that we've gotten to this point, we commit transaction if we can,
			// otherwise we wait for commit message
			if (this.paymentHashes.containsKey(request)) {
				commit(request, getPreImage(request));
			}
		}
	}

	public void commit(byte[] request, byte[] preImage) {
		if (!this.paymentHashes.containsKey(request)) {
			this.paymentHashes.put(request, preImage);
		}
		ECKey inboundKey = this.inboundConnectionsByPayment.get(request);
		if (inboundKey != null) {
			this.inboundConnectionsByPayment.remove(request);
			this.connections.get(inboundKey.getPubKey()).commit(request, preImage);
		}
		ECKey outboundKey = this.outboundConnectionsByPayment.get(request);
		if (outboundKey != null) {
			this.outboundConnectionsByPayment.remove(request);
			this.connections.get(outboundKey.getPubKey()).commit(request, preImage);
		}
	}
}