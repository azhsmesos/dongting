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
package com.github.dtprj.dongting.fiber;

import com.github.dtprj.dongting.common.IndexedQueue;

/**
 * @author huangli
 */
@SuppressWarnings({"Convert2Diamond"})
public class Condition {
    private final IndexedQueue<Fiber> waitQueue = new IndexedQueue<Fiber>(16);
    private final Dispatcher dispatcher;

    public Condition(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    IndexedQueue<Fiber> getWaitQueue() {
        return waitQueue;
    }

    public void signal() {
        Fiber f = waitQueue.removeFirst();
        dispatcher.makeReady(f);
    }

    public void signalAll() {
        while (waitQueue.size() > 0) {
            Fiber f = waitQueue.removeFirst();
            dispatcher.makeReady(f);
        }
    }
}
