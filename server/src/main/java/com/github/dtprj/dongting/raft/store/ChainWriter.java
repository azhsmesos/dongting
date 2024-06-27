/*
 * Copyright The Dongting Project
 *
 * The Dongting Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.dtprj.dongting.raft.store;

import com.github.dtprj.dongting.buf.ByteBufferPool;
import com.github.dtprj.dongting.common.DtThread;
import com.github.dtprj.dongting.common.PerfCallback;
import com.github.dtprj.dongting.fiber.Fiber;
import com.github.dtprj.dongting.fiber.FiberCondition;
import com.github.dtprj.dongting.fiber.FiberFrame;
import com.github.dtprj.dongting.fiber.FiberFuture;
import com.github.dtprj.dongting.fiber.FiberGroup;
import com.github.dtprj.dongting.fiber.FrameCallResult;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.RaftException;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.function.Supplier;

/**
 * @author huangli
 */
public abstract class ChainWriter {
    private static final DtLog log = DtLogs.getLogger(ChainWriter.class);

    private final PerfCallback perfCallback;
    private final RaftGroupConfigEx config;
    private final int writePerfType1;
    private final int writePerfType2;
    private final int forcePerfType;
    private final ByteBufferPool directPool;
    private final LinkedList<WriteTask> writeTasks = new LinkedList<>();
    private final LinkedList<WriteTask> forceTasks = new LinkedList<>();

    private final FiberCondition needForceCondition;
    private final Fiber forceFiber;

    private boolean forcing;

    public ChainWriter(RaftGroupConfigEx config, int writePerfType1, int writePerfType2, int forcePerfType) {
        this.config = config;
        this.perfCallback = config.getPerfCallback();
        this.writePerfType1 = writePerfType1;
        this.writePerfType2 = writePerfType2;
        this.forcePerfType = forcePerfType;
        DtThread t = config.getFiberGroup().getThread();
        this.directPool = t.getDirectPool();
        this.needForceCondition = new FiberCondition("needForceCond", config.getFiberGroup());
        this.forceFiber = new Fiber("force-" + config.getGroupId(), config.getFiberGroup(), new ForceLoopFrame());
    }

    protected abstract void writeFinish(WriteTask writeTask);

    protected abstract void forceFinish(WriteTask writeTask);

    protected abstract boolean isClosed();

    public void startForceFiber() {
        forceFiber.start();
    }

    public FiberFuture<Void> shutdownForceFiber() {
        needForceCondition.signal();
        if (forceFiber.isStarted()) {
            return forceFiber.join();
        } else {
            return FiberFuture.completedFuture(config.getFiberGroup(), null);
        }
    }

    public static class WriteTask extends AsyncIoTask {
        private final long posInFile;
        private final long expectNextPos;
        private final boolean force;
        private final ByteBuffer buf;

        private final int perfWriteItemCount;
        private final int perfWriteBytes;
        private final long lastRaftIndex;

        private int perfForceItemCount;
        private long perfForceBytes;


        public WriteTask(RaftGroupConfigEx groupConfig, DtFile dtFile, boolean retry, boolean retryForever,
                         Supplier<Boolean> cancelIndicator, ByteBuffer buf, long posInFile, boolean force,
                         int perfItemCount, long lastRaftIndex) {
            super(groupConfig, dtFile, retry, retryForever, cancelIndicator);
            this.posInFile = posInFile;
            this.force = force;
            this.buf = buf;
            this.perfWriteItemCount = perfItemCount;
            int remaining = buf.remaining();
            this.perfWriteBytes = remaining;
            this.expectNextPos = posInFile + remaining;
            this.lastRaftIndex = lastRaftIndex;
        }

        public long getLastRaftIndex() {
            return lastRaftIndex;
        }
    }

    public void submitWrite(WriteTask task) {
        if (!writeTasks.isEmpty()) {
            WriteTask lastTask = writeTasks.getLast();
            if (lastTask.getDtFile() == task.getDtFile()) {
                if (lastTask.expectNextPos != task.posInFile) {
                    throw Fiber.fatal(new RaftException("pos not continuous"));
                }
            }
        }
        long startTime = perfCallback.takeTime(writePerfType2);
        FiberFuture<Void> f = task.write(task.buf, task.posInFile);
        if (writePerfType1 > 0) {
            perfCallback.fireTime(writePerfType1, startTime, task.perfWriteItemCount, task.perfWriteBytes);
        }
        writeTasks.add(task);
        f.registerCallback((v, ex) -> afterWrite(ex, task, startTime));
    }

    private void afterWrite(Throwable ioEx, WriteTask task, long startTime) {
        perfCallback.fireTime(writePerfType2, startTime, task.perfWriteItemCount, task.perfWriteBytes);
        directPool.release(task.buf);
        if (ioEx != null) {
            log.error("write file {} error: {}", task.getDtFile().getFile(), ioEx.toString());
            FiberGroup.currentGroup().requestShutdown();
            return;
        }
        LinkedList<WriteTask> writeTasks = this.writeTasks;
        WriteTask lastTaskNeedCallback = null;
        while (!writeTasks.isEmpty()) {
            WriteTask t = writeTasks.getFirst();
            FiberFuture<Void> f = t.getFuture();
            if (f.isDone() && f.getEx() == null) {
                writeTasks.removeFirst();
                if (t.force) {
                    lastTaskNeedCallback = t;
                    forceTasks.add(t);
                }
            } else {
                break;
            }
        }
        if (lastTaskNeedCallback != null) {
            needForceCondition.signal();
            writeFinish(lastTaskNeedCallback);
        }
    }

    private class ForceLoopFrame extends FiberFrame<Void> {
        @Override
        protected FrameCallResult handle(Throwable ex) {
            throw Fiber.fatal(ex);
        }

        @Override
        public FrameCallResult execute(Void input) {
            if (isClosed() && !hasTask()) {
                return Fiber.frameReturn();
            }
            LinkedList<WriteTask> forceTasks = ChainWriter.this.forceTasks;
            if (forceTasks.isEmpty()) {
                return needForceCondition.await(this);
            } else {
                WriteTask task = forceTasks.removeFirst();
                task.perfForceItemCount = task.perfWriteItemCount;
                task.perfForceBytes = task.perfWriteBytes;
                WriteTask nextTask;
                while ((nextTask = forceTasks.peekFirst()) != null) {
                    if (task.getDtFile() == nextTask.getDtFile()) {
                        nextTask.perfForceItemCount = nextTask.perfWriteItemCount + task.perfForceItemCount;
                        nextTask.perfForceBytes = nextTask.perfWriteBytes + task.perfForceBytes;
                        task = nextTask;
                        forceTasks.removeFirst();
                    } else {
                        break;
                    }
                }
                ForceFrame ff = new ForceFrame(task.getDtFile().getChannel(), config.getBlockIoExecutor(), false);
                WriteTask finalTask = task;
                long perfStartTime = perfCallback.takeTime(forcePerfType);
                forcing = true;
                return Fiber.call(ff, v -> afterForce(finalTask, perfStartTime));
            }
        }

        private FrameCallResult afterForce(WriteTask task, long perfStartTime) {
            perfCallback.fireTime(forcePerfType, perfStartTime, task.perfForceItemCount, task.perfForceBytes);
            forcing = false;
            forceFinish(task);
            return Fiber.frameReturn();
        }
    }

    public boolean hasTask() {
        return !writeTasks.isEmpty() || !forceTasks.isEmpty() || forcing;
    }

}
