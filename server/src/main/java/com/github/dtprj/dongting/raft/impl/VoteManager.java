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

import com.github.dtprj.dongting.codec.Decoder;
import com.github.dtprj.dongting.codec.PbNoCopyDecoder;
import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.fiber.Fiber;
import com.github.dtprj.dongting.fiber.FiberFrame;
import com.github.dtprj.dongting.fiber.FrameCallResult;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.Commands;
import com.github.dtprj.dongting.net.NioClient;
import com.github.dtprj.dongting.net.ReadFrame;
import com.github.dtprj.dongting.raft.rpc.VoteReq;
import com.github.dtprj.dongting.raft.rpc.VoteResp;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;
import com.github.dtprj.dongting.raft.server.RaftServerConfig;
import com.github.dtprj.dongting.raft.store.StatusManager;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author huangli
 */
public class VoteManager {

    private static final DtLog log = DtLogs.getLogger(VoteManager.class);

    private final GroupComponents gc;

    private final RaftGroupConfigEx groupConfig;
    private final NioClient client;
    private final RaftStatusImpl raftStatus;
    private final RaftServerConfig config;
    private final int groupId;

    private LinearTaskRunner linearTaskRunner;
    private StatusManager statusManager;

    private boolean voting;
    private HashSet<Integer> votes;
    private int currentVoteId;

    private static final Decoder<VoteResp> RESP_DECODER = new PbNoCopyDecoder<>(c -> new VoteResp.Callback());

    public VoteManager(NioClient client, GroupComponents gc) {
        this.gc = gc;
        this.client = client;
        this.groupConfig = gc.getGroupConfig();
        this.raftStatus = gc.getRaftStatus();
        this.config = gc.getServerConfig();
        this.groupId = groupConfig.getGroupId();
    }

    public void postInit() {
        this.linearTaskRunner = gc.getLinearTaskRunner();
        this.statusManager = gc.getStatusManager();
    }

    public void startFiber() {
        VoteFiberFrame ff = new VoteFiberFrame();
        Fiber f = new Fiber("vote-" + groupId, groupConfig.getFiberGroup(), ff, true);
        f.start();
    }

    public void cancelVote() {
        if (voting) {
            log.info("cancel current voting: groupId={}, voteId={}", groupId, currentVoteId);
            voting = false;
            votes = null;
            currentVoteId++;
        }
    }

    private void initStatusForVoting() {
        voting = true;
        currentVoteId++;
        votes = new HashSet<>();
    }

    private int readyCount(Collection<RaftMember> list) {
        int count = 0;
        for (RaftMember member : list) {
            if (member.isReady()) {
                // include self
                count++;
            }
        }
        return count;
    }

    private boolean readyNodesNotEnough(boolean preVote) {
        if (readyNodesNotEnough(raftStatus.getMembers(), preVote, false)) {
            return true;
        }
        return readyNodesNotEnough(raftStatus.getPreparedMembers(), preVote, true);
    }

    private boolean readyNodesNotEnough(List<RaftMember> list, boolean preVote, boolean jointConsensus) {
        if (list.isEmpty()) {
            // for joint consensus
            return false;
        }
        int count = readyCount(list);
        if (count < RaftUtil.getElectQuorum(list.size())) {
            log.warn("{} only {} node is ready, can't start {}. groupId={}, term={}",
                    jointConsensus ? "[joint consensus]" : "", count,
                    preVote ? "pre-vote" : "vote", groupId, raftStatus.getCurrentTerm());
            return true;
        }
        return false;
    }

    public void tryStartPreVote() {
        if (!MemberManager.validCandidate(raftStatus, config.getNodeId())) {
            log.info("not valid candidate, can't start pre vote. groupId={}, term={}",
                    groupId, raftStatus.getCurrentTerm());
            return;
        }

        if (readyNodesNotEnough(true)) {
            return;
        }

        RaftUtil.resetElectTimer(raftStatus);

        Set<RaftMember> voter = RaftUtil.union(raftStatus.getMembers(), raftStatus.getPreparedMembers());
        initStatusForVoting();

        log.info("node ready, start pre vote. groupId={}, term={}, voteId={}, lastIndex={}",
                groupId, raftStatus.getCurrentTerm(), currentVoteId, raftStatus.getLastLogIndex());
        startPreVote(voter);
    }

    private void startPreVote(Set<RaftMember> voter) {
        for (RaftMember member : voter) {
            if (member.isReady()) {
                sendRequest(member, true, 0);
            }
        }
    }

    private void sendRequest(RaftMember member, boolean preVote, long leaseStartTime) {
        VoteReq req = new VoteReq();
        int currentTerm = raftStatus.getCurrentTerm();
        req.setGroupId(groupId);
        req.setTerm(currentTerm);
        req.setCandidateId(config.getNodeId());
        req.setLastLogIndex(raftStatus.getLastLogIndex());
        req.setLastLogTerm(raftStatus.getLastLogTerm());
        req.setPreVote(preVote);
        VoteReq.VoteReqWriteFrame wf = new VoteReq.VoteReqWriteFrame(req);
        wf.setCommand(Commands.RAFT_REQUEST_VOTE);
        DtTime timeout = new DtTime(config.getRpcTimeout(), TimeUnit.MILLISECONDS);

        final int voteIdOfRequest = this.currentVoteId;

        if (member.getNode().isSelf()) {
            VoteResp resp = new VoteResp();
            resp.setVoteGranted(true);
            resp.setTerm(currentTerm);
            fireRespProcessFiber(req, resp, null, member, leaseStartTime, voteIdOfRequest);
        } else {
            CompletableFuture<ReadFrame<VoteResp>> f = client.sendRequest(member.getNode().getPeer(), wf,
                    RESP_DECODER, timeout);
            log.info("send {} request. remoteNode={}, groupId={}, term={}, lastLogIndex={}, lastLogTerm={}",
                    preVote ? "pre-vote" : "vote", member.getNode().getNodeId(), groupId,
                    currentTerm, req.getLastLogIndex(), req.getLastLogTerm());
            f.whenComplete((rf, ex) -> fireRespProcessFiber(req, rf.getBody(),
                    ex, member, leaseStartTime, voteIdOfRequest));
        }
    }

    private void fireRespProcessFiber(VoteReq req, VoteResp resp, Throwable ex, RaftMember member,
                                      long leaseStartTime, int voteIdOfRequest) {
        String fiberName = "vote-resp-processor(" + voteIdOfRequest + "," + member.getNode().getNodeId() + ")";
        RespProcessFiberFrame initFrame = new RespProcessFiberFrame(resp, ex, member,
                req, voteIdOfRequest, leaseStartTime);
        groupConfig.getFiberGroup().fireFiber(fiberName, initFrame);
    }

    private static int getVoteCount(Set<Integer> members, Set<Integer> votes) {
        int count = 0;
        for (int nodeId : votes) {
            if (members.contains(nodeId)) {
                count++;
            }
        }
        return count;
    }

    private boolean isElected(int nodeId, boolean preVote) {
        if (!votes.add(nodeId)) {
            return false;
        }
        int quorum = raftStatus.getElectQuorum();
        int voteCount = getVoteCount(raftStatus.getNodeIdOfMembers(), votes);
        if (raftStatus.getPreparedMembers().isEmpty()) {
            log.info("[{}] get {} grant of {}", preVote ? "pre-vote" : "vote", voteCount, quorum);
            return voteCount >= quorum;
        } else {
            int jointQuorum = RaftUtil.getElectQuorum(raftStatus.getPreparedMembers().size());
            int jointVoteCount = getVoteCount(raftStatus.getNodeIdOfPreparedMembers(), votes);
            log.info("[{}] get {} grant of {}, joint consensus get {} grant of {}",
                    preVote ? "pre-vote" : "vote", voteCount, quorum, jointVoteCount, jointQuorum);
            return voteCount >= quorum && jointVoteCount >= jointQuorum;
        }
    }

    private class VoteFiberFrame extends FiberFrame<Void> {

        private final long electTimeoutNanos = Duration.ofMillis(config.getElectTimeout()).toNanos();
        private final long INTERVAL = new Random().nextInt(150) + 150;

        @Override
        public FrameCallResult execute(Void input) {
            boolean timeout = raftStatus.getTs().getNanoTime() - raftStatus.getLastElectTime() > electTimeoutNanos;
            if (voting) {
                if (timeout) {
                    cancelVote();
                } else {
                    return Fiber.sleep(INTERVAL, this);
                }
            }
            if (timeout) {
                // move last elect time 1 seconds, prevent pre-vote too frequently if failed
                long newLastElectTime = raftStatus.getTs().getNanoTime() - raftStatus.getElectTimeoutNanos()
                        - TimeUnit.SECONDS.toNanos(1);
                raftStatus.setLastElectTime(newLastElectTime);

                if (RaftUtil.writeNotFinished(raftStatus)) {
                    return RaftUtil.waitWriteFinish(raftStatus, this);
                }
                tryStartPreVote();
                return Fiber.resume(null, this);
            } else {
                return Fiber.sleep(INTERVAL, this);
            }
        }
    }

    private class RespProcessFiberFrame extends FiberFrame<Void> {

        private final VoteResp resp;
        private final Throwable ex;
        private final RaftMember remoteMember;
        private final VoteReq req;
        private final int voteIdOfRequest;
        private final long leaseStartTime;

        private RespProcessFiberFrame(VoteResp resp, Throwable ex, RaftMember remoteMember, VoteReq req,
                                      int voteIdOfRequest, long leaseStartTime) {
            this.resp = resp;
            this.ex = ex;
            this.remoteMember = remoteMember;
            this.req = req;
            this.voteIdOfRequest = voteIdOfRequest;
            this.leaseStartTime = leaseStartTime;
        }

        @Override
        public FrameCallResult execute(Void input) {
            if (req.isPreVote()) {
                return processPreVoteResp();
            } else {
                return processVoteResp();
            }
        }

        private boolean voteCheckFail(long oldVoteId) {
            if (oldVoteId != currentVoteId) {
                log.info("vote id changed, ignore {} response. remoteNode={}, grant={}",
                        req.isPreVote() ? "preVote" : "vote", remoteMember.getNode().getNodeId(),
                        resp.isVoteGranted());
                return true;
            }
            if (MemberManager.validCandidate(raftStatus, config.getNodeId())) {
                return false;
            } else {
                BugLog.getLog().error("not valid candidate, cancel vote. groupId={}, term={}",
                        groupId, raftStatus.getCurrentTerm());
                cancelVote();
                return true;
            }
        }

        private FrameCallResult processPreVoteResp() {
            if (voteCheckFail(voteIdOfRequest)) {
                return Fiber.frameReturn();
            }
            int currentTerm = raftStatus.getCurrentTerm();
            if (ex == null) {
                if (resp.isVoteGranted() && raftStatus.getRole() == RaftRole.follower
                        && resp.getTerm() == req.getTerm()) {
                    log.info("receive pre-vote grant success. term={}, remoteNode={}, groupId={}",
                            currentTerm, remoteMember.getNode().getNodeId(), groupId);
                    if (isElected(remoteMember.getNode().getNodeId(), true)) {
                        log.info("pre-vote success. groupId={}. term={}", groupId, currentTerm);
                        return startVote();
                    }
                } else {
                    log.info("receive pre-vote grant fail. term={}, remoteNode={}, groupId={}",
                            currentTerm, remoteMember.getNode().getNodeId(), groupId);
                }
            } else {
                log.warn("pre-vote rpc fail. term={}, remoteNode={}, groupId={}, error={}",
                        currentTerm, remoteMember.getNode().getNodeId(), groupId, ex.toString());
                // don't send more request for simplification
            }
            return Fiber.frameReturn();
        }

        private FrameCallResult startVote() {
            if (readyNodesNotEnough(false)) {
                cancelVote();
                return Fiber.frameReturn();
            }

            Set<RaftMember> voter = RaftUtil.union(raftStatus.getMembers(), raftStatus.getPreparedMembers());
            // add vote id, so rest pre-vote response is ignored
            initStatusForVoting();


            RaftUtil.resetStatus(raftStatus);
            if (raftStatus.getRole() != RaftRole.candidate) {
                log.info("change to candidate. groupId={}, oldTerm={}", groupId, raftStatus.getCurrentTerm());
                raftStatus.setRole(RaftRole.candidate);
            }

            raftStatus.setCurrentTerm(raftStatus.getCurrentTerm() + 1);
            raftStatus.setVotedFor(config.getNodeId());
            raftStatus.copyShareStatus();

            statusManager.persistAsync(true);
            int voteIfBeforePersist = currentVoteId;
            return statusManager.waitForce(v -> afterStartVotePersist(voter, voteIfBeforePersist));
        }

        private FrameCallResult afterStartVotePersist(Set<RaftMember> voter, int oldVoteId) {
            if (voteCheckFail(oldVoteId)) {
                return Fiber.frameReturn();
            }
            if (readyNodesNotEnough(false)) {
                cancelVote();
                return Fiber.frameReturn();
            }
            log.info("start vote. groupId={}, newTerm={}, voteId={}, lastIndex={}", groupId,
                    raftStatus.getCurrentTerm(), currentVoteId, raftStatus.getLastLogIndex());

            long leaseStartTime = raftStatus.getTs().getNanoTime();
            for (RaftMember member : voter) {
                sendRequest(member, false, leaseStartTime);
            }
            return Fiber.frameReturn();
        }

        private FrameCallResult processVoteResp() {
            if (voteCheckFail(voteIdOfRequest)) {
                return Fiber.frameReturn();
            }
            if (ex == null) {
                processVoteResp(remoteMember, req);
            } else {
                log.warn("vote rpc fail. groupId={}, term={}, remote={}, error={}",
                        groupId, req.getTerm(), remoteMember.getNode().getHostPort(), ex.toString());
                // don't send more request for simplification
            }
            return Fiber.frameReturn();
        }

        private void processVoteResp(RaftMember remoteMember, VoteReq voteReq) {
            int remoteTerm = resp.getTerm();
            if (remoteTerm < raftStatus.getCurrentTerm()) {
                log.warn("receive outdated vote resp, ignore, remoteTerm={}, reqTerm={}, remoteId={}, groupId={}",
                        resp.getTerm(), voteReq.getTerm(), remoteMember.getNode().getNodeId(), groupId);
            } else if (remoteTerm == raftStatus.getCurrentTerm()) {
                if (raftStatus.getRole() != RaftRole.candidate) {
                    log.warn("receive vote resp, not candidate, ignore. remoteTerm={}, reqTerm={}, remoteId={}, groupId={}",
                            resp.getTerm(), voteReq.getTerm(), remoteMember.getNode().getNodeId(), groupId);
                } else {
                    log.info("receive vote resp, granted={}, remoteTerm={}, reqTerm={}, remoteId={}, groupId={}",
                            resp.isVoteGranted(), resp.getTerm(),
                            voteReq.getTerm(), remoteMember.getNode().getNodeId(), groupId);
                    if (resp.isVoteGranted()) {
                        remoteMember.setLastConfirmReqNanos(leaseStartTime);
                        if (isElected(remoteMember.getNode().getNodeId(), false)) {
                            log.info("successfully elected, change to leader. groupId={}, term={}", groupId, raftStatus.getCurrentTerm());
                            RaftUtil.changeToLeader(raftStatus);
                            RaftUtil.updateLease(raftStatus);
                            raftStatus.copyShareStatus();
                            cancelVote();
                            linearTaskRunner.sendHeartBeat();
                        }
                    }
                }
            } else {
                RaftUtil.incrTerm(remoteTerm, raftStatus, -1);
                statusManager.persistAsync(true);
                // no rest action, so not call statusManager.waitSync
            }
        }
    }

}
