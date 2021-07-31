package de.schildbach.pte;

import de.schildbach.pte.dto.*;
import de.schildbach.pte.exception.ParserException;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nullable;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Implementation of timetables.search.ch provider.
 * Provides data for Switzerland
 * <p>
 * Quota: 1000 route queries and 5000 departure/arrival tables per Day
 * </p>
 * <p>
 * TOS: https://timetable.search.ch/api/terms
 * </p>
 * @author Tobias Bossert
 * @apiNote https://timetable.search.ch/api/help
 */
public class CHSearchProvider extends AbstractNetworkProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://timetable.search.ch/api/");
    private static final String COMPLETION_ENDPOINT = "completion.json";
    private static final String TRIP_ENDPOINT = "route.json";
    protected static final String SERVER_PRODUCT = "timetables.search.ch";

    private final List<Capability> CAPABILITIES = Arrays.asList(
            Capability.SUGGEST_LOCATIONS,
            Capability.NEARBY_LOCATIONS,
            Capability.DEPARTURES,
            Capability.TRIPS,
            Capability.TRIPS_VIA
    );

    public CHSearchProvider(NetworkId network) {
        super(network);
    }

    @Override
    protected boolean hasCapability(final Capability capability) {
        return CAPABILITIES.contains(capability);
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(Set<LocationType> types, Location location, int maxDistance, int maxLocations) throws IOException {
        CharSequence res = httpClient.get(API_BASE.newBuilder()
                .addPathSegment(COMPLETION_ENDPOINT)
                .addQueryParameter("latlon", location.coord.toString())
                .addQueryParameter("accuracy", Integer.toString(maxDistance))
                .addQueryParameter("show_ids", "1")
                .addQueryParameter("show_coordinates", "1")
                .build());
        try {
            JSONArray rawResult = new JSONArray(res.toString());
            List<Location> suggestions = new ArrayList<>();
            for (int i = 0; i < Math.min(rawResult.length(), maxLocations); i++) {
                JSONObject entry = rawResult.getJSONObject(i);
                suggestions.add(extractLocation(entry));
            }
            ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
            return new NearbyLocationsResult(header, suggestions);
        } catch (final JSONException x) {
            throw new ParserException("queryNearbyLocations: cannot parse json:" + x);
        }
    }

    @Override
    public QueryDeparturesResult queryDepartures(String stationId, @Nullable Date time, int maxDepartures, boolean equivs) throws IOException {
        return null;
    }

    @Override
    public SuggestLocationsResult suggestLocations(CharSequence constraint, @Nullable Set<LocationType> types, int maxLocations) throws IOException {
        CharSequence res = httpClient.get(API_BASE.newBuilder()
                .addPathSegment(COMPLETION_ENDPOINT)
                .addQueryParameter("term", constraint.toString())
                .addQueryParameter("show_ids", "1")
                .addQueryParameter("show_coordinates", "1")
                .build());
        try {
            JSONArray rawResult = new JSONArray(res.toString());
            List<SuggestedLocation> suggestions = new ArrayList<>();
            for (int i = 0; i < Math.min(rawResult.length(), maxLocations); i++) {
                JSONObject entry = rawResult.getJSONObject(i);
                suggestions.add(new SuggestedLocation(extractLocation(entry)));
            }
            ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
            return new SuggestLocationsResult(header, suggestions);
        } catch (final JSONException x) {
            throw new ParserException("suggestLocations: cannot parse json:" + x);
        }
    }

    @Override
    public QueryTripsResult queryTrips(Location from, @Nullable Location via, Location to, Date date, boolean dep, @Nullable TripOptions options) throws IOException {
        HashMap<String, String> rawParameters = new HashMap<>();
        DateFormat dateFormatter = new SimpleDateFormat("MM/dd/yyyy");
        DateFormat timeFormatter = new SimpleDateFormat("HH:mm");
        rawParameters.put("from", from.id);
        rawParameters.put("to", to.id);
        rawParameters.put("via", via != null ? via.id : null);
        rawParameters.put("date", dateFormatter.format(date));
        rawParameters.put("time", timeFormatter.format(date));
        rawParameters.put("time_type", dep ? "depart" : "arrival");
        rawParameters.put("show_delays", "1");
        rawParameters.put("show_trackchanges", "1");

        HttpUrl.Builder builder = API_BASE.newBuilder();
        builder.addPathSegment(TRIP_ENDPOINT);
        rawParameters.forEach((key, value) -> {
            if (value != null) {
                builder.addQueryParameter(key, value);
            }
        });
        HttpUrl requestURL = builder.build();
        CharSequence res = httpClient.get(requestURL);
        try {
            JSONObject jsonResult = new JSONObject(res.toString());
            // Process Connections (Trips)
            JSONArray connectionsResult = jsonResult.getJSONArray("connections");
            List<Trip> trips = new ArrayList<>();
            for (int i = 0; i < connectionsResult.length(); i++) {
                JSONObject entry = connectionsResult.getJSONObject(i);

                // Process Legs
                JSONArray legsResult = entry.getJSONArray("legs");
                List<Trip.Leg> legs = new ArrayList<>();
                for (int j = 0; j < legsResult.length(); j++) {
                    JSONObject legEntry = legsResult.getJSONObject(j);
                    String transportType = legEntry.getString("type");
                    String lineName = legEntry.getString("line");
                    String lineID = legEntry.getString("tripid");
                    String networkOperator = legEntry.getString("operator");
                    String lineTerminalDest = legEntry.getString("terminal");
                    Line line = "walk".equals(transportType) ? Line.FOOTWAY : new Line(lineID, networkOperator, type2Product(transportType), lineName);
                    Location terminalDestination = new Location(LocationType.ANY, null, null, lineTerminalDest, lineTerminalDest);

                    String departureName = legEntry.getString("name");
                    double departureLat = legEntry.getDouble("lat");
                    double departureLong = legEntry.getDouble("lon");
                    String departureStationID = legEntry.getString("stopid");
                    String departureTime = legEntry.getString("departure");

                    Location departureLocation = new Location(LocationType.STATION,
                            departureStationID,
                            Point.fromDouble(departureLat, departureLong),
                            departureName,
                            departureName
                    );
                    Stop departureStop = new Stop(departureLocation);
                    Stop arrivalStop = new Stop();

                    Location arrivalLocation = new Location();

                }

                Location loc = extractLocation(entry);
            }

        } catch (final JSONException x) {
            throw new ParserException("queryTrips: cannot parse json:" + x);
        }
        return null;
    }

    private Map<String, String> prepareQueryParameters(Map<String, String> input) {
        HashMap<String, String> result = new HashMap<>();
        input.forEach((key, value) -> {
            if (value != null) {
                result.put(key, value);
            }
        });
        return result;
    }

    private Product type2Product(String chSearchType) {
        HashMap<String, Product> mapping = new HashMap<>();
        // walk ??
        mapping.put("express_train", Product.HIGH_SPEED_TRAIN);
        mapping.put("bus", Product.BUS);
        mapping.put("train", Product.REGIONAL_TRAIN);
        mapping.put("tram", Product.TRAM);
        mapping.put("cablecar", Product.CABLECAR);
        return mapping.getOrDefault(chSearchType, Product.fromCode(Product.UNKNOWN));
    }

    private Line.Attr code2Attr(String code) {

    }

    private Location extractLocation(JSONObject locationEntry) throws JSONException {
        return new Location(
                LocationType.STATION,
                locationEntry.getString("id"),
                Point.fromDouble(locationEntry.getDouble("lat"), locationEntry.getDouble("lon")),
                null,
                locationEntry.getString("label"));
    }

    @Override
    public QueryTripsResult queryMoreTrips(QueryTripsContext context, boolean later) throws IOException {
        return null;
    }
}
