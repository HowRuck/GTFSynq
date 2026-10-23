package org.example.gtfsynq.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.example.gtfsynq.api.dto.OverviewDto;
import org.example.gtfsynq.api.repository.GtfsQueryRepository;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class OverviewResource {

    private final GtfsQueryRepository repository;

    @Inject
    public OverviewResource(GtfsQueryRepository repository) {
        this.repository = repository;
    }

    @GET
    @Path("/overview")
    public OverviewDto overview() {
        return repository.overview();
    }

    @GET
    @Path("/feeds")
    public List<String> feeds() {
        return repository.feedIds();
    }
}
