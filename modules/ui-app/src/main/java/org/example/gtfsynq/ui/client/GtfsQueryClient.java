package org.example.gtfsynq.ui.client;

import com.vaadin.flow.server.VaadinSession;
import com.vaadin.quarkus.annotation.VaadinServiceEnabled;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import org.example.gtfsynq.shared.protocol.query.TripMarkersReply;
import org.example.gtfsynq.shared.protocol.query.TripMarkersRequest;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdateRequest;
import org.example.gtfsynq.shared.protocol.query.TripUpdatesRequest;
import org.example.gtfsynq.shared.protocol.query.TripsRequest;
import org.example.gtfsynq.ui.i18n.UiI18nProvider;

/**
 * The console's only data source.
 *
 * <p>Every call is a unary gRPC request to {@code api-app}. The blocking stub is used because Vaadin
 * views need the data before rendering; Quarkus runs the servlet request on a worker thread.
 */
@ApplicationScoped
public class GtfsQueryClient {

    private static final long DEADLINE_SECONDS = 15;

    /**
     * Deadline for the marker query. It is the heaviest call - every positioned trip in one
     * unpaged reply - so it gets a shorter budget than the paged queries: abandoning it and
     * retrying on the next poll is better than leaving the map waiting. The statement timeout on
     * the API side is 15 s, so this deadline fires first and the view still gets an answer.
     */
    @ConfigProperty(name = "gtfsynq.ui.map.deadline-seconds", defaultValue = "10")
    long markerDeadlineSeconds = 10;

    @Inject
    @GrpcClient("gtfs-api")
    GtfsQueryServiceGrpc.GtfsQueryServiceBlockingStub stub;

    @Inject
    @VaadinServiceEnabled
    UiI18nProvider i18n;

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
        return staticFeeds(page, size, false);
    }

    public PagedResult<StaticFeed> staticFeeds(int page, int size, boolean includeRealtime) {
        return staticFeeds(page, size, includeRealtime, null, null, null, null, "asc");
    }

    public PagedResult<StaticFeed> staticFeeds(
            int page,
            int size,
            boolean includeRealtime,
            String feedId,
            String search,
            String kind,
            String sort,
            String direction) {
        var request = StaticFeedsRequest.newBuilder()
                .setPage(page)
                .setSize(size)
                .setIncludeRealtime(includeRealtime)
                .setSort(sort == null ? "" : sort)
                .setDirection(direction == null ? "asc" : direction);
        if (feedId != null) {
            request.setFeedId(feedId);
        }
        if (search != null && !search.isBlank()) {
            request.setSearch(search);
        }
        if (kind != null) {
            request.setKind(kind);
        }
        return call(() -> {
            var reply = stub().listStaticFeeds(request.build());
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
        if (query.route() != null) {
            request.setRoute(query.route());
        }
        if (query.routeType() != null) {
            request.setRouteType(query.routeType());
        }
        return call(() -> {
            var reply = stub().listTripUpdates(request.build());
            return page(reply.getItemsList(), reply.getPage());
        });
    }

    /** One live trip update by id, for opening the detail pane from a map marker. */
    public TripUpdate tripUpdate(long tripUpdateId) {
        return call(() -> stub().getTripUpdate(TripUpdateRequest.newBuilder()
                .setTripUpdateId(tripUpdateId)
                .build()));
    }

    /**
     * Every live trip that has a position, for the map; unpaged on purpose. The reply also says when
     * the API computed the snapshot, so the view can tell how old the cached markers are.
     */
    public TripMarkersReply tripMarkers() {
        return call(
                () -> stub(markerDeadlineSeconds).listTripMarkers(TripMarkersRequest.getDefaultInstance()),
                markerDeadlineSeconds);
    }

    /** The deadline the map's marker query runs under, so the view can bound its loading state. */
    public long markerDeadlineSeconds() {
        return markerDeadlineSeconds;
    }

    /** Stops of one live trip: static schedule merged with the live stop time updates. */
    public List<StopTimeUpdate> stopTimeUpdates(long tripUpdateId) {
        var request = StopTimeUpdatesRequest.newBuilder()
                .setTripUpdateId(tripUpdateId)
                .build();
        return call(() -> stub().listStopTimeUpdates(request).getItemsList());
    }

    private GtfsQueryServiceGrpc.GtfsQueryServiceBlockingStub stub() {
        return stub(DEADLINE_SECONDS);
    }

    private GtfsQueryServiceGrpc.GtfsQueryServiceBlockingStub stub(long deadlineSeconds) {
        return stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS);
    }

    private static <T> PagedResult<T> page(List<T> items, PageInfo info) {
        return new PagedResult<>(items, info.getTotal());
    }

    private <T> T call(Supplier<T> action) {
        return call(action, DEADLINE_SECONDS);
    }

    private <T> T call(Supplier<T> action, long deadlineSeconds) {
        try {
            return action.get();
        } catch (StatusRuntimeException e) {
            throw new DataAccessException(friendlyMessage(e, deadlineSeconds), e);
        }
    }

    private String friendlyMessage(StatusRuntimeException e, long deadlineSeconds) {
        return switch (e.getStatus().getCode()) {
            case UNAVAILABLE -> translate("error.api.unavailable");
            case DEADLINE_EXCEEDED -> translate("error.api.deadline", deadlineSeconds);
            case INVALID_ARGUMENT, NOT_FOUND, FAILED_PRECONDITION -> description(e, translate("error.api.rejected"));
            default -> translate("error.api.status", e.getStatus().getCode());
        };
    }

    /**
     * Translates a gRPC failure. Grid data providers fetch on background threads where Vaadin has
     * no current service or session; fall back to English there instead of throwing an
     * {@code I18NProvider} error that masks the real gRPC status.
     */
    private String translate(String key, Object... params) {
        var session = VaadinSession.getCurrent();
        var locale = session == null ? Locale.ENGLISH : session.getLocale();
        return i18n.getTranslation(key, locale, params);
    }

    private static String description(StatusRuntimeException e, String fallback) {
        var description = e.getStatus().getDescription();
        return description == null || description.isBlank() ? fallback : description;
    }
}
