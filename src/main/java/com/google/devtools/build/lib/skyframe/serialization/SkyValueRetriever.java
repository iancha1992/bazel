// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.getRootCause;
import static com.google.common.util.concurrent.Futures.getDone;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunction.LookupEnvironment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

/** Fetches remotely stored {@link SkyValue}s by {@link SkyKey}. */
// TODO: b/364831651 - this looks up the serialized SkyKey directly from the key value store. For
// this to work beyond demo purposes, the key must include additional version metadata.
public final class SkyValueRetriever {
  /** Clients of this class should use this value as the initial state. */
  public static final SerializationState INITIAL_STATE = InitialQuery.INITIAL_QUERY;

  /**
   * A {@link SkyKeyComputeState} implementation may additionally support this interface to enable
   * the {@link SkyFunction} to use {@link #tryRetrieve}.
   *
   * <p>Provides access to a {@link SerializationState} that is intended to persist across Skyframe
   * restarts. Implementations should initialize the value to {@link #INITIAL_STATE}.
   */
  public interface SerializationStateProvider {
    SerializationState getSerializationState();

    void setSerializationState(SerializationState state);
  }

  /** Returned status of {@link DependOnFutureShim#dependOnFuture}. */
  public enum ObservedFutureStatus {
    /** If the future was already done. */
    DONE,
    /**
     * If the future was not done.
     *
     * <p>Indicates that a Skyframe restart is needed.
     */
    NOT_DONE
  }

  /**
   * Encapsulates {@link Environment#dependOnFuture}, {@link Environment#valuesMissing} in a single
   * method.
   *
   * <p>Decoupling this from {@link Environment} simplifies testing and the API.
   */
  public interface DependOnFutureShim {
    /**
     * Outside of testing, delegates to {@link Environment#dependOnFuture} then {@link
     * Environment#valuesMissing} to determine the return value.
     *
     * @throws IllegalStateException if called when an underlying environment's {@link
     *     Environment#valuesMissing} is already true.
     */
    ObservedFutureStatus dependOnFuture(ListenableFuture<?> future);
  }

  /** A convenience implementation used with an {@link Environment} instance. */
  public static final class DefaultDependOnFutureShim implements DependOnFutureShim {
    private final Environment env;

    public DefaultDependOnFutureShim(Environment env) {
      this.env = env;
    }

    @Override
    public ObservedFutureStatus dependOnFuture(ListenableFuture<?> future) {
      checkState(!env.valuesMissing());
      env.dependOnFuture(future);
      return env.valuesMissing() ? ObservedFutureStatus.NOT_DONE : ObservedFutureStatus.DONE;
    }
  }

  /**
   * Opaque state of serialization.
   *
   * <p>Each permitted type corresponds to a mostly sequential state.
   *
   * <ol>
   *   <li>{@link InitialQuery}: serializes the key and initiates fetching from {@link
   *       FingerprintValueService}.
   *   <li>{@link WaitingForFutureValueBytes}: waits for value bytes from the {@link
   *       FingerprintValueService} to become available and begins deserializing those bytes. When
   *       the result is available immediately, may directly transition to {@link RetrievedValue}.
   *   <li>{@link WaitingForFutureLookupContinuation}: waits for the {@link
   *       SkyframeLookupContinuation} to become available. This corresponds to immediate
   *       deserialization of any owned shared bytes. Immediate means that the shared bytes have
   *       been processed, but pending futures might be registered by this step. The significance of
   *       this instant is that all {@link SkyKey}s that need to be looked up to deserialize the
   *       value are known.
   *   <li>{@link WaitingForLookupContinuation}: looks up any {@link SkyKey}s needed for
   *       deserialization and waits for any resulting Skyframe restarts.
   *   <li>{@link WaitingForFutureResult}: waits for remaining futures needed to complete
   *       deserialization. This includes: any required <i>unowned</i> shared values; a small amount
   *       of work to set values in parents; and propagating the corresponding futures.
   *   <li>{@link RetrievedValue}: returns the retrieved value, idempotently.
   * </ol>
   */
  public static sealed interface SerializationState
      permits SkyValueRetriever.InitialQuery,
          SkyValueRetriever.WaitingForFutureValueBytes,
          SkyValueRetriever.WaitingForFutureLookupContinuation,
          SkyValueRetriever.WaitingForLookupContinuation,
          SkyValueRetriever.WaitingForFutureResult,
          SkyValueRetriever.RetrievedValue {}

  /** Return value of {@link #tryRetrieve}. */
  public static sealed interface RetrievalResult
      permits SkyValueRetriever.Restart,
          SkyValueRetriever.NoCachedData,
          SkyValueRetriever.RetrievedValue {}

  /**
   * Deserialization requires a Skyframe restart to proceed.
   *
   * <p>This may indicate that deserialization is blocked on an unavailable Skyframe value or
   * asynchronous I/O. When {@link #tryRetrieve} is called inside a {@link SkyFunction} returning
   * null from {@link SkyFunction#compute} is appropriate.
   */
  public enum Restart implements RetrievalResult {
    RESTART;
  }

  /**
   * There was no associated data in the cache for the requested key.
   *
   * <p>A typical client falls back on local computation upon seeing this.
   */
  public enum NoCachedData implements RetrievalResult {
    NO_CACHED_DATA;
  }

  /** The value was successfully retrieved. */
  public record RetrievedValue(SkyValue value) implements SerializationState, RetrievalResult {}

  /**
   * Attempts to retrieve the value associated with {@code key} from {@code
   * fingerprintValueService}.
   *
   * @return a {@link RetrievalResult} instance. This can be {@link NoCachedData} when there is no
   *     data associated with the given key.
   */
  public static RetrievalResult tryRetrieve(
      LookupEnvironment env,
      DependOnFutureShim futuresShim,
      ObjectCodecs codecs,
      FingerprintValueService fingerprintValueService,
      SkyKey key,
      SerializationStateProvider stateProvider)
      throws InterruptedException, SerializationException {
    SerializationState nextState = stateProvider.getSerializationState();
    try {
      while (true) {
        switch (nextState) {
          case InitialQuery unused:
            {
              SerializationResult<ByteString> keyBytes =
                  codecs.serializeMemoizedAndBlocking(
                      fingerprintValueService, key, /* profileCollector= */ null);
              ListenableFuture<Void> keyWriteStatus = keyBytes.getFutureToBlockWritesOn();
              if (keyWriteStatus != null) {
                // Nothing depends directly on the status of this write.
                Futures.addCallback(
                    keyWriteStatus, FutureHelpers.FAILURE_REPORTING_CALLBACK, directExecutor());
              }
              ListenableFuture<byte[]> valueBytes;
              try {
                valueBytes = fingerprintValueService.get(keyBytes.getObject());
              } catch (IOException e) {
                throw new SerializationException("key lookup failed for " + key, e);
              }
              nextState = new WaitingForFutureValueBytes(valueBytes);
              switch (futuresShim.dependOnFuture(valueBytes)) {
                case DONE:
                  break; // continues to the next state
                case NOT_DONE:
                  return Restart.RESTART;
              }
              break;
            }
          case WaitingForFutureValueBytes(ListenableFuture<byte[]> futureValueBytes):
            {
              byte[] valueBytes;
              try {
                valueBytes = getDone(futureValueBytes);
              } catch (ExecutionException e) {
                if (getRootCause(e) instanceof MissingFingerprintValueException) {
                  return NoCachedData.NO_CACHED_DATA;
                }
                throw new SerializationException("getting value bytes for " + key, e);
              }
              if (valueBytes.length == 0) {
                // Serialized representations are never empty in this protocol. Some implementations
                // use empty bytes to indicate missing data.
                return NoCachedData.NO_CACHED_DATA;
              }
              Object value =
                  codecs.deserializeWithSkyframe(
                      fingerprintValueService, ByteString.copyFrom(valueBytes));
              if (!(value instanceof ListenableFuture)) {
                nextState = new RetrievedValue((SkyValue) value);
                break;
              }

              @SuppressWarnings("unchecked")
              var futureContinuation = (ListenableFuture<SkyframeLookupContinuation>) value;
              nextState = new WaitingForFutureLookupContinuation(futureContinuation);
              switch (futuresShim.dependOnFuture(futureContinuation)) {
                case DONE:
                  break; // continues to the next state
                case NOT_DONE:
                  return Restart.RESTART;
              }
              break;
            }
          case WaitingForFutureLookupContinuation(
              ListenableFuture<SkyframeLookupContinuation> futureContinuation):
            // This state is transient. It discards the wrapping future before
            // WaitingForLookupContinuation so restarts from that state do not need repeat the
            // unwrapping.
            try {
              nextState = new WaitingForLookupContinuation(getDone(futureContinuation));
            } catch (ExecutionException e) {
              throw new SerializationException("waiting for all owned shared values for " + key, e);
            }
            break;
          case WaitingForLookupContinuation(SkyframeLookupContinuation continuation):
            {
              ListenableFuture<?> futureResult;
              try {
                futureResult = continuation.process(env); // only source of InterruptedException
              } catch (SkyframeDependencyException e) {
                throw new SerializationException(
                    "skyframe dependency error during deserialization for " + key, e);
              }
              if (futureResult == null) {
                return Restart.RESTART;
              }
              nextState = new WaitingForFutureResult(futureResult);
              switch (futuresShim.dependOnFuture(futureResult)) {
                case DONE:
                  break; // continues to the next state
                case NOT_DONE:
                  return Restart.RESTART;
              }
              break;
            }
          case WaitingForFutureResult(ListenableFuture<?> futureResult):
            try {
              nextState = new RetrievedValue((SkyValue) getDone(futureResult));
            } catch (ExecutionException e) {
              throw new SerializationException("waiting for deserialization result for " + key, e);
            }
            break;
          case RetrievedValue value:
            return value;
        }
      }
    } finally {
      stateProvider.setSerializationState(nextState);
    }
  }

  private enum InitialQuery implements SerializationState {
    INITIAL_QUERY;
  }

  @VisibleForTesting
  record WaitingForFutureValueBytes(ListenableFuture<byte[]> futureValueBytes)
      implements SerializationState {}

  @VisibleForTesting
  record WaitingForFutureLookupContinuation(
      ListenableFuture<SkyframeLookupContinuation> futureContinuation)
      implements SerializationState {}

  @VisibleForTesting
  record WaitingForLookupContinuation(SkyframeLookupContinuation continuation)
      implements SerializationState {}

  @VisibleForTesting
  record WaitingForFutureResult(ListenableFuture<?> futureResult) implements SerializationState {}

  private SkyValueRetriever() {}
}
