// Copyright 2015-2018 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import io.nats.client.Options;
import io.nats.client.support.ByteArrayBuilder;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class NatsConnectionWriterReentrantImpl extends NatsConnectionWriterImpl {

    private final ByteArrayBuilder regularSendBuffer;
    private final ByteArrayBuilder reconnectSendBuffer;
    private final int discardMessageCountThreshold;

    private long regularQueuedMessageCount;
    private long reconnectQueuedMessageCount;

    private boolean lockFlag;
    private final Lock lock;
    private final Condition producerFinished;
    private final Condition consumerFinished;

    NatsConnectionWriterReentrantImpl(NatsConnection connection) {
        super(connection);

        Options options = connection.getOptions();
        int bufSize = options.getBufferSize();
        regularSendBuffer = new ByteArrayBuilder(bufSize);
        reconnectSendBuffer = new ByteArrayBuilder(bufSize);

        discardMessageCountThreshold = options.isDiscardMessagesWhenOutgoingQueueFull()
                ? options.getMaxMessagesInOutgoingQueue() : Integer.MAX_VALUE;

        regularQueuedMessageCount = 0;
        reconnectQueuedMessageCount = 0;

        lockFlag = false;
        lock = new ReentrantLock();
        producerFinished = lock.newCondition();
        consumerFinished = lock.newCondition();
    }

    // Should only be called if the current thread has exited.
    // Use the Future from stop() to determine if it is ok to call this.
    // This method resets that future so mistiming can result in badness.
    void start(Future<DataPort> dataPortFuture) {
        this.startStopLock.lock();
        try {
            this.dataPortFuture = dataPortFuture;
            this.running.set(true);
            this.stopped = connection.getExecutor().submit(this, Boolean.TRUE);
        } finally {
            this.startStopLock.unlock();
        }
    }

    // May be called several times on an error.
    // Returns a future that is completed when the thread completes, not when this
    // method does.
    Future<Boolean> stop() {
        this.running.set(false);
        lock.lock();
        try {
            lockFlag = true;
            producerFinished.signal();
        } finally {
            lock.unlock();
        }
        return this.stopped;
    }

    @Override
    public void run() {
        try {
            dataPort = dataPortFuture.get(); // Will wait for the future to complete
            while (running.get()) {
                lock.lock();
                try {
                    //no new message wait for new message
                    while (!lockFlag) {
                        producerFinished.await();
                    }

                    boolean rmode = reconnectMode.get();
                    long mcount = rmode ? reconnectQueuedMessageCount : regularQueuedMessageCount;
                    if (mcount > 0) {
                        ByteArrayBuilder bab = rmode ? reconnectSendBuffer : regularSendBuffer;
                        int byteCount = bab.length();
                        dataPort.write(bab.internalArray(), byteCount);
                        bab.clear();
                        connection.getNatsStatistics().registerWrite(byteCount);
                        if (rmode) {
                            reconnectQueuedMessageCount = 0;
                        }
                        else {
                            regularQueuedMessageCount = 0;
                        }
                    }
                    lockFlag = false;

                    //message consumed, notify waiting thread
                    consumerFinished.signal();

                } catch(InterruptedException ie) {
                    System.out.println("Thread interrupted - consumer");
                } finally {
                    lock.unlock();
                }
            }
        } catch (IOException | BufferOverflowException io) {
            System.out.println(io);
            connection.handleCommunicationIssue(io);
        } catch (CancellationException | ExecutionException | InterruptedException ex) {
            System.out.println(ex);
            // Exit
        } finally {
            running.set(false);
        }
    }

    @Override
    long queuedBytes() {
        return regularSendBuffer.length();
    }

    boolean queue(NatsMessage msg) {
        if (regularQueuedMessageCount >= discardMessageCountThreshold) {
            return false;
        }
        _queue(msg, regularSendBuffer);
        return true;
    }

    void queueInternalMessage(NatsMessage msg) {
        if (reconnectMode.get()) {
            _queue(msg, reconnectSendBuffer);
        } else {
            _queue(msg, regularSendBuffer);
        }
    }

    void _queue(NatsMessage msg, ByteArrayBuilder bab) {
        lock.lock();
        try {
            //last message not consumed, wait for it be consumed
            while (lockFlag) {
                consumerFinished.await();
            }

            long startSize = bab.length();
            msg.appendSerialized(bab);
            long added = bab.length() - startSize;

            // it's safe check for object equality
            if (bab == regularSendBuffer) {
                regularQueuedMessageCount++;
            }
            else {
                reconnectQueuedMessageCount++;
            }

            connection.getNatsStatistics().incrementOutMsgsAndBytes(added);

            lockFlag = true;

            //new message added, notify waiting thread
            producerFinished.signal();

        } catch(InterruptedException ie) {
            System.out.println("Thread interrupted - produce");
        } finally {
            lock.unlock();
        }
    }
}
