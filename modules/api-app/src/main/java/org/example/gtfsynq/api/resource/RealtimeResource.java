package org.example.gtfsynq.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.example.gtfsynq.api.dto.PageResponse;
import org.example.gtfsynq.api.dto.TripUpdateDto;
import org.example.gtfsynq.api.repository.GtfsQueryRepository;

@Path("/api/rt")
@Produces(MediaType.APPLICATION_JSON)
public class RealtimeResource {

    private final GtfsQueryRepository repository;

    @Inject
    public RealtimeResource(GtfsQueryRepository repository) {
        this.repository = repository;
    }

    @GET
    @Path("/trip-updates")
    public PageResponse<TripUpdateDto> tripUpdates(
            @QueryParam("feedId") String feedId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("25") int size,
            @QueryParam("sort") @DefaultValue("lastSeenAt") String sort,
            @QueryParam("dir") @DefaultValue("desc") String direction,
            @QueryParam("count") @DefaultValue("true") boolean count) {
        return repository.liveTripUpdates(feedId, page, size, sort, direction, count);
    }
}
