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

package org.txchain.libtxchain;

import java.io.File;
import java.math.BigInteger;
import java.net.InetAddress;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PeerAddress;
import com.google.bitcoin.params.RegTestParams;

import org.txchain.libtxchain.core.Peer;

public class RunTest 
{
    public static void main( String[] args ) throws Exception
    {
    	NetworkParameters params = RegTestParams.get();

    	Peer ep1 = new Peer(params, new File("target/ep1"), "ep1");
        ep1.setPeerNodes(new PeerAddress(InetAddress.getLocalHost(), 18444));
    	Peer ep2 = new Peer(params, new File("target/ep2"), "ep2");
        ep2.setPeerNodes(new PeerAddress(InetAddress.getLocalHost(), 18444));
    	Peer im = new Peer(params, new File("target/im"), "im");
        im.setPeerNodes(new PeerAddress(InetAddress.getLocalHost(), 18444));

    	ep1.start();
    	ep2.start();
    	im.start();

    	do {
    		Thread.sleep(1000);
    	} while ((ep1.isRunning() == false) || (ep2.isRunning() == false) || (im.isRunning() == false));
    	
    	if (ep1.wallet().getBalance().compareTo(BigInteger.valueOf(5000000)) < 0) {
    		System.out.println("Please feed ep1 at " + ep1.getKey().toAddress(params).toString());
    	}
    	
    	if (ep2.wallet().getBalance().compareTo(BigInteger.valueOf(5000000)) < 0) {
    		System.out.println("Please feed ep2 at " + ep2.getKey().toAddress(params).toString());
    	}

    	if (im.wallet().getBalance().compareTo(BigInteger.valueOf(10000000)) < 0) {
    		System.out.println("Please feed im at " + im.getKey().toAddress(params).toString());
    	}
    	
    	System.out.println("After you feed these addresses, please generate a block.");
    	
    	while (
    			(ep1.wallet().getBalance().compareTo(BigInteger.valueOf(5000000)) < 0) ||
    			(ep2.wallet().getBalance().compareTo(BigInteger.valueOf(5000000)) < 0) ||
    			(im.wallet().getBalance().compareTo(BigInteger.valueOf(10000000)) < 0)
    			) {
    		Thread.sleep(1000);
    	}
    	
    	ECKey imKey1 = ep1.connectTo(im);
    	ECKey imKey2 = ep2.connectTo(im);	// imKey2 is the same as imKey1, but play along...

    	ep1.setupChannel(imKey1, BigInteger.valueOf(1000000), BigInteger.valueOf(1000000),
    			BigInteger.valueOf(5000000), BigInteger.valueOf(5000000), BigInteger.valueOf(10000),
    			BigInteger.valueOf(1), 14, 2, 1);

    	System.out.println("Thanks for feeding us! To set up a channel between ep1 and im, please generate another block.");

    	while (!ep1.canPay(imKey1)) {
    		Thread.sleep(1000);
    	}
    	
    	ep2.setupChannel(imKey2, BigInteger.valueOf(500000), BigInteger.valueOf(1000000),
    			BigInteger.valueOf(5000000), BigInteger.valueOf(5000000), BigInteger.valueOf(10000),
    			BigInteger.valueOf(1), 24, 2, 1);

    	System.out.println("Channel between ep1 and im is set up!  To set up a channel between ep2 and im, please generate another block.");

    	while (!ep2.canPay(imKey2)) {
    		Thread.sleep(1000);
    	}

    	System.out.println("Channel between ep2 and im is set up!");

    	// Payee ep2 specifies commit hash and commits immediately
    	byte[] request = ep2.getNewCommitHash();
    	// .0025 BTC - about 25 cents when 1 BTC ~ $100 USD
    	ep1.pay(imKey1, ep2.getKey(), request, BigInteger.valueOf(300000));
    	
    	System.out.println("ep1 paid ep2 through im, and ep2 auto-committed the payment!");

    	// Payer ep1 specifies commit hash as in escrow, commits
		// after goods/services are delivered
    	request = ep1.getNewCommitHash();
    	// .0010 BTC - about 10 cents when 1 BTC ~ $100 USD
    	ep1.pay(imKey1, ep2.getKey(), request, BigInteger.valueOf(100000));
    	
    	System.out.println("ep1 paid ep2 through im!");
    	
    	ep1.commit(request, ep1.getPreImage(request));
    	
    	System.out.println("ep1 committed the payment!");
    	
    	// Switch payer and payee - ep2 is now paying ep1
    	request = ep1.getNewCommitHash();
    	// .0015 BTC - about 15 cents when 1 BTC ~ $100 USD
    	ep2.pay(imKey2, ep1.getKey(), request, BigInteger.valueOf(200000));
    	System.out.println("ep2 paid ep1 through im, and ep1 auto-committed the payment!");
    	
    	System.out.println("Please generate some blocks to expire one of the channels.");
    	
    	while (ep1.hasOpenChannels()) {
    		Thread.sleep(1000);
    	}
    	
    	System.out.println("Channel between ep1 and im has expired! Closing channel between ep1 and im.");

    	ep2.closeAllChannelsWith(imKey2);
    	
    	System.out.println("Channel between ep2 and im has been closed!  Please generate some blocks to commit.");
    	
    	while (ep2.hasOpenChannels()) {
    		Thread.sleep(1000);
    	}
    	
    	System.out.println("All channels are fully closed!  Exiting now.");
    	
    	System.exit(0);
    	
    }
}
