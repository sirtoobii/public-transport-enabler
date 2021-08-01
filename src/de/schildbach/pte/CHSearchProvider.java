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
import java.text.ParseException;
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
 *
 * @author Tobias Bossert
 * @apiNote https://timetable.search.ch/api/help
 */
public class CHSearchProvider extends AbstractNetworkProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://timetable.search.ch/api/");
    private static final int N_TRIPS = 8;
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
        System.out.println(requestURL);
        CharSequence res = httpClient.get(requestURL);
        try {
            RouteResult routeResult = new RouteResult(new JSONObject(res.toString()));
            List<Trip> tripsList = new ArrayList<>(N_TRIPS);
            routeResult.connections.forEach(connection -> {
                List<Trip.Leg> legsList = new ArrayList<>(10);
                connection.legs.forEach(leg -> {
                    List<Stop> intermediateStops = new ArrayList<>(20);
                    Line currentLine;

                    // Departure
                    Date plannedDeparture = leg.departure;
                    Date expectedDeparture = addMinutesToDate(leg.departure, leg.dep_delay);
                    Position planedDeparturePos = null;

                    // Arrival Stop
                    Date plannedArrival = leg.arrival;
                    Date expectedArrival = addMinutesToDate(leg.arrival, leg.arr_delay);
                    Position planedArrivalPos = null;

                    if (leg.is_walk) {
                        currentLine = Line.FOOTWAY;
                    } else {
                        currentLine = new Line(leg.Z, leg.operator, type2Product(leg.G), leg.line, new Style(Style.Shape.RECT, leg.bgColor, leg.fgColor));
                        planedDeparturePos = new Position(leg.track);
                        planedArrivalPos = new Position(leg.exit.track);

                    }
                    Location destinationLocation = to;


                    Stop departureStop = new Stop(from, true, plannedDeparture, expectedDeparture, planedDeparturePos, null);


                    Location arrivalLocation = new Location(leg.exit.isAddress ? LocationType.ADDRESS : LocationType.STATION, leg.exit.stopID, Point.fromDouble(leg.exit.lat, leg.exit.lon), null, leg.exit.name);
                    Stop arrivalStop = new Stop(arrivalLocation, false, plannedArrival, expectedArrival, planedArrivalPos, null);

                    legsList.add(new Trip.Public(currentLine, destinationLocation, departureStop, arrivalStop, intermediateStops, null, null));

                    leg.stops.forEach(stop -> {
                        if (!stop.isSpecial) {
                            Location stopLocation = new Location(LocationType.STATION, stop.stopID, Point.fromDouble(stop.lat, stop.lon), null, stop.name);
                            Date plannedArrivalTime = stop.arrival;
                            Date expectedArrivalTime = addMinutesToDate(stop.arrival, stop.arr_delay);
                            Date plannedDepartureTime = stop.departure;
                            Date expectedDepartureTime = addMinutesToDate(stop.departure, stop.dep_delay);
                            intermediateStops.add(new Stop(stopLocation,
                                    plannedArrivalTime,
                                    expectedArrivalTime,
                                    null,
                                    null,
                                    plannedDepartureTime,
                                    expectedDepartureTime,
                                    null,
                                    null));
                        }
                    });
                });
                tripsList.add(new Trip("generated" + UUID.randomUUID(), from, to, legsList, null, null, legsList.size()));
            });
            ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
            return new QueryTripsResult(header, requestURL.toString(), from, to, via, null, tripsList);

        } catch (final JSONException x) {
            throw new ParserException("JSON Error:" + x);
        } catch (ParseException e) {
            e.printStackTrace();
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
        mapping.put("IC", Product.HIGH_SPEED_TRAIN);
        mapping.put("B", Product.BUS);
        mapping.put("S", Product.REGIONAL_TRAIN);
        mapping.put("T", Product.TRAM);
        mapping.put("cablecar", Product.CABLECAR);
        return mapping.get(chSearchType);
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

    private static Date addMinutesToDate(Date orig, long minutes) {
        if (orig == null) return null;
        long newTime = orig.getTime() + (1000 * 60 * minutes);
        return new Date(newTime);
    }

    private static int delayParser(String delay){
        try{
            // Unfortunately "X" is not documented...
            if ("X".equals(delay)) return 0;
            return Integer.parseInt(delay);
        } catch (NumberFormatException x){
            System.out.println(x);
        }
        return 0;
    }

    private static class RouteResult {
        public final int nConnections;
        public final List<Connection> connections = new ArrayList<>(N_TRIPS);

        RouteResult(JSONObject rawResult) throws JSONException, ParseException {
            nConnections = rawResult.getInt("count");
            JSONArray rawCons = rawResult.getJSONArray("connections");
            for (int i = 0; i < rawCons.length(); i++) {
                connections.add(new Connection(rawCons.getJSONObject(i)));
            }
        }

        private static class Connection {
            public final List<Leg> legs = new ArrayList<>();
            protected static final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            public final String from;
            public final String to;
            public final Date arrival;
            public final Date departure;
            public final double duration;

            Connection(JSONObject rawConnection) throws JSONException, ParseException {
                try {
                    this.from = rawConnection.getString("from");
                    this.to = rawConnection.getString("to");
                    this.duration = rawConnection.getDouble("duration");
                    this.arrival = dateFormatter.parse(rawConnection.getString("arrival"));
                    this.departure = dateFormatter.parse(rawConnection.getString("departure"));

                    JSONArray rawLegs = rawConnection.getJSONArray("legs");
                    for (int i = 0; i < rawLegs.length(); i++) {
                        legs.add(new Leg(rawLegs.getJSONObject(i)));
                    }
                } catch (JSONException x) {
                    throw new JSONException("Connection::" + x);
                }


            }

            private static class Leg {
                public final Date departure;
                public final Date arrival;
                public final String tripID;
                public final String stopID;
                public final String name;
                public final String Z; // Train number
                public final String G; // Train Product
                public final String terminal;
                public final String line;
                public final String type;
                public final String operator;
                public final int fgColor;
                public final int bgColor;
                public final double runningTime;
                public final int dep_delay;
                public final int arr_delay;
                public final String track;
                public final Double lat;
                public final Double lon;
                public final Exit exit;
                public final List<Stop> stops = new ArrayList<>();
                public final boolean is_walk;


                public Leg(JSONObject rawLeg) throws JSONException, ParseException {
                    try {
                        this.departure = rawLeg.has("departure") ? dateFormatter.parse(rawLeg.getString("departure")) : dateFormatter.parse(rawLeg.getString("arrival"));
                        this.arrival = rawLeg.has("arrival") ? dateFormatter.parse(rawLeg.getString("arrival")) : dateFormatter.parse(rawLeg.getString("departure"));
                        this.type = rawLeg.has("type") ? rawLeg.getString("type") : "unknown";
                        this.is_walk = "walk".equals(this.type);
                        this.Z = rawLeg.has("*Z") ? rawLeg.getString("*Z") : "00000";
                        this.G = rawLeg.has("*G") ? rawLeg.getString("*G") : "UNKN";
                        this.name = rawLeg.getString("name");
                        this.terminal = rawLeg.has("terminal") ? rawLeg.getString("terminal") : null;
                        this.tripID = rawLeg.has("tripid") ? rawLeg.getString("tripid") : "generated_" + UUID.randomUUID();
                        this.line = rawLeg.has("line") ? rawLeg.getString("line") : null;
                        this.stopID = rawLeg.getString("stopid");
                        this.operator = rawLeg.has("operator") ? rawLeg.getString("operator") : null;
                        this.fgColor = rawLeg.has("fgcolor") ? Integer.parseInt(rawLeg.getString("fgcolor"), 16) : 0xff;
                        this.bgColor = rawLeg.has("bgcolor") ? Integer.parseInt(rawLeg.getString("bgcolor"), 16) : 0x00;
                        this.runningTime = rawLeg.has("runningtime") ? rawLeg.getDouble("runningtime") : 0;
                        this.dep_delay = rawLeg.has("dep_delay") ? delayParser(rawLeg.getString("dep_delay")) : 0;
                        this.arr_delay = rawLeg.has("arr_delay") ? delayParser(rawLeg.getString("arr_delay")) : 0;
                        this.track = rawLeg.has("track") ? rawLeg.getString("track") : null;
                        this.lat = rawLeg.getDouble("lat");
                        this.lon = rawLeg.getDouble("lon");
                        this.exit = rawLeg.has("exit") ? new Exit(rawLeg.getJSONObject("exit")) : null;

                        if (rawLeg.has("stops") && !rawLeg.isNull("stops")) {
                            JSONArray rawStops = rawLeg.getJSONArray("stops");
                            for (int i = 0; i < rawStops.length(); i++) {
                                stops.add(new Stop(rawStops.getJSONObject(i)));
                            }
                        }

                    } catch (JSONException x) {
                        throw new JSONException("Leg::" + x);
                    }


                }

                private static class Exit {
                    public final Date arrival;
                    public final String stopID;
                    public final String name;
                    public final double waitTime;
                    public final String track;
                    public final int arr_delay;
                    public final Double lat;
                    public final Double lon;
                    public final boolean isAddress;

                    Exit(JSONObject rawExit) throws JSONException, ParseException {
                        try {
                            this.arrival = dateFormatter.parse(rawExit.getString("arrival"));
                            this.stopID = rawExit.getString("stopid");
                            this.name = rawExit.getString("name");
                            this.waitTime = rawExit.has("waittime") ? rawExit.getDouble("waittime") : 0;
                            this.track = rawExit.has("track") ? rawExit.getString("track") : null;
                            this.arr_delay = rawExit.has("arr_delay") ? delayParser(rawExit.getString("arr_delay")) : 0;
                            this.lat = rawExit.getDouble("lat");
                            this.lon = rawExit.getDouble("lon");
                            this.isAddress = rawExit.has("isaddress") && rawExit.getBoolean("isaddress");
                        } catch (JSONException x) {
                            throw new JSONException("Exit::" + x);
                        }

                    }
                }

                private static class Stop {
                    public final @Nullable
                    Date arrival;
                    public final @Nullable
                    Date departure;
                    public final int dep_delay;
                    public final int arr_delay;
                    public final String stopID;
                    public final String name;
                    public final Double lat;
                    public final Double lon;
                    // Sometimes the we have no real "Stop" e.g (Löschbergbasis Tunnel) which means we have no arrival/departure times
                    public boolean isSpecial = false;

                    Stop(JSONObject rawStop) throws JSONException, ParseException {
                        try {
                            if (rawStop.has("arrival") || rawStop.has("departure")) {
                                // The first stop does not have an arrival attribute an similarly the last no departure
                                this.departure = rawStop.has("departure") ? dateFormatter.parse(rawStop.getString("departure")) : dateFormatter.parse(rawStop.getString("arrival"));
                                this.arrival = rawStop.has("arrival") ? dateFormatter.parse(rawStop.getString("arrival")) : dateFormatter.parse(rawStop.getString("departure"));
                            } else {
                                this.isSpecial = true;
                                this.departure = null;
                                this.arrival = null;
                            }
                            this.dep_delay = rawStop.has("dep_delay") ? delayParser(rawStop.getString("dep_delay")) : 0;
                            this.arr_delay = rawStop.has("arr_delay") ? delayParser(rawStop.getString("arr_delay")) : 0;
                            this.stopID = rawStop.getString("stopid");
                            this.name = rawStop.getString("name");
                            this.lat = rawStop.getDouble("lat");
                            this.lon = rawStop.getDouble("lon");
                        } catch (JSONException x) {
                            throw new JSONException("Stop::" + x);
                        }

                    }
                }
            }

        }
    }
}
