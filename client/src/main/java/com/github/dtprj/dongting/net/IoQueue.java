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
package com.github.dtprj.dongting.net;

import com.github.dtprj.dongting.common.BitUtil;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author huangli
 */
// TODO currently just work, optimize performance
class IoQueue {
    private static final DtLog log = DtLogs.getLogger(IoQueue.class);
    private final ConcurrentLinkedQueue<WriteData> writeQueue = new ConcurrentLinkedQueue<>();
    private final ArrayList<DtChannel> channels;
    private final boolean server;
    private int invokeIndex;

    public IoQueue(ArrayList<DtChannel> channels) {
        this.channels = channels;
        this.server = channels == null;
    }

    public void write(WriteData data) {
        writeQueue.add(data);
    }

    public boolean dispatchWriteQueue(HashMap<Long, WriteData> pendingRequests) {
        ConcurrentLinkedQueue<WriteData> writeQueue = this.writeQueue;
        WriteData wo;
        boolean result = false;
        while ((wo = writeQueue.poll()) != null) {
            result |= enqueue(pendingRequests, wo);
        }
        return result;
    }

    private boolean enqueue(HashMap<Long, WriteData> pendingRequests, WriteData wo) {
        WriteFrame frame = wo.getData();
        DtChannel dtc = wo.getDtc();
        if (dtc == null) {
            Peer peer = wo.getPeer();
            if (peer == null) {
                if (!server && frame.getFrameType() == CmdType.TYPE_REQ) {
                    dtc = selectChannel();
                    if (dtc == null) {
                        wo.getFuture().completeExceptionally(new NetException("no available channel"));
                        return false;
                    }
                } else {
                    log.error("no peer set");
                    if (frame.getFrameType() == CmdType.TYPE_REQ) {
                        wo.getFuture().completeExceptionally(new NetException("no peer set"));
                    }
                    return false;
                }
            } else {
                dtc = peer.getDtChannel();
                if (dtc == null) {
                    if (frame.getFrameType() == CmdType.TYPE_REQ) {
                        wo.getFuture().completeExceptionally(new NetException("not connected"));
                    }
                    return false;
                }
            }
        }

        if (dtc.isClosed()) {
            if (frame.getFrameType() == CmdType.TYPE_REQ) {
                wo.getFuture().completeExceptionally(new NetException("channel closed"));
            }
            return false;
        }
        if (frame.getFrameType() == CmdType.TYPE_REQ) {
            int seq = dtc.getAndIncSeq();
            frame.setSeq(seq);
            long key = BitUtil.toLong(dtc.getChannelIndexInWorker(), seq);
            WriteData old = pendingRequests.put(key, wo);
            if (old != null) {
                String errMsg = "dup seq: old=" + old.getData() + ", new=" + frame;
                log.error(errMsg);
                wo.getFuture().completeExceptionally(new NetException(errMsg));
                pendingRequests.put(key, old);
                return false;
            }
        }
        dtc.getSubQueue().enqueue(frame);
        return true;
    }

    private DtChannel selectChannel() {
        ArrayList<DtChannel> list = this.channels;
        int size = list.size();
        if (size == 0) {
            return null;
        }
        int idx = invokeIndex;
        if (idx < size) {
            invokeIndex = idx + 1;
            return list.get(idx);
        } else {
            invokeIndex = 0;
            return list.get(0);
        }
    }
}
