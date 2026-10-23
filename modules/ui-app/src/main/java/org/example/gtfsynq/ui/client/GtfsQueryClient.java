package org.example.gtfsynq.ui.client;

import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.example.gtfsynq.shared.protocol.query.FeedIdsRequest;
import org.example.gtfsynq.shared.protocol.query.GtfsQueryServiceGrpc;
import org.example.gtfsynq.shared.protocol.query.OverviewReply;
import org.example.gtfsynq.shared.protocol.query.OverviewRequest;
import org.example.gtfsynq.shared.protocol.query.PageInfo;
import org.example.gtfsynq.shared.protocol.query.Route;
import org.example.gtfsynq.shared.protocol.query.RoutesRequest;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.shared.protocol.query.StaticFeedsRequest;
import org.example.gtfsynq.shared.protocol.query.Stop;
import org.example.gtfsynq.shared.protocol.query.StopsRequest;
import org.example.gtfsynq.shared.protocol.query.Trip;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdatesRequest;
import org.example.gtfsynq.shared.protocol.query.TripsRequest;

/**
 * The console's only data source.
 *
 * <p>Every call is a unary gRPC request to {@code api-app}. The blocking stub is used because Vaadin
 * views need the data before rendering; Quarkus runs the servlet request on a worker thread.
 */
@ApplicationScoped
public class GtfsQueryClient {

    private static final long DEADLINE_SECONDS = 15;

    @Inject
    @GrpcClient("gtfs-api")
    GtfsQueryServiceGrpc.GtfsQueryServiceBlockingStub stub;

    public OverviewReply overview() {
        return call(() -> stub().getOverview(OverviewRequest.getDefaultInstance()));
    }

    public List<String> feedIds() {
        return call(
                () -> stub().listFeedIds(FeedIdsRequest.getDefaultInstance()).getFeedIdsList());
    }

    public PagedResult<StaticFeed> staticFeeds(int page, int size) {
        var request =
                StaticFeedsRequest.newBuilder().setPage(page).setSize(size).build();
        return call(() -> {
            var reply = stub().listStaticFeeds(request);
            return page(reply.getItemsList(), reply.getPage());
        });
    }

    public PagedResult<Route> routes(PageQuery query) {
        var request = RoutesRequest.newBuilder()
                .setSearch(query.search())
                .setPage(query.page())
                .setSize(query.size())
                .setSort(query.sort())
                .setDirection(query.direction());
        if (query.feedId() != null) {
            request.setFeedId(query.feedId());
        }
        return call(() -> {
            var reply = stub().listRoutes(request.build());
            return page(reply.getItemsList(), reply.getPage());
        });
    }

    public PagedResult<Stop> stops(PageQuery query) {
        var request = StopsRequest.newBuilder()
                .setSearch(query.search())
                .setPage(query.page())
                .setSize(query.size())
                .setSort(query.sort())
                .setDirection(query.direction());
        if (query.feedId() != null) {
            request.setFeedId(query.feedId());
        }
        return call(() -> {
            var reply = stub().listStops(request.build());
            return page(reply.getItemsList(), reply.getPage());
        });
    }

    public PagedResult<Trip> trips(PageQuery query) {
        var request = TripsRequest.newBuilder()
                .setSearch(query.search())
                .setPage(query.page())
                .setSize(query.size())
                .setSort(query.sort())
                .setDirection(query.direction());
        if (query.feedId() != null) {
            request.setFeedId(query.feedId());
        }
        return call(() -> {
            var reply = stub().listTrips(request.build());
            return page(reply.getItemsList(), reply.getPage());
        });
    }

    public PagedResult<TripUpdate> tripUpdates(PageQuery query) {
        var request = TripUpdatesRequest.newBuilder()
                .setPage(query.page())
                .setSize(query.size())
                .setSort(query.sort())
                .setDirection(query.direction())
                .setCount(true);
        if (query.feedId() != null) {
            request.setFeedId(query.feedId());
        }
        return call(() -> {
            var reply = stub().listTripUpdates(request.build());
            return page(reply.getItemsList(), reply.getPage());
        });
    }

    private GtfsQueryServiceGrpc.GtfsQueryServiceBlockingStub stub() {
        return stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    private static <T> PagedResult<T> page(List<T> items, PageInfo info) {
        return new PagedResult<>(items, info.getTotal());
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (StatusRuntimeException e) {
            throw new DataAccessException(friendlyMessage(e), e);
        }
    }

    private static String friendlyMessage(StatusRuntimeException e) {
        return switch (e.getStatus().getCode()) {
            case UNAVAILABLE -> "The API service is unavailable. Is api-app running?";
            case DEADLINE_EXCEEDED -> "The API service did not answer within " + DEADLINE_SECONDS + " seconds.";
            case INVALID_ARGUMENT, NOT_FOUND, FAILED_PRECONDITION -> description(e, "The API rejected the query.");
            default -> "The API service returned " + e.getStatus().getCode();
        };
    }

    private static String description(StatusRuntimeException e, String fallback) {
        var description = e.getStatus().getDescription();
        return description == null || description.isBlank() ? fallback : description;
    }
}
