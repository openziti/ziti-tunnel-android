/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.asDeferred
import org.openziti.mobile.net.PacketRouter
import org.openziti.mobile.net.TUNNEL_MTU
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedByInterruptException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class Tunnel(fd: ParcelFileDescriptor, val processor: PacketRouter, val toPeerChannel: ReceiveChannel<ByteBuffer>) {

    val output = ParcelFileDescriptor.AutoCloseOutputStream(fd).channel
    val input = ParcelFileDescriptor.AutoCloseInputStream(fd).channel

    val startTime = Instant.now()
    val uptime: Duration
        get() = Duration.between(startTime, Instant.now())

    val reader: Job = GlobalScope.launch(tunnelDispatch) { reader() }
    val writer = GlobalScope.launch(tunnelDispatch) {
        toPeerChannel.receiveAsFlow().collect {
            Log.v(TAG, "writing ${it.remaining()} on t[${Thread.currentThread().name}]")
            CompletableFuture.supplyAsync {
                output.write(it)
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

    suspend fun reader() {
        Log.i(TAG, "running tunnel reader")

        val buf = ByteBuffer.allocateDirect(TUNNEL_MTU)
        try {
            while (true) {
                    val msg = CompletableFuture.supplyAsync {
                        buf.clear()
                        val n = input.read(buf)
                        if (n < 0)
                            Log.e(TAG, "read $n bytes")
                        val msg = ByteBuffer.allocate(n).put(buf.flip() as ByteBuffer)
                        msg.flip() as ByteBuffer
                    }.asDeferred().await()
                runCatching {
                    processor.route(msg)
                }.onFailure {
                    Log.w(TAG, "routing exception ${it.localizedMessage}", it)
                }
            }
        } catch (cex: CancellationException) {
            Log.i(TAG, "reader was cancelled")
        } catch (clex: ClosedByInterruptException) {
            Log.i(TAG, "reader() was interrupted: $clex")
        } catch (acex: AsynchronousCloseException) {
            Log.i(TAG, "reader() was interrupted: $acex")
        } catch (ex: Throwable) {
            Log.wtf(TAG, "unexpected!", ex)
            close(ex)
        }
    }

    fun close(ex: Throwable? = null) {
        if (ex != null) {
            Log.e(TAG, "closing with exception", ex)
        } else {
            Log.i(TAG, "closing")
        }

        runBlocking {
            reader.cancelAndJoin()
            writer.cancelAndJoin()

            runCatching { output.close() }.onFailure {
                Log.d(TAG, "output.close exception", ex)
            }
            runCatching { input.close() }.onFailure {
                Log.d(TAG, "input.close exception", ex)
            }
        }
    }

    companion object {
        val TAG = Tunnel::class.java.simpleName
        val tunnelDispatch = Executors.newSingleThreadExecutor{
            r -> Thread(r, "tunnel-dispatch").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}