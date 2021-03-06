package org.hillview.remoting;

import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hillview.dataset.api.IDataSet;
import org.hillview.dataset.api.PartialResult;
import org.hillview.pb.Ack;
import org.hillview.pb.Command;
import org.hillview.pb.HillviewServerGrpc;
import org.hillview.pb.PartialResponse;
import org.hillview.utils.Converters;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Server that transfers map(), sketch(), zip() and unsubscribe() RPCs from a RemoteDataSet
 * object to locally managed IDataSet objects, and streams back results.
 *
 * If memoization is enabled, it caches the results of (operation, dataset-index) types.
 */
public class HillviewServer extends HillviewServerGrpc.HillviewServerImplBase {
    private static final Logger LOG = Logger.getLogger(HillviewServer.class.getName());
    public static final int DEFAULT_IDS_INDEX = 1;
    public static final int DEFAULT_PORT = 3569;
    private static final String LOCALHOST = "127.0.0.1";
    private static final int NUM_THREADS = 5;
    public static final int MAX_MESSAGE_SIZE = 20971520;
    private static final Executor EXECUTOR = Executors.newFixedThreadPool(NUM_THREADS);
    private final Server server;
    private final AtomicInteger dsIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, IDataSet> dataSets;
    private final ConcurrentHashMap<UUID, Subscription> operationToObservable
            = new ConcurrentHashMap<>();
    private final HostAndPort listenAddress;
    private final ConcurrentHashMap<ByteString, Map<Integer, PartialResponse>> memoizedCommands
            = new ConcurrentHashMap<>();
    @SuppressWarnings("CanBeFinal")
    private boolean MEMOIZE = true;

    public HillviewServer(final HostAndPort listenAddress, final IDataSet dataSet) throws IOException {
        this.listenAddress = listenAddress;
        this.server = NettyServerBuilder.forAddress(new InetSocketAddress(listenAddress.getHost(),
                                                                     listenAddress.getPort()))
                                        .executor(EXECUTOR)
                                        .addService(this)
                                        .maxMessageSize(MAX_MESSAGE_SIZE)
                                        .build()
                                        .start();
        this.dataSets = new ConcurrentHashMap<>();
        this.dataSets.put(this.dsIndex.incrementAndGet(), dataSet);
    }

    private Subscriber<PartialResult<IDataSet>> createSubscriber(final Command command,
            final  UUID id, final StreamObserver<PartialResponse> responseObserver) {
        return new Subscriber<PartialResult<IDataSet>>() {
            @Nullable private PartialResponse memoizedResult = null;

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
                HillviewServer.this.operationToObservable.remove(id);
                if (MEMOIZE && this.memoizedResult != null) {
                    HillviewServer.this.memoizedCommands.computeIfAbsent(command.getSerializedOp(),
                                                     (k) -> new ConcurrentHashMap<>())
                                    .put(command.getIdsIndex(), this.memoizedResult);
                }
            }

            @Override
            public void onError(final Throwable e) {
                e.printStackTrace();
                responseObserver.onError(asStatusRuntimeException(e));
                HillviewServer.this.operationToObservable.remove(id);
            }

            @Override
            public void onNext(final PartialResult<IDataSet> pr) {
                Integer idsIndex = null;
                if (pr.deltaValue != null) {
                    idsIndex = HillviewServer.this.dsIndex.incrementAndGet();
                    HillviewServer.this.dataSets.put(idsIndex, Converters.checkNull(pr.deltaValue));
                }
                final OperationResponse<Integer> res = new OperationResponse<Integer>(idsIndex);
                final byte[] bytes = SerializationUtils.serialize(res);
                final PartialResponse result = PartialResponse.newBuilder()
                        .setSerializedOp(ByteString.copyFrom(bytes)).build();
                responseObserver.onNext(result);
                if (MEMOIZE) {
                    this.memoizedResult = result;
                }
            }
        };
    }

    /**
     * Implementation of map() service in hillview.proto.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void map(final Command command, final StreamObserver<PartialResponse> responseObserver) {
        try {
            if (!this.checkValidIdsIndex(command.getIdsIndex(), responseObserver)) {
                return;
            }
            final byte[] bytes = command.getSerializedOp().toByteArray();
            if (this.respondIfReplyIsMemoized(command, responseObserver)) {
                LOG.info("Returning memoized result for map operation against IDataSet#" + command.getIdsIndex());
                return;
            }

            final MapOperation mapOp = SerializationUtils.deserialize(bytes);
            final UUID commandId = new UUID(command.getHighId(), command.getLowId());
            final Observable<PartialResult<IDataSet>> observable =
                    this.dataSets.get(command.getIdsIndex())
                                 .map(mapOp.mapper);
            final Subscription sub = observable.subscribe(this.createSubscriber(command, commandId, responseObserver));
            this.operationToObservable.put(commandId, sub);
        } catch (final Exception e) {
            e.printStackTrace();
            responseObserver.onError(asStatusRuntimeException(e));
        }
    }

    /**
     * Implementation of flatMap() service in hillview.proto.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void flatMap(final Command command, final StreamObserver<PartialResponse>
            responseObserver) {
        try {
            if (!this.checkValidIdsIndex(command.getIdsIndex(), responseObserver)) {
                return;
            }
            final byte[] bytes = command.getSerializedOp().toByteArray();

            if (this.respondIfReplyIsMemoized(command, responseObserver)) {
                LOG.info("Returning memoized result for flatMap operation against IDataSet#" + command.getIdsIndex());
                return;
            }
            final FlatMapOperation mapOp = SerializationUtils.deserialize(bytes);
            final UUID commandId = new UUID(command.getHighId(), command.getLowId());
            final Observable<PartialResult<IDataSet>> observable =
                    this.dataSets.get(command.getIdsIndex())
                            .flatMap(mapOp.mapper);
            final Subscription sub = observable.subscribe(this.createSubscriber(command,
                                                                                commandId, responseObserver));
            this.operationToObservable.put(commandId, sub);
        } catch (final Exception e) {
            e.printStackTrace();
            responseObserver.onError(asStatusRuntimeException(e));
        }
    }

    /**
     * Implementation of sketch() service in hillview.proto.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void sketch(final Command command, final StreamObserver<PartialResponse> responseObserver) {
        try {
            if (!this.checkValidIdsIndex(command.getIdsIndex(), responseObserver)) {
                return;
            }
            if (this.respondIfReplyIsMemoized(command, responseObserver)) {
                LOG.info("Returning memoized result for sketch operation against IDataSet#" + command.getIdsIndex());
                return;
            }
            final byte[] bytes = command.getSerializedOp().toByteArray();
            final SketchOperation sketchOp = SerializationUtils.deserialize(bytes);
            final Observable<PartialResult> observable = this.dataSets.get(command.getIdsIndex())
                                                                      .sketch(sketchOp.sketch);
            final UUID commandId = new UUID(command.getHighId(), command.getLowId());
            final Subscription sub = observable.subscribe(new Subscriber<PartialResult>() {
                @Nullable private Object sketchResultAccumulator = sketchOp.sketch.getZero();

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                    HillviewServer.this.operationToObservable.remove(commandId);

                    if (MEMOIZE && this.sketchResultAccumulator != null) {
                        final OperationResponse<PartialResult> res =
                                new OperationResponse<PartialResult>(new PartialResult(1.0, this.sketchResultAccumulator));
                        final byte[] bytes = SerializationUtils.serialize(res);
                        final PartialResponse memoizedResult = PartialResponse.newBuilder()
                                .setSerializedOp(ByteString.copyFrom(bytes))
                                .build();
                        HillviewServer.this.memoizedCommands.computeIfAbsent(command.getSerializedOp(),
                                (k) -> new ConcurrentHashMap<>())
                                .put(command.getIdsIndex(), memoizedResult);
                    }
                }

                @Override
                public void onError(final Throwable e) {
                    e.printStackTrace();
                    responseObserver.onError(asStatusRuntimeException(e));
                    HillviewServer.this.operationToObservable.remove(commandId);
                }

                @Override
                public void onNext(final PartialResult pr) {
                    this.sketchResultAccumulator = sketchOp.sketch.add(this.sketchResultAccumulator, pr.deltaValue);
                    final OperationResponse<PartialResult> res =
                            new OperationResponse<PartialResult>(pr);
                    final byte[] bytes = SerializationUtils.serialize(res);
                    responseObserver.onNext(PartialResponse.newBuilder()
                                                           .setSerializedOp(ByteString.copyFrom(bytes))
                                                           .build());
                }
            });
            this.operationToObservable.put(commandId, sub);
        } catch (final Exception e) {
            e.printStackTrace();
            responseObserver.onError(asStatusRuntimeException(e));
        }
    }

    /**
     * Implementation of zip() service in hillview.proto.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void zip(final Command command, final StreamObserver<PartialResponse> responseObserver) {
        try {
            final byte[] bytes = command.getSerializedOp().toByteArray();
            final ZipOperation zipOp = SerializationUtils.deserialize(bytes);
            if (!this.checkValidIdsIndex(command.getIdsIndex(), responseObserver)
                    || !this.checkValidIdsIndex(zipOp.datasetIndex, responseObserver)) {
                return;
            }
            if (this.respondIfReplyIsMemoized(command, responseObserver)) {
                LOG.info("Returning memoized result for zip operation against IDataSet#" + command.getIdsIndex());
                return;
            }

            final IDataSet other = this.dataSets.get(zipOp.datasetIndex);
            final Observable<PartialResult<IDataSet>> observable =
                    this.dataSets.get(command.getIdsIndex())
                                 .zip(other);
            final UUID commandId = new UUID(command.getHighId(), command.getLowId());
            final Subscription sub = observable.subscribe(
                    this.createSubscriber(command, commandId, responseObserver));
            this.operationToObservable.put(commandId, sub);
        } catch (final Exception e) {
            e.printStackTrace();
            responseObserver.onError(asStatusRuntimeException(e));
        }
    }

    /**
     * Implementation of unsubscribe() service in hillview.proto.
     */
    @Override
    public void unsubscribe(final Command command, final StreamObserver<Ack> responseObserver) {
        try {
            final byte[] bytes = command.getSerializedOp().toByteArray();
            final UnsubscribeOperation unsubscribeOp = SerializationUtils.deserialize(bytes);
            final Subscription subscription = this.operationToObservable.remove(unsubscribeOp.id);
            if (subscription != null) {
                subscription.unsubscribe();
            }
        } catch (final Exception e) {
            LOG.warning(e.getMessage());
            responseObserver.onError(asStatusRuntimeException(e));
        }
    }

    /**
     * Purges all memoized results
     */
    public void purgeCache() {
        this.memoizedCommands.clear();
    }

    /**
     * shutdown RPC server
     */
    public void shutdown() {
        this.server.shutdown();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkValidIdsIndex(final int index,
                                       final StreamObserver<PartialResponse> observer) {
        if (!this.dataSets.containsKey(index)) {
            observer.onError(asStatusRuntimeException(new RuntimeException("Object with index does not exist: "
                    + index + " " + this.listenAddress)));
            return false;
        }
        return true;
    }

    /**
     * Respond with a memoized result if it is available.
     */
    private boolean respondIfReplyIsMemoized(final Command command,
                                             final StreamObserver<PartialResponse> responseObserver) {
        if (MEMOIZE && this.memoizedCommands.containsKey(command.getSerializedOp())
             && this.memoizedCommands.get(command.getSerializedOp()).containsKey(command.getIdsIndex())) {
            responseObserver.onNext(this.memoizedCommands.get(command.getSerializedOp()).get(command.getIdsIndex()));
            responseObserver.onCompleted();
            return true;
        }
        return false;
    }

    /**
     * Helper method to propagate exceptions via gRPC
     */
    private StatusRuntimeException asStatusRuntimeException(final Throwable e) {
        final String stackTrace = ExceptionUtils.getStackTrace(e);
        return Status.INTERNAL.withDescription(stackTrace).asRuntimeException();
    }
}
