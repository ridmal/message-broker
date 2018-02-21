/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package io.ballerina.messaging.broker.common.util.function;

/**
 * Functional interface to wrap a throwing action.
 *
 * @param <E> checked exception thrown by the method
 */
@FunctionalInterface
public interface ThrowingRunnable<E extends Exception> {
    /**
     * Do the action, or throws an exception if unable to do so.
     *
     * @throws E if unable to compute a result
     */
    void run() throws E;
}
