package org.example.gtfsynq.api.grpc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.example.gtfsynq.api.dto.OverviewDto;
import org.example.gtfsynq.api.dto.PageResponse;
import org.example.gtfsynq.api.dto.RouteDto;
import org.example.gtfsynq.api.dto.StaticFeedDto;
import org.example.gtfsynq.api.dto.StopDto;
import org.example.gtfsynq.api.dto.StopTimeUpdateDto;
import org.example.gtfsynq.api.dto.TripDto;
import org.example.gtfsynq.api.dto.TripMarkerDto;
import org.example.gtfsynq.api.dto.TripUpdateDto;
import org.example.gtfsynq.shared.protocol.query.OverviewReply;
import org.example.gtfsynq.shared.protocol.query.PageInfo;
import org.example.gtfsynq.shared.protocol.query.Route;
import org.example.gtfsynq.shared.protocol.query.RoutesReply;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.shared.protocol.query.StaticFeedsReply;
import org.example.gtfsynq.shared.protocol.query.Stop;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdatesReply;
import org.example.gtfsynq.shared.protocol.query.StopsReply;
import org.example.gtfsynq.shared.protocol.query.Trip;
import org.example.gtfsynq.shared.protocol.query.TripMarker;
import org.example.gtfsynq.shared.protocol.query.TripMarkersReply;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdatesReply;
import org.example.gtfsynq.shared.protocol.query.TripsReply;

/** Maps the read model returned by {@code GtfsQueryRepository} onto the gRPC contract. */
final class GtfsQueryMapper {

    private GtfsQueryMapper() {}

    static OverviewReply toReply(OverviewDto dto) {
        return OverviewReply.newBuilder()
                .setStaticFeeds(dto.staticFeeds())
                .setRoutes(dto.routes())
                .setStops(dto.stops())
                .setTrips(dto.trips())
                .setLiveTripUpdates(dto.liveTripUpdates())
                .setLiveStopTimeUpdates(dto.liveStopTimeUpdates())
                .build();
    }

    static StaticFeedsReply staticFeedsReply(PageResponse<StaticFeedDto> page) {
        var reply = StaticFeedsReply.newBuilder().setPage(pageInfo(page));
        for (var feed : page.items()) {
            var item = StaticFeed.newBuilder()
                    .setFeedId(feed.feedId())
                    .setSha256(feed.sha256())
                    .setBucket(feed.bucket())
                    .setObjectKey(feed.objectKey())
                    .setSizeBytes(feed.sizeBytes())
                    .setIngestedAt(timestamp(feed.ingestedAt()))
                    .setLoadedAt(timestamp(feed.loadedAt()))
                    .addAllAgencyNames(feed.agencyNames());
            setIfPresent(feed.sourceUrl(), item::setSourceUrl);
            reply.addItems(item);
        }
        return reply.build();
    }

    static RoutesReply routesReply(PageResponse<RouteDto> page) {
        var reply = RoutesReply.newBuilder().setPage(pageInfo(page));
        for (var route : page.items()) {
            var item = Route.newBuilder()
                    .setFeedId(route.feedId())
                    .setRouteId(route.routeId())
                    .setRouteType(route.routeType());
            setIfPresent(route.shortName(), item::setShortName);
            setIfPresent(route.longName(), item::setLongName);
            setIfPresent(route.color(), item::setColor);
            setIfPresent(route.textColor(), item::setTextColor);
            reply.addItems(item);
        }
        return reply.build();
    }

    static StopsReply stopsReply(PageResponse<StopDto> page) {
        var reply = StopsReply.newBuilder().setPage(pageInfo(page));
        for (var stop : page.items()) {
            var item = Stop.newBuilder()
                    .setFeedId(stop.feedId())
                    .setStopId(stop.stopId())
                    .setStopName(stop.stopName());
            if (stop.lat() != null) {
                item.setLat(stop.lat());
            }
            if (stop.lon() != null) {
                item.setLon(stop.lon());
            }
            if (stop.locationType() != null) {
                item.setLocationType(stop.locationType());
            }
            setIfPresent(stop.parentStation(), item::setParentStation);
            reply.addItems(item);
        }
        return reply.build();
    }

    static TripsReply tripsReply(PageResponse<TripDto> page) {
        var reply = TripsReply.newBuilder().setPage(pageInfo(page));
        for (var trip : page.items()) {
            var item = Trip.newBuilder()
                    .setFeedId(trip.feedId())
                    .setTripId(trip.tripId())
                    .setRouteId(trip.routeId())
                    .setServiceId(trip.serviceId());
            if (trip.directionId() != null) {
                item.setDirectionId(trip.directionId());
            }
            reply.addItems(item);
        }
        return reply.build();
    }

    static TripUpdatesReply tripUpdatesReply(PageResponse<TripUpdateDto> page) {
        var reply = TripUpdatesReply.newBuilder().setPage(pageInfo(page));
        for (var update : page.items()) {
            var item = TripUpdate.newBuilder()
                    .setTripUpdateId(update.tripUpdateId())
                    .setFeedId(update.feedId())
                    .setFeedTs(timestamp(update.feedTs()))
                    .setLastSeenAt(timestamp(update.lastSeenAt()))
                    .setStopTimeUpdateCount(update.stopTimeUpdateCount());
            setIfPresent(update.entityId(), item::setEntityId);
            setIfPresent(update.tripId(), item::setTripId);
            setIfPresent(update.routeId(), item::setRouteId);
            setIfPresent(timeText(update.startDate()), item::setStartDate);
            setIfPresent(timeText(update.startTime()), item::setStartTime);
            setIfPresent(update.currentDelay(), item::setCurrentDelay);
            setIfPresent(update.tripShortName(), item::setTripShortName);
            setIfPresent(update.routeShortName(), item::setRouteShortName);
            setIfPresent(update.routeLongName(), item::setRouteLongName);
            setIfPresent(update.agencyName(), item::setAgencyName);
            setIfPresent(update.routeType(), item::setRouteType);
            if (update.latestStopLat() != null) {
                item.setLatestStopLat(update.latestStopLat());
            }
            if (update.latestStopLon() != null) {
                item.setLatestStopLon(update.latestStopLon());
            }
            setIfPresent(update.latestStopName(), item::setLatestStopName);
            reply.addItems(item);
        }
        return reply.build();
    }

    static TripMarkersReply tripMarkersReply(List<TripMarkerDto> markers) {
        var reply = TripMarkersReply.newBuilder();
        for (var marker : markers) {
            var item = TripMarker.newBuilder().setLat(marker.lat()).setLon(marker.lon());
            setIfPresent(marker.routeShortName(), item::setRouteShortName);
            setIfPresent(marker.tripShortName(), item::setTripShortName);
            setIfPresent(marker.routeId(), item::setRouteId);
            setIfPresent(marker.tripId(), item::setTripId);
            setIfPresent(marker.routeType(), item::setRouteType);
            setIfPresent(marker.stopName(), item::setStopName);
            setIfPresent(marker.delaySeconds(), item::setDelaySeconds);
            setIfPresent(marker.reachedAt(), reached -> item.setReachedAt(timestamp(reached)));
            reply.addItems(item);
        }
        return reply.build();
    }

    static StopTimeUpdatesReply stopTimeUpdatesReply(List<StopTimeUpdateDto> stopTimes) {
        var reply = StopTimeUpdatesReply.newBuilder();
        for (var stopTime : stopTimes) {
            var item = StopTimeUpdate.newBuilder()
                    .setTripUpdateId(stopTime.tripUpdateId())
                    .setStopSequence(stopTime.stopSequence());
            setIfPresent(stopTime.stopId(), item::setStopId);
            setIfPresent(stopTime.arrivalTime(), arrival -> item.setArrivalTime(timestamp(arrival)));
            setIfPresent(stopTime.arrivalDelay(), item::setArrivalDelay);
            setIfPresent(
                    stopTime.scheduledArrivalTime(), scheduled -> item.setScheduledArrivalTime(timestamp(scheduled)));
            setIfPresent(stopTime.departureTime(), departure -> item.setDepartureTime(timestamp(departure)));
            setIfPresent(stopTime.departureDelay(), item::setDepartureDelay);
            setIfPresent(
                    stopTime.scheduledDepartureTime(),
                    scheduled -> item.setScheduledDepartureTime(timestamp(scheduled)));
            setIfPresent(stopTime.scheduleRelationship(), item::setScheduleRelationship);
            setIfPresent(stopTime.assignedStopId(), item::setAssignedStopId);
            setIfPresent(stopTime.stopName(), item::setStopName);
            if (stopTime.lat() != null) {
                item.setStopLat(stopTime.lat());
            }
            if (stopTime.lon() != null) {
                item.setStopLon(stopTime.lon());
            }
            setIfPresent(stopTime.lastSeenAt(), seen -> item.setLastSeenAt(timestamp(seen)));
            item.setRealtime(stopTime.realtime());
            reply.addItems(item);
        }
        return reply.build();
    }

    private static PageInfo pageInfo(PageResponse<?> page) {
        return PageInfo.newBuilder()
                .setPage(page.page())
                .setSize(page.size())
                .setTotal(page.total())
                .setTotalPages(page.totalPages())
                .build();
    }

    private static com.google.protobuf.Timestamp timestamp(OffsetDateTime value) {
        if (value == null) {
            return com.google.protobuf.Timestamp.getDefaultInstance();
        }
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(value.toEpochSecond())
                .setNanos(value.getNano())
                .build();
    }

    private static String timeText(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String timeText(LocalTime value) {
        return value == null
                ? null
                : String.format("%02d:%02d:%02d", value.getHour(), value.getMinute(), value.getSecond());
    }

    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
