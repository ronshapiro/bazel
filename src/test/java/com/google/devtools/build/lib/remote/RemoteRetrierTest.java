// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import com.google.common.collect.Range;
import com.google.devtools.build.lib.remote.RemoteRetrier.ExponentialBackoff;
import com.google.devtools.build.lib.remote.Retrier.Backoff;
import com.google.devtools.build.lib.remote.Retrier.RetryException;
import com.google.devtools.build.lib.remote.Retrier.Sleeper;
import com.google.devtools.common.options.Options;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/**
 * Tests for {@link RemoteRetrier}.
 */
@RunWith(JUnit4.class)
public class RemoteRetrierTest {

  interface Foo {
    String foo();
  }

  private RemoteRetrierTest.Foo fooMock;

  @Before
  public void setUp() {
    fooMock = Mockito.mock(RemoteRetrierTest.Foo.class);
  }

  @Test
  public void testExponentialBackoff() throws Exception {
    Retrier.Backoff backoff =
        new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(10), 2, 0, 6);
    assertThat(backoff.nextDelayMillis()).isEqualTo(1000);
    assertThat(backoff.nextDelayMillis()).isEqualTo(2000);
    assertThat(backoff.nextDelayMillis()).isEqualTo(4000);
    assertThat(backoff.nextDelayMillis()).isEqualTo(8000);
    assertThat(backoff.nextDelayMillis()).isEqualTo(10000);
    assertThat(backoff.nextDelayMillis()).isEqualTo(10000);
    assertThat(backoff.nextDelayMillis()).isLessThan(0L);
  }

  @Test
  public void testExponentialBackoffJittered() throws Exception {
    Retrier.Backoff backoff =
        new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(10), 2, 0.1, 6);
    assertThat(backoff.nextDelayMillis()).isIn(Range.closedOpen(900L, 1100L));
    assertThat(backoff.nextDelayMillis()).isIn(Range.closedOpen(1800L, 2200L));
    assertThat(backoff.nextDelayMillis()).isIn(Range.closedOpen(3600L, 4400L));
    assertThat(backoff.nextDelayMillis()).isIn(Range.closedOpen(7200L, 8800L));
    assertThat(backoff.nextDelayMillis()).isIn(Range.closedOpen(9000L, 11000L));
    assertThat(backoff.nextDelayMillis()).isIn(Range.closedOpen(9000L, 11000L));
    assertThat(backoff.nextDelayMillis()).isLessThan(0L);
  }

  private void assertThrows(RemoteRetrier retrier, int attempts) throws Exception {
    try {
      retrier.execute(() -> fooMock.foo());
      fail();
    } catch (RetryException e) {
      assertThat(e.getAttempts()).isEqualTo(attempts);
    }
  }

  @Test
  public void testNoRetries() throws Exception {
    RemoteOptions options = Options.getDefaults(RemoteOptions.class);
    options.experimentalRemoteRetry = false;

    RemoteRetrier retrier =
        Mockito.spy(new RemoteRetrier(options, (e) -> true, Retrier.ALLOW_ALL_CALLS));
    when(fooMock.foo())
        .thenReturn("bla")
        .thenThrow(Status.Code.UNKNOWN.toStatus().asRuntimeException());
    assertThat(retrier.execute(() -> fooMock.foo())).isEqualTo("bla");
    assertThrows(retrier, 1);
    Mockito.verify(fooMock, Mockito.times(2)).foo();
  }

  @Test
  public void testNonRetriableError() throws Exception {
    Supplier<Backoff> s =
        () -> new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0, 0.0, 2);
    RemoteRetrier retrier = Mockito.spy(new RemoteRetrier(s, (e) -> false,
        Retrier.ALLOW_ALL_CALLS, Mockito.mock(Sleeper.class)));
    when(fooMock.foo()).thenThrow(Status.Code.UNKNOWN.toStatus().asRuntimeException());
    assertThrows(retrier, 1);
    Mockito.verify(fooMock, Mockito.times(1)).foo();
  }

  @Test
  public void testRepeatedRetriesReset() throws Exception {
    Supplier<Backoff> s =
        () -> new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0, 0.0, 2);
    Sleeper sleeper = Mockito.mock(Sleeper.class);
    RemoteRetrier retrier = Mockito.spy(new RemoteRetrier(s, (e) -> true,
        Retrier.ALLOW_ALL_CALLS, sleeper));

    when(fooMock.foo()).thenThrow(Status.Code.UNKNOWN.toStatus().asRuntimeException());
    assertThrows(retrier, 3);
    assertThrows(retrier, 3);
    Mockito.verify(sleeper, Mockito.times(2)).sleep(1000);
    Mockito.verify(sleeper, Mockito.times(2)).sleep(2000);
    Mockito.verify(fooMock, Mockito.times(6)).foo();
  }

  @Test
  public void testInterruptedExceptionIsPassedThrough() throws Exception {
    InterruptedException thrown = new InterruptedException();

    RemoteOptions options = Options.getDefaults(RemoteOptions.class);
    options.experimentalRemoteRetry = false;
    RemoteRetrier retrier = new RemoteRetrier(options, (e) -> true, Retrier.ALLOW_ALL_CALLS);
    try {
      retrier.execute(() -> {
        throw thrown;
      });
      fail();
    } catch (InterruptedException expected) {
      assertThat(expected).isSameAs(thrown);
    }
  }

  @Test
  public void testPassThroughException() throws Exception {
    StatusRuntimeException thrown = Status.Code.UNKNOWN.toStatus().asRuntimeException();

    RemoteOptions options = Options.getDefaults(RemoteOptions.class);
    RemoteRetrier retrier = new RemoteRetrier(options, (e) -> true, Retrier.ALLOW_ALL_CALLS);

    AtomicInteger numCalls = new AtomicInteger();
    try {
      retrier.execute(() -> {
        numCalls.incrementAndGet();
        throw new RemoteRetrier.PassThroughException(thrown);
      });
      fail();
    } catch (RetryException expected) {
      assertThat(expected).hasCauseThat().isSameAs(thrown);
    }

    assertThat(numCalls.get()).isEqualTo(1);
  }
}
