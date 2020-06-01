/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openziti.net.dns.DNSResolver
import org.openziti.tunnel.net.PacketRouterImpl
import org.openziti.tunnel.net.TUNNEL_MTU
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.Executors

class Tunnel(fd: ParcelFileDescriptor, dns: DNSResolver, dnsAddr: String) {
    val output = ParcelFileDescriptor.AutoCloseOutputStream(fd).channel
    val input = ParcelFileDescriptor.AutoCloseInputStream(fd).channel
    val processor = PacketRouterImpl(dns, dnsAddr, this::onInbound)
    val readerThread = Thread(this::reader, "tunnel-read")
    val toPeerChannel = Channel<ByteBuffer>(Channel.UNLIMITED)
    val writeDispatcher = Executors.newSingleThreadExecutor{
        r -> Thread(r, "tunner-write")
    }.asCoroutineDispatcher()

    fun start() {
        readerThread.start()
        GlobalScope.launch(writeDispatcher){
            writer()
        }
    }

    suspend fun writer() {
        for (b in toPeerChannel) {
            Log.v(TAG, "writing ${b.remaining()} on t[${Thread.currentThread().name}]")
            output.write(b)
        }

        Log.i(TAG, "writer() finished")
    }

    fun reader() {
        Log.i(TAG, "running tunnel reader")

        val buf = ByteBuffer.allocateDirect(TUNNEL_MTU)
        try {
            while (true) {
                buf.clear()
                val n = input.read(buf)
                buf.flip()
                val msg = ByteBuffer.allocate(n).put(buf)
                msg.flip()
                try {
                    processor.route(msg)
                } catch (ex: Throwable) {
                    Log.w(TAG, "routing exception ${ex.localizedMessage}", ex)
                }
            }
        } catch (interrupt: ClosedByInterruptException) {
            Log.i(TAG, "reader() was interrupted")
        } catch (ex: Throwable) {
            Log.wtf(TAG, "unexpected!", ex)
            close(ex)
        }
    }

    fun onInbound(b: ByteBuffer) =
        runBlocking { toPeerChannel.send(b) }

    fun close(ex: Throwable? = null) {
        ex?.let {
            Log.e(TAG, "closing with exception: $it")
        }

        toPeerChannel.close()
        writeDispatcher.close()
        processor.stop()
        readerThread.interrupt()
        output.close()
        input.close()
    }

    companion object {
        val TAG = Tunnel::class.java.simpleName
    }
}