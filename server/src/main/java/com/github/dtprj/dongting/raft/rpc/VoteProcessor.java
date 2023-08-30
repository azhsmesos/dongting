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

import com.github.dtprj.dongting.codec.Decoder;
import com.github.dtprj.dongting.codec.PbNoCopyDecoder;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.ChannelContext;
import com.github.dtprj.dongting.net.CmdCodes;
import com.github.dtprj.dongting.net.ReadFrame;
import com.github.dtprj.dongting.net.ReqContext;
import com.github.dtprj.dongting.net.WriteFrame;
import com.github.dtprj.dongting.raft.impl.MemberManager;
import com.github.dtprj.dongting.raft.impl.RaftGroupImpl;
import com.github.dtprj.dongting.raft.impl.RaftStatusImpl;
import com.github.dtprj.dongting.raft.impl.RaftUtil;
import com.github.dtprj.dongting.raft.server.RaftGroup;
import com.github.dtprj.dongting.raft.server.RaftServer;
import com.github.dtprj.dongting.raft.store.StatusManager;

/**
 * @author huangli
 */
public class VoteProcessor extends RaftGroupProcessor<VoteReq> {
    private static final DtLog log = DtLogs.getLogger(VoteProcessor.class);

    private static final PbNoCopyDecoder<VoteReq> decoder = new PbNoCopyDecoder<>(c -> new VoteReq.Callback());

    public VoteProcessor(RaftServer raftServer) {
        super(false, raftServer);
    }

    @Override
    protected int getGroupId(ReadFrame<VoteReq> frame) {
        return frame.getBody().getGroupId();
    }

    @Override
    protected WriteFrame doProcess(ReadFrame<VoteReq> rf, ChannelContext channelContext,
                                   ReqContext reqContext, RaftGroup rg) {
        VoteReq voteReq = rf.getBody();
        VoteResp resp = new VoteResp();
        RaftGroupImpl gc = (RaftGroupImpl) rg;
        RaftStatusImpl raftStatus = gc.getRaftStatus();
        if (MemberManager.validCandidate(raftStatus, voteReq.getCandidateId())) {
            int localTerm = raftStatus.getCurrentTerm();
            if (voteReq.isPreVote()) {
                processPreVote(raftStatus, voteReq, resp, localTerm);
            } else {
                RaftUtil.resetElectTimer(raftStatus);
                processVote(raftStatus, voteReq, resp, localTerm, gc.getStatusManager());
            }
            log.info("receive {} request. granted={}. reqTerm={}, localTerm={}",
                    voteReq.isPreVote() ? "pre-vote" : "vote", resp.isVoteGranted(), voteReq.getTerm(), localTerm);
        } else {
            resp.setVoteGranted(false);
            log.warn("receive vote request from unknown member. remoteId={}, group={}, remote={}",
                    voteReq.getCandidateId(), voteReq.getGroupId(), channelContext.getRemoteAddr());
        }

        resp.setTerm(raftStatus.getCurrentTerm());
        VoteResp.VoteRespWriteFrame wf = new VoteResp.VoteRespWriteFrame(resp);
        wf.setRespCode(CmdCodes.SUCCESS);
        return wf;
    }

    private void processPreVote(RaftStatusImpl raftStatus, VoteReq voteReq, VoteResp resp, int localTerm) {
        if (shouldGrant(raftStatus, voteReq, localTerm)) {
            resp.setVoteGranted(true);
        }
    }

    private void processVote(RaftStatusImpl raftStatus, VoteReq voteReq, VoteResp resp,
                             int localTerm, StatusManager statusManager) {
        boolean needPersist = false;
        if (voteReq.getTerm() > localTerm) {
            RaftUtil.incrTerm(voteReq.getTerm(), raftStatus, -1);
            needPersist = true;
        }

        if (shouldGrant(raftStatus, voteReq, localTerm)) {
            raftStatus.setVotedFor(voteReq.getCandidateId());
            resp.setVoteGranted(true);
            needPersist = true;
        }
        if (needPersist) {
            statusManager.persistSync();
        }
    }

    private boolean shouldGrant(RaftStatusImpl raftStatus, VoteReq voteReq, int localTerm) {
        if (voteReq.getTerm() < localTerm) {
            return false;
        } else {
            if (raftStatus.getVotedFor() == 0 || raftStatus.getVotedFor() == voteReq.getCandidateId()) {
                if (voteReq.getLastLogTerm() > raftStatus.getLastLogTerm()) {
                    return true;
                } else {
                    return voteReq.getLastLogTerm() == raftStatus.getLastLogTerm()
                            && voteReq.getLastLogIndex() >= raftStatus.getLastLogIndex();
                }
            } else {
                return false;
            }
        }
    }

    @Override
    public Decoder<VoteReq> createDecoder() {
        return decoder;
    }
}

