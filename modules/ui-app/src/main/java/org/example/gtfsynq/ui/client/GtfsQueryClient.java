package org.example.gtfsynq.ui.client;

import com.vaadin.flow.i18n.I18NProvider;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.example.gtfsynq.shared.protocol.query.AgencyNamesRequest;
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
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdatesRequest;
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

    /** Agency names, optionally limited to one feed. */
    public List<String> agencyNames(String feedId) {
        var request = AgencyNamesRequest.newBuilder();
        if (feedId != null && !feedId.isBlank()) {
            request.setFeedId(feedId);
        }
        return call(() -> stub().listAgencyNames(request.build()).getAgencyNamesList());
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
        if (query.routeType() != null) {
            request.setRouteType(query.routeType());
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
        if (query.locationType() != null) {
            request.setLocationType(query.locationType());
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
        if (query.directionId() != null) {
            request.setDirectionId(query.directionId());
        }
        return call(() -> {
            var reply = stub().listTrips(request.build());
            return page(reply.getItemsList(), reply.getPage());
        });
    }

    public PagedResult<TripUpdate> tripUpdates(PageQuery query) {
        var request = TripUpdatesRequest.newBuilder()
                .setSearch(query.search())
                .setPage(query.page())
                .setSize(query.size())
                .setSort(query.sort())
                .setDirection(query.direction())
                .setCount(true);
        if (query.feedId() != null) {
            request.setFeedId(query.feedId());
        }
        if (query.agency() != null) {
            request.setAgency(query.agency());
        }
        return call(() -> {
            var reply = stub().listTripUpdates(request.build());
            return page(reply.getItemsList(), reply.getPage());
        });
    }

    /** Stops of one live trip: static schedule merged with the live stop time updates. */
    public List<StopTimeUpdate> stopTimeUpdates(long tripUpdateId) {
        var request = StopTimeUpdatesRequest.newBuilder()
                .setTripUpdateId(tripUpdateId)
                .build();
        return call(() -> stub().listStopTimeUpdates(request).getItemsList());
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
            case UNAVAILABLE -> I18NProvider.translate("error.api.unavailable");
            case DEADLINE_EXCEEDED -> I18NProvider.translate("error.api.deadline", DEADLINE_SECONDS);
            case INVALID_ARGUMENT, NOT_FOUND, FAILED_PRECONDITION ->
                description(e, I18NProvider.translate("error.api.rejected"));
            default -> I18NProvider.translate("error.api.status", e.getStatus().getCode());
        };
    }

    private static String description(StatusRuntimeException e, String fallback) {
        var description = e.getStatus().getDescription();
        return description == null || description.isBlank() ? fallback : description;
    }
}
