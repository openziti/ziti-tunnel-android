/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.asDeferred
import org.openziti.mobile.net.PacketRouter
import org.openziti.mobile.net.TUNNEL_MTU
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class Tunnel(fd: ParcelFileDescriptor, val processor: PacketRouter, val toPeerChannel: ReceiveChannel<ByteBuffer>): CoroutineScope {

    override val coroutineContext = SupervisorJob() + tunnelDispatch

    val output = ParcelFileDescriptor.AutoCloseOutputStream(fd).channel
    val input = ParcelFileDescriptor.AutoCloseInputStream(fd).channel

    val startTime = Instant.now()
    val uptime: Duration
        get() = Duration.between(startTime, Instant.now())

    val reader: Job = reader()
    val writer = launch {
        Log.i(TAG, "running tunnel writer [${Thread.currentThread()}]")

        while(true) {
            val msg = toPeerChannel.receiveCatching().getOrNull() ?: break
            CompletableFuture.supplyAsync {
                output.write(msg)
            }.asDeferred().await()
        }
        Log.i(TAG, "writer is done")
    }

    init {
        writer.invokeOnCompletion {
            Log.i(TAG, "writer() finished ${it?.localizedMessage}")
        }
        reader.invokeOnCompletion {
            Log.i(TAG, "reader() finished ${it?.localizedMessage}")
        }
    }

    fun readerRun(readerQueue: SendChannel<ByteBuffer>) {
        Log.i(TAG, "running tunnel reader [${Thread.currentThread()}]")
        val buf = ByteBuffer.allocateDirect(TUNNEL_MTU)
        try {
            while (true) {
                buf.clear()
                val n = input.read(buf)
                if (n < 0)
                    Log.e(TAG, "read $n bytes")
                val msg = ByteBuffer.allocate(n).put(buf.flip() as ByteBuffer)
                msg.flip() as ByteBuffer
                readerQueue.trySend(msg).onFailure {
                    runBlocking { readerQueue.send(msg) }
                }
            }
        } catch (iex: InterruptedException) {
            Log.i(TAG, "reader thread was interrupted")
        } catch (acex: AsynchronousCloseException) {
            Log.i(TAG, "reader() input was closed: $acex")
        } catch (ex: Throwable) {
            Log.wtf(TAG, "unexpected!", ex)
            close(ex)
        } finally {
            readerQueue.close()
        }
    }

    fun reader() = launch {
        Log.i(TAG, "starting reader")
        val q = Channel<ByteBuffer>(1024)
        val readerThread = Thread(Runnable { readerRun(q) },"tunnel-reader")
        try {
            readerThread.start()

            q.receiveAsFlow().collect {
                processor.route(it)
            }
        } catch (cex: CancellationException) {
            Log.i(TAG, "reader was cancelled")
        } catch (acex: AsynchronousCloseException) {
            Log.i(TAG, "reader() was interrupted: $acex")
        } finally {
            readerThread.interrupt()
        }
    }

    fun close(ex: Throwable? = null) {
        if (ex != null) {
            Log.e(TAG, "closing with exception", ex)
        } else {
            Log.i(TAG, "closing")
        }

        runCatching { output.close() }.onFailure {
            Log.v(TAG, "output.close exception", it)
        }
        runCatching { input.close() }.onFailure {
            Log.v(TAG, "input.close exception", it)
        }

        coroutineContext.cancelChildren()
        cancel("tunnel closed", ex)
    }

    companion object {
        val TAG = Tunnel::class.java.simpleName
        val tunnelDispatch = Executors.newSingleThreadExecutor{
            r -> Thread(r, "tunnel-dispatch").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}