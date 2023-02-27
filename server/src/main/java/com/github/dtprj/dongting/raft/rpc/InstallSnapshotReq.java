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
package com.github.dtprj.dongting.raft.rpc;

import com.github.dtprj.dongting.buf.ByteBufferPool;
import com.github.dtprj.dongting.pb.PbCallback;
import com.github.dtprj.dongting.pb.PbUtil;

import java.nio.ByteBuffer;

/**
 * @author huangli
 */
//  uint32 term = 1;
//  uint32 leader_id = 2;
//  fixed64 last_included_index = 3;
//  uint32 last_included_term = 4;
//  fixed64 offset = 5;
//  bytes data = 6;
//  bool done = 7;
public class InstallSnapshotReq {
    public int term;
    public int leaderId;
    public long lastIncludedIndex;
    public int lastIncludedTerm;
    public long offset;
    public ByteBuffer data;
    public boolean done;

    public static class Callback extends PbCallback {
        private final InstallSnapshotReq result = new InstallSnapshotReq();

        @Override
        public boolean readVarNumber(int index, long value) {
            switch (index) {
                case 1:
                    result.term = (int) value;
                    break;
                case 2:
                    result.leaderId = (int) value;
                    break;
                case 4:
                    result.lastIncludedTerm = (int) value;
                    break;
                case 7:
                    result.done = value != 0;
                    break;
            }
            return true;
        }

        @Override
        public boolean readFix64(int index, long value) {
            switch (index) {
                case 3:
                    result.lastIncludedIndex = value;
                    break;
                case 5:
                    result.offset = value;
                    break;
            }
            return true;
        }

        @Override
        public boolean readBytes(int index, ByteBuffer buf, int len, boolean begin, boolean end) {
            if (index == 6) {
                if (begin) {
                    result.data = ByteBuffer.allocate(len);
                }
                result.data.put(buf);
                if (end) {
                    result.data.flip();
                }
            }
            return true;
        }

        @Override
        public InstallSnapshotReq getResult() {
            return result;
        }
    }

    public static class WriteFrame extends com.github.dtprj.dongting.net.WriteFrame {

        private final InstallSnapshotReq data;

        public WriteFrame(InstallSnapshotReq data) {
            this.data = data;
        }

        @Override
        protected int calcEstimateBodySize() {
            return PbUtil.accurateUnsignedIntSize(1, data.term)
                    + PbUtil.accurateUnsignedIntSize(2, data.leaderId)
                    + PbUtil.accurateFix64Size(3, data.lastIncludedIndex)
                    + PbUtil.accurateUnsignedIntSize(4, data.lastIncludedTerm)
                    + PbUtil.accurateFix64Size(5, data.offset)
                    + PbUtil.accurateLengthDelimitedSize(6, data.data.remaining(), false)
                    + PbUtil.accurateUnsignedIntSize(7, data.done ? 1 : 0);
        }

        @Override
        protected void encodeBody(ByteBuffer buf, ByteBufferPool pool) {
            super.writeBodySize(buf, estimateBodySize());
            PbUtil.writeUnsignedInt32(buf, 1, data.term);
            PbUtil.writeUnsignedInt32(buf, 2, data.leaderId);
            PbUtil.writeFix64(buf, 3, data.lastIncludedIndex);
            PbUtil.writeUnsignedInt32(buf, 4, data.lastIncludedTerm);
            PbUtil.writeFix64(buf, 5, data.offset);
            PbUtil.writeLengthDelimitedPrefix(buf, 6, data.data.remaining(), false);
            buf.put(data.data);
            PbUtil.writeUnsignedInt32(buf, 7, data.done ? 1 : 0);
        }

    }
}
