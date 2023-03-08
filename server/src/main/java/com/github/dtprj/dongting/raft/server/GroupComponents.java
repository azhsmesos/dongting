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

import com.github.dtprj.dongting.raft.impl.MemberManager;
import com.github.dtprj.dongting.raft.impl.RaftGroup;
import com.github.dtprj.dongting.raft.impl.RaftStatus;
import com.github.dtprj.dongting.raft.impl.VoteManager;

/**
 * @author huangli
 */
public class GroupComponents {
    private final RaftServerConfig serverConfig;
    private final RaftGroupConfig groupConfig;
    private final RaftGroup raftGroup;
    private final RaftStatus raftStatus;
    private final MemberManager memberManager;
    private final VoteManager voteManager;
    private final RaftLog raftLog;
    private final StateMachine stateMachine;

    public GroupComponents(RaftServerConfig serverConfig, RaftGroupConfig groupConfig,
                           RaftLog raftLog, StateMachine stateMachine, RaftGroup raftGroup,
                           RaftStatus raftStatus, MemberManager memberManager, VoteManager voteManager) {
        this.serverConfig = serverConfig;
        this.groupConfig = groupConfig;
        this.raftLog = raftLog;
        this.stateMachine = stateMachine;
        this.raftGroup = raftGroup;
        this.raftStatus = raftStatus;
        this.memberManager = memberManager;
        this.voteManager = voteManager;
    }

    public RaftGroup getRaftGroup() {
        return raftGroup;
    }

    public RaftStatus getRaftStatus() {
        return raftStatus;
    }

    public MemberManager getMemberManager() {
        return memberManager;
    }

    public VoteManager getVoteManager() {
        return voteManager;
    }

    public RaftServerConfig getServerConfig() {
        return serverConfig;
    }

    public RaftGroupConfig getGroupConfig() {
        return groupConfig;
    }

    public RaftLog getRaftLog() {
        return raftLog;
    }

    public StateMachine getStateMachine() {
        return stateMachine;
    }
}