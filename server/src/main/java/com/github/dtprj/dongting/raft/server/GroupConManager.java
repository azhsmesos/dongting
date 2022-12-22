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
package com.github.dtprj.dongting.raft.server;

import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.common.MutableInt;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.Commands;
import com.github.dtprj.dongting.net.Decoder;
import com.github.dtprj.dongting.net.HostPort;
import com.github.dtprj.dongting.net.NioClient;
import com.github.dtprj.dongting.net.PbZeroCopyDecoder;
import com.github.dtprj.dongting.net.Peer;
import com.github.dtprj.dongting.net.ProcessContext;
import com.github.dtprj.dongting.net.ReadFrame;
import com.github.dtprj.dongting.net.ReqProcessor;
import com.github.dtprj.dongting.net.StringFieldDecoder;
import com.github.dtprj.dongting.net.WriteFrame;
import com.github.dtprj.dongting.net.ZeroCopyWriteFrame;
import com.github.dtprj.dongting.pb.PbCallback;
import com.github.dtprj.dongting.pb.PbUtil;
import com.github.dtprj.dongting.raft.client.RaftException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author huangli
 */
class GroupConManager {
    private static final DtLog log = DtLogs.getLogger(GroupConManager.class);
    private final UUID uuid = UUID.randomUUID();
    private final int id;
    private final byte[] serversStr;
    private final NioClient client;
    private static final PbZeroCopyDecoder DECODER = new PbZeroCopyDecoder() {
        @Override
        protected PbCallback createCallback(ProcessContext context) {
            return new RaftInitFrameCallback(context.getIoThreadStrDecoder());
        }

        @Override
        protected Object getResult(PbCallback callback) {
            return callback;
        }
    };

    private final ReqProcessor processor = new ReqProcessor() {
        @Override
        public WriteFrame process(ReadFrame frame, ProcessContext context) {
            return new RaftInitWriteFrame();
        }

        @Override
        public Decoder getDecoder() {
            return DECODER;
        }
    };

    public GroupConManager(int id, String serversStr, NioClient client) {
        this.id = id;
        this.serversStr = serversStr.getBytes(StandardCharsets.UTF_8);
        this.client = client;
    }

    public CompletableFuture<List<Peer>> add(Collection<HostPort> servers) {
        ArrayList<Peer> peers = new ArrayList<>();
        CompletableFuture<List<Peer>> finalFuture = new CompletableFuture<>();
        MutableInt count = new MutableInt(servers.size());
        for (HostPort hp : servers) {
            client.addPeer(hp).handle((peer, ex) -> {
                if (ex == null) {
                    peers.add(peer);
                } else {
                    BugLog.getLog().error("add peer fail: {}", hp, ex);
                }
                if (count.decrement() == 0) {
                    finalFuture.complete(peers);
                }
                return null;
            });
        }
        return finalFuture;
    }

    public CompletableFuture<List<RaftNode>> fetch(Collection<Peer> servers) {
        ArrayList<RaftNode> peers = new ArrayList<>();
        CompletableFuture<List<RaftNode>> finalFuture = new CompletableFuture<>();
        MutableInt count = new MutableInt(servers.size());
        for (Peer peer : servers) {
            if (peer.isConnected()) {
                whenConnected(peers, finalFuture, count, peer);
            } else {
                client.connect(peer).handle((v, ex) -> {
                    if (ex == null) {
                        whenConnected(peers, finalFuture, count, peer);
                    } else {
                        log.info("connect to raft server {} fail: {}", peer.getEndPoint(), ex.getMessage());
                        if (count.decrement() == 0) {
                            finalFuture.complete(peers);
                        }
                    }
                    return null;
                });
            }
        }
        return finalFuture;
    }

    private void whenConnected(ArrayList<RaftNode> peers, CompletableFuture<List<RaftNode>> finalFuture, MutableInt count, Peer peer) {
        DtTime timeout = new DtTime(10, TimeUnit.SECONDS);
        CompletableFuture<ReadFrame> f = client.sendRequest(peer, new RaftInitWriteFrame(), DECODER, timeout);
        f.handle((rf, ex) -> {
            if (ex == null) {
                whenRpcFinish(rf, peers, peer);
            } else {
                log.info("init raft server {} fail: {}", peer.getEndPoint(), ex.getMessage());
            }
            if (count.decrement() == 0) {
                finalFuture.complete(peers);
            }
            return null;
        });
    }

    private void whenRpcFinish(ReadFrame rf, ArrayList<RaftNode> peers, Peer peer) {
        RaftInitFrameCallback callback = (RaftInitFrameCallback) rf.getBody();
        boolean self = callback.uuidHigh == uuid.getMostSignificantBits()
                && callback.uuidLow == uuid.getLeastSignificantBits();
        Set<HostPort> remoteServers = RaftServer.parseServers(callback.serversStr);
        RaftNode rm = new RaftNode(callback.id, peer, remoteServers, self);
        peers.add(rm);
    }

    public void init(int electQuorum, Collection<HostPort> servers, int sleepMillis) {
        List<RaftNode> nodes = init0(electQuorum, servers, sleepMillis);
        checkNodes(nodes);
    }

    private void checkNodes(List<RaftNode> nodes) {
        int size = nodes.size();
        for (int i = 0; i < size; i++) {
            RaftNode rn1 = nodes.get(i);
            for (int j = i + 1; j < size; j++) {
                RaftNode rn2 = nodes.get(j);
                if (rn1.getId() == rn2.getId()) {
                    throw new RaftException("find same id: " + rn1.getId() + ", "
                            + rn1.getPeer().getEndPoint() + ", " + rn2.getPeer().getEndPoint());
                }
                if (!rn1.getServers().equals(rn2.getServers())) {
                    throw new RaftException("server list not same: "
                            + rn1.getPeer().getEndPoint() + ", " + rn2.getPeer().getEndPoint());
                }
            }
        }
    }

    @SuppressWarnings("BusyWait")
    private List<RaftNode> init0(int electQuorum, Collection<HostPort> servers, int sleepMillis) {
        while (true) {
            CompletableFuture<List<RaftNode>> f = add(servers)
                    .thenCompose(this::fetch);
            try {
                List<RaftNode> peers = f.get(15, TimeUnit.SECONDS);
                int currentNodes = peers.size();
                if (currentNodes >= electQuorum) {
                    log.info("raft group init success. electQuorum={}, currentNodes={}. remote peers: {}",
                            electQuorum, currentNodes, peers);
                    return peers;
                }
                Thread.sleep(sleepMillis);
            } catch (TimeoutException e) {
                log.warn("init raft group timeout, will continue");
            } catch (Exception e) {
                throw new RaftException(e);
            }
        }
    }

    public ReqProcessor getProcessor() {
        return processor;
    }

    class RaftInitWriteFrame extends ZeroCopyWriteFrame {

        public RaftInitWriteFrame() {
            setCommand(Commands.RAFT_HANDSHAKE);
        }

        @Override
        protected int accurateBodySize() {
            return PbUtil.accurateFix32Size(1, id)
                    + PbUtil.accurateFix64Size(2, uuid.getMostSignificantBits())
                    + PbUtil.accurateFix64Size(3, uuid.getLeastSignificantBits())
                    + PbUtil.accurateLengthDelimitedSize(4, serversStr.length);
        }

        @Override
        protected void encodeBody(ByteBuffer buf) {
            PbUtil.writeFix32(buf, 1, id);
            PbUtil.writeFix64(buf, 2, uuid.getMostSignificantBits());
            PbUtil.writeFix64(buf, 3, uuid.getLeastSignificantBits());
            PbUtil.writeBytes(buf, 4, serversStr);
        }
    }


    static class RaftInitFrameCallback extends PbCallback {
        private final StringFieldDecoder ioThreadStrDecoder;
        private int id;
        private long uuidHigh;
        private long uuidLow;
        private String serversStr;

        public RaftInitFrameCallback(StringFieldDecoder ioThreadStrDecoder) {
            this.ioThreadStrDecoder = ioThreadStrDecoder;
        }

        @Override
        public boolean readFix32(int index, int value) {
            if (index == 1) {
                this.id = value;
            }
            return true;
        }

        @Override
        public boolean readFix64(int index, long value) {
            if (index == 2) {
                this.uuidHigh = value;
            } else if (index == 3) {
                this.uuidLow = value;
            }
            return true;
        }

        @Override
        public boolean readBytes(int index, ByteBuffer buf, int len, boolean begin, boolean end) {
            if (index == 4) {
                serversStr = ioThreadStrDecoder.decodeUTF8(buf, len, begin, end);
            }
            return true;
        }
    }
}
