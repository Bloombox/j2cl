/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.junit.integration.async.data;

import com.google.j2cl.junit.async.J2clAsyncTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Integration test used in J2clTestRunnerTest. */
@RunWith(J2clAsyncTestRunner.class)
public class TestResolvesAfterDelay {

  @Test(timeout = 200L)
  public Thenable testResolvesAfterDelay1() {
    return (onFulfilled, onRejected) -> Timer.schedule(() -> onFulfilled.execute(null), 0);
  }

  private interface SubThenable extends Thenable {}

  @Test(timeout = 200L)
  public SubThenable testResolvesAfterDelay2() {
    return (onFulfilled, onRejected) -> Timer.schedule(() -> onFulfilled.execute(null), 0);
  }

  private abstract static class ThenableImpl implements SubThenable {}

  @Test(timeout = 200L)
  public ThenableImpl testResolvesAfterDelay3() {
    return new ThenableImpl() {
      @Override
      public void then(FullFilledCallback onFulfilled, RejectedCallback onRejected) {
        Timer.schedule(() -> onFulfilled.execute(null), 0);
      }
    };
  }
}
