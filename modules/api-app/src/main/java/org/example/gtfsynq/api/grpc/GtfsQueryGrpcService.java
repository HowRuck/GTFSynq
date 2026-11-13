package org.example.gtfsynq.api.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import java.util.function.Supplier;
import org.example.gtfsynq.api.repository.GtfsQueryRepository;
import org.example.gtfsynq.shared.protocol.query.AgencyNamesReply;
import org.example.gtfsynq.shared.protocol.query.AgencyNamesRequest;
import org.example.gtfsynq.shared.protocol.query.FeedIdsReply;
import org.example.gtfsynq.shared.protocol.query.FeedIdsRequest;
import org.example.gtfsynq.shared.protocol.query.GtfsQueryServiceGrpc;
import org.example.gtfsynq.shared.protocol.query.OverviewReply;
import org.example.gtfsynq.shared.protocol.query.OverviewRequest;
import org.example.gtfsynq.shared.protocol.query.RoutesReply;
import org.example.gtfsynq.shared.protocol.query.RoutesRequest;
import org.example.gtfsynq.shared.protocol.query.StaticFeedsReply;
import org.example.gtfsynq.shared.protocol.query.StaticFeedsRequest;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdatesReply;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdatesRequest;
import org.example.gtfsynq.shared.protocol.query.StopsReply;
import org.example.gtfsynq.shared.protocol.query.StopsRequest;
import org.example.gtfsynq.shared.protocol.query.TripMarkersReply;
import org.example.gtfsynq.shared.protocol.query.TripMarkersRequest;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdateRequest;
import org.example.gtfsynq.shared.protocol.query.TripUpdatesReply;
import org.example.gtfsynq.shared.protocol.query.TripUpdatesRequest;
import org.example.gtfsynq.shared.protocol.query.TripsReply;
import org.example.gtfsynq.shared.protocol.query.TripsRequest;

/**
 * gRPC facade over the read-only query repository.
 *
 * <p>The methods block on JDBC, so the service is annotated {@link Blocking} and runs on a worker
 * thread instead of the Vert.x event loop.
 */
@GrpcService
@Blocking
public class GtfsQueryGrpcService extends GtfsQueryServiceGrpc.GtfsQueryServiceImplBase {

    /**
     * The map polls on a minute cadence but several sessions may poll together, and the marker reply
     * is large. The snapshot is therefore computed in the background and served from memory.
     */
    private final MarkerSnapshot markers;

    private final GtfsQueryRepository repository;

    @Inject
    public GtfsQueryGrpcService(GtfsQueryRepository repository, MarkerSnapshot markers) {
        this.repository = repository;
        this.markers = markers;
    }

    @Override
    public void getOverview(OverviewRequest request, StreamObserver<OverviewReply> observer) {
        unary(observer, () -> GtfsQueryMapper.toReply(repository.overview()));
    }

    @Override
    public void listFeedIds(FeedIdsRequest request, StreamObserver<FeedIdsReply> observer) {
        unary(
                observer,
                () -> FeedIdsReply.newBuilder()
                        .addAllFeedIds(repository.feedIds())
                        .build());
    }

    @Override
    public void listAgencyNames(AgencyNamesRequest request, StreamObserver<AgencyNamesReply> observer) {
        unary(
                observer,
                () -> AgencyNamesReply.newBuilder()
                        .addAllAgencyNames(repository.agencyNames(request.hasFeedId() ? request.getFeedId() : null))
                        .build());
    }

    @Override
    public void listStaticFeeds(StaticFeedsRequest request, StreamObserver<StaticFeedsReply> observer) {
        unary(
                observer,
                () -> GtfsQueryMapper.staticFeedsReply(
                        repository.staticFeeds(request.getPage(), request.getSize(), request.getIncludeRealtime())));
    }

    @Override
    public void listRoutes(RoutesRequest request, StreamObserver<RoutesReply> observer) {
        unary(
                observer,
                () -> GtfsQueryMapper.routesReply(repository.routes(
                        request.getFeedId(),
                        request.getSearch(),
                        request.hasRouteType() ? request.getRouteType() : null,
                        request.getPage(),
                        request.getSize(),
                        request.getSort(),
                        request.getDirection())));
    }

    @Override
    public void listStops(StopsRequest request, StreamObserver<StopsReply> observer) {
        unary(
                observer,
                () -> GtfsQueryMapper.stopsReply(repository.stops(
                        request.getFeedId(),
                        request.getSearch(),
                        request.hasLocationType() ? request.getLocationType() : null,
                        request.getPage(),
                        request.getSize(),
                        request.getSort(),
                        request.getDirection())));
    }

    @Override
    public void listTrips(TripsRequest request, StreamObserver<TripsReply> observer) {
        unary(
                observer,
                () -> GtfsQueryMapper.tripsReply(repository.trips(
                        request.getFeedId(),
                        request.getSearch(),
                        request.hasDirectionId() ? request.getDirectionId() : null,
                        request.getPage(),
                        request.getSize(),
                        request.getSort(),
                        request.getDirection())));
    }

    @Override
    public void listTripUpdates(TripUpdatesRequest request, StreamObserver<TripUpdatesReply> observer) {
        unary(
                observer,
                () -> GtfsQueryMapper.tripUpdatesReply(repository.liveTripUpdates(
                        request.getFeedId(),
                        request.getSearch(),
                        request.hasAgency() ? request.getAgency() : null,
                        request.hasRoute() ? request.getRoute() : null,
                        request.hasRouteType() ? request.getRouteType() : null,
                        request.getPage(),
                        request.getSize(),
                        request.getSort(),
                        request.getDirection(),
                        request.getCount())));
    }

    @Override
    public void getTripUpdate(TripUpdateRequest request, StreamObserver<TripUpdate> observer) {
        unary(
                observer,
                () -> repository
                        .liveTripUpdate(request.getTripUpdateId())
                        .map(GtfsQueryMapper::tripUpdate)
                        .orElseThrow(() -> Status.NOT_FOUND
                                .withDescription("Trip update " + request.getTripUpdateId() + " is no longer live")
                                .asRuntimeException()));
    }

    @Override
    public void listTripMarkers(TripMarkersRequest request, StreamObserver<TripMarkersReply> observer) {
        unary(observer, markers::current);
    }

    @Override
    public void listStopTimeUpdates(StopTimeUpdatesRequest request, StreamObserver<StopTimeUpdatesReply> observer) {
        unary(
                observer,
                () -> GtfsQueryMapper.stopTimeUpdatesReply(repository.liveStopTimeUpdates(request.getTripUpdateId())));
    }

    /**
     * Runs a blocking JDBC query and surfaces failures as a gRPC error instead of dropping the
     * stream.
     */
    private static <T> void unary(StreamObserver<T> observer, Supplier<T> query) {
        try {
            observer.onNext(query.get());
            observer.onCompleted();
        } catch (StatusRuntimeException e) {
            // A query can choose its own status, for example NOT_FOUND for a row that just expired.
            observer.onError(e);
        } catch (RuntimeException e) {
            observer.onError(Status.INTERNAL
                    .withDescription(e.getMessage() == null ? "Query failed" : e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }
}
