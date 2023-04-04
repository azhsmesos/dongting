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
package com.github.dtprj.dongting.common;

/**
 * @author huangli
 */
public class ObjUtil {

    /**
     * Checks that the given argument is strictly positive. If it is not, throws {@link IllegalArgumentException}.
     * Otherwise, returns the argument.
     */
    public static void checkPositive(int i, String name) {
        if (i <= 0) {
            throw new IllegalArgumentException(name + " : " + i + " (expected: > 0)");
        }
    }

    public static void checkPositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " : " + value + " (expected: >= 0)");
        }
    }

    public static void checkPositive(float i, String name) {
        if (i <= 0) {
            throw new IllegalArgumentException(name + " : " + i + " (expected: > 0)");
        }
    }

    public static void checkNotNegative(int i, String name) {
        if (i < 0) {
            throw new IllegalArgumentException(name + " : " + i + " (expected: >= 0)");
        }
    }

    public static void checkNotNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " : " + value + " (expected: >= 0)");
        }
    }

}
