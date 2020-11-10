/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import org.openziti.mobile.net.PacketRouter
import org.openziti.mobile.net.TUNNEL_MTU
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.Executors

class Tunnel(fd: ParcelFileDescriptor, val processor: PacketRouter, val toPeerChannel: ReceiveChannel<ByteBuffer>) {

    val output = ParcelFileDescriptor.AutoCloseOutputStream(fd).channel
    val input = ParcelFileDescriptor.AutoCloseInputStream(fd).channel
    val readerThread = Thread(this::reader, "tunnel-read")

    val writer: Job
    init {
        readerThread.start()
        writer = GlobalScope.launch(writeDispatcher) {
            toPeerChannel.consumeAsFlow().collect {
                Log.v(TAG, "writing ${it.remaining()} on t[${Thread.currentThread().name}]")
                output.write(it)
            }
        }
        writer.invokeOnCompletion {
            Log.i(TAG, "writer() finished ${it?.localizedMessage}")
        }
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

//    fun onInbound(b: ByteBuffer): Unit = runBlocking {
//        toPeerChannel.runCatching {
//            toPeerChannel.send(b)
//        }.onFailure {
//            Log.w(TAG, "")
//        }
//    }

    fun close(ex: Throwable? = null) {
        ex?.let {
            Log.e(TAG, "closing with exception: $it")
        }

        runBlocking { writer.cancelAndJoin() }
        readerThread.interrupt()
        output.close()
        input.close()
    }

    companion object {
        val TAG = Tunnel::class.java.simpleName
        val writeDispatcher = Executors.newSingleThreadExecutor{
            r -> Thread(r, "tunnel-write").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}