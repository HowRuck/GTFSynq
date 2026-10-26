package org.example.gtfsynq.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import org.example.gtfsynq.api.dto.TripMarkerDto;
import org.example.gtfsynq.api.repository.GtfsQueryRepository;
import org.example.gtfsynq.shared.protocol.query.TripMarkersReply;
import org.example.gtfsynq.shared.protocol.query.TripMarkersRequest;
import org.junit.jupiter.api.Test;

class GtfsQueryGrpcServiceTest {

    private final CountingRepository repository = new CountingRepository();
    private final GtfsQueryGrpcService service = new GtfsQueryGrpcService(repository);

    @Test
    void servesTheMarkerSnapshotFromTheShortCache() {
        var first = call();
        var second = call();

        assertEquals(1, repository.calls, "a second poll within ten seconds must reuse the snapshot");
        assertEquals(1, first.getItemsCount());
        assertEquals("10", second.getItems(0).getRouteShortName());
    }

    private TripMarkersReply call() {
        var replies = new ArrayList<TripMarkersReply>();
        service.listTripMarkers(TripMarkersRequest.getDefaultInstance(), new StreamObserver<TripMarkersReply>() {
            @Override
            public void onNext(TripMarkersReply value) {
                replies.add(value);
            }

            @Override
            public void onError(Throwable failure) {
                throw new AssertionError(failure);
            }

            @Override
            public void onCompleted() {}
        });
        return replies.getFirst();
    }

    /** Counts queries; only the marker query is exercised by this test. */
    private static final class CountingRepository extends GtfsQueryRepository {

        private int calls;

        private CountingRepository() {
            super(null);
        }

        @Override
        public List<TripMarkerDto> liveTripMarkers() {
            calls++;
            return List.of(new TripMarkerDto(52.5, 13.4, "10", "Trip 1", "route-id", "trip-id", 3, "Central", 120));
        }
    }
}
