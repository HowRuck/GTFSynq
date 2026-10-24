package org.example.gtfsynq.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.example.gtfsynq.api.dto.PageResponse;
import org.example.gtfsynq.api.dto.RouteDto;
import org.example.gtfsynq.api.dto.StaticFeedDto;
import org.example.gtfsynq.api.dto.StopDto;
import org.example.gtfsynq.api.dto.TripDto;
import org.example.gtfsynq.api.repository.GtfsQueryRepository;

@Path("/api/static")
@Produces(MediaType.APPLICATION_JSON)
public class StaticResource {

    private final GtfsQueryRepository repository;

    @Inject
    public StaticResource(GtfsQueryRepository repository) {
        this.repository = repository;
    }

    @GET
    @Path("/feeds")
    public PageResponse<StaticFeedDto> feeds(
            @QueryParam("page") @DefaultValue("0") int page, @QueryParam("size") @DefaultValue("25") int size) {
        return repository.staticFeeds(page, size);
    }

    @GET
    @Path("/routes")
    public PageResponse<RouteDto> routes(
            @QueryParam("feedId") String feedId,
            @QueryParam("search") @DefaultValue("") String search,
            @QueryParam("routeType") Integer routeType,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("25") int size,
            @QueryParam("sort") @DefaultValue("routeId") String sort,
            @QueryParam("dir") @DefaultValue("asc") String direction) {
        return repository.routes(feedId, search, routeType, page, size, sort, direction);
    }

    @GET
    @Path("/stops")
    public PageResponse<StopDto> stops(
            @QueryParam("feedId") String feedId,
            @QueryParam("search") @DefaultValue("") String search,
            @QueryParam("locationType") Integer locationType,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("25") int size,
            @QueryParam("sort") @DefaultValue("stopName") String sort,
            @QueryParam("dir") @DefaultValue("asc") String direction) {
        return repository.stops(feedId, search, locationType, page, size, sort, direction);
    }

    @GET
    @Path("/trips")
    public PageResponse<TripDto> trips(
            @QueryParam("feedId") String feedId,
            @QueryParam("search") @DefaultValue("") String search,
            @QueryParam("directionId") Integer directionId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("25") int size,
            @QueryParam("sort") @DefaultValue("tripId") String sort,
            @QueryParam("dir") @DefaultValue("asc") String direction) {
        return repository.trips(feedId, search, directionId, page, size, sort, direction);
    }
}
