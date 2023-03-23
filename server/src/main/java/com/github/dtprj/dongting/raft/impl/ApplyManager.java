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
package com.github.dtprj.dongting.raft.impl;

import com.github.dtprj.dongting.common.Timestamp;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftExecTimeoutException;
import com.github.dtprj.dongting.raft.server.RaftInput;
import com.github.dtprj.dongting.raft.server.RaftLog;
import com.github.dtprj.dongting.raft.server.RaftOutput;
import com.github.dtprj.dongting.raft.server.StateMachine;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author huangli
 */
public class ApplyManager {

    private final RaftLog raftLog;
    private final StateMachine stateMachine;
    private final Timestamp ts;

    ApplyManager(RaftLog raftLog, StateMachine stateMachine, Timestamp ts) {
        this.raftLog = raftLog;
        this.stateMachine = stateMachine;
        this.ts = ts;
    }

    public void apply(long commitIndex, RaftStatus raftStatus) {
        long lastApplied = raftStatus.getLastApplied();
        long diff = commitIndex - lastApplied;
        while (diff > 0) {
            long index = lastApplied + 1;
            RaftTask rt = raftStatus.getPendingRequests().get(index);
            if (rt == null) {
                int limit = (int) Math.min(diff, 100L);
                LogItem[] items = RaftUtil.load(raftLog, raftStatus,
                        index, limit, 16 * 1024 * 1024);
                int readCount = items.length;
                for (int i = 0; i < readCount; i++) {
                    LogItem item = items[i];
                    RaftInput input;
                    if (item.getType() == LogItem.TYPE_NORMAL) {
                        Object o = stateMachine.decode(item.getBuffer());
                        input = new RaftInput(item.getBuffer(), o, null, false);
                    } else {
                        input = new RaftInput(item.getBuffer(), null, null, false);
                    }
                    rt = new RaftTask(ts, item.getType(), input, null);
                    execChain(index + i, rt);
                }
                lastApplied += readCount;
                diff -= readCount;
            } else {
                execChain(index, rt);
                lastApplied++;
                diff--;
            }
        }

        raftStatus.setLastApplied(commitIndex);
    }

    @SuppressWarnings("ForLoopReplaceableByForEach")
    private void execChain(long index, RaftTask rt) {
        exec(index, rt);
        ArrayList<RaftTask> nextReaders = rt.nextReaders;
        if (nextReaders == null) {
            return;
        }
        for (int i = 0; i < nextReaders.size(); i++) {
            RaftTask readerTask = nextReaders.get(i);
            exec(index, readerTask);
        }
    }

    public void exec(long index, RaftTask rt) {
        if (rt.type != LogItem.TYPE_NORMAL) {
            return;
        }
        RaftInput input = rt.input;
        CompletableFuture<RaftOutput> future = rt.future;
        if (input.isReadOnly() && input.getDeadline().isTimeout(ts)) {
            if (future != null) {
                future.completeExceptionally(new RaftExecTimeoutException("timeout "
                        + input.getDeadline().getTimeout(TimeUnit.MILLISECONDS) + "ms"));
            }
            return;
        }
        try {
            Object result = stateMachine.exec(index, input);
            if (future != null) {
                future.complete(new RaftOutput(index, result));
            }
        } catch (RuntimeException e) {
            if (input.isReadOnly()) {
                if (future != null) {
                    future.completeExceptionally(e);
                }
            } else {
                throw e;
            }
        }

    }
}
