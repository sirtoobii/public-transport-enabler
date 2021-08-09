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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Implementation of timetables.search.ch provider.
 * Provides data for Switzerland
 *
 *
 * <p><b>Notes:</b></p>
 * <ul>
 *  <li>
 *      Some endpoints do offer a "transportation_type" parameter, however it seems that this functionality is not implemented
 *  </li>
 *  <li>
 *      Track changes are indicated with a "!" as prefix of the "track" attribute value
 *  </li>
 *  <li>
 *      Canceled connections are either indicated by a "dep_delay" of "X" and/or the a particular leg has the attribute "cancelled" set to true
 *  </li>
 * </ul>
 *
 *
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
    private static final String STATIONBOARD_ENDPOINT = "stationboard.json";
    protected static final String SERVER_PRODUCT = "timetables.search.ch";
    private static final DateFormat DATE_FORMATTER = new SimpleDateFormat("MM/dd/yyyy");
    private static final DateFormat TIME_FORMATTER = new SimpleDateFormat("HH:mm");
    protected static final SimpleDateFormat DATE_TIME_FORMATTER = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");


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

    /**
     * Finds nearby locations. Please note that locations without coordinates result in an additional query
     *
     * @param types        Location types (not supported!)
     * @param location     A Location object, must have either a name or valid id.
     * @param maxDistance  Distance (radius) from location in meters
     * @param maxLocations Number of locations (not supported, is always 10!)
     * @return A possibly empty list of <L>{@link Location}s</L>
     */
    @Override
    public NearbyLocationsResult queryNearbyLocations(Set<LocationType> types, Location location, int maxDistance, int maxLocations) throws IOException {
        ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);

        // Since the endpoint only supports lat/long we have to get the coordinates first (if not already supplied)
        Location fixedLocation = location;
        if (location.coord == null && location.id != null) {
            SuggestLocationsResult suggestionResult = this.suggestLocations(location.id, null, 2);
            if (suggestionResult.suggestedLocations == null || suggestionResult.suggestedLocations.size() != 1) {
                return new NearbyLocationsResult(header, NearbyLocationsResult.Status.INVALID_ID);
            } else {
                fixedLocation = suggestionResult.suggestedLocations.get(0).location;
            }
        }
        if (fixedLocation.coord != null) {
            String latlon = String.format("%f,%f", fixedLocation.coord.getLatAsDouble(), fixedLocation.coord.getLonAsDouble());
            HttpUrl queryUrl = API_BASE.newBuilder()
                    .addPathSegment(COMPLETION_ENDPOINT)
                    .addQueryParameter("latlon", latlon)
                    .addQueryParameter("accuracy", Integer.toString(maxDistance))
                    .addQueryParameter("show_ids", "1")
                    .addQueryParameter("show_coordinates", "1")
                    .build();
            CharSequence res = httpClient.get(queryUrl);
            try {
                String jsonResult = res.toString();
                if (jsonResult.equals("")) {
                    return new NearbyLocationsResult(header, NearbyLocationsResult.Status.INVALID_ID);
                }
                JSONArray rawResult = new JSONArray(jsonResult);
                List<Location> suggestions = new ArrayList<>();
                for (int i = 0; i < rawResult.length(); i++) {
                    JSONObject entry = rawResult.getJSONObject(i);
                    suggestions.add(extractLocation(entry));
                }
                return new NearbyLocationsResult(header, suggestions);
            } catch (final JSONException x) {
                throw new ParserException("queryNearbyLocations: cannot parse json:" + x);
            }
        } else {
            return new NearbyLocationsResult(header, NearbyLocationsResult.Status.INVALID_ID);
        }
    }

    /**
     * Returns all departing connections from a station id
     *
     * @param stationId     id (or name) of the station
     * @param time          desired time for departing, or {@code null} for the provider default
     * @param maxDepartures maximum number of departures to get or {@code 0}
     * @param equivs        (Not supported!)
     * @return List of departing connections
     */
    @Override
    public QueryDeparturesResult queryDepartures(String stationId, @Nullable Date time, int maxDepartures, boolean equivs) throws IOException {
        ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
        // Set time to now if not set
        time = time == null ? new Date() : time;

        HttpUrl queryUrl = API_BASE.newBuilder()
                .addPathSegment(STATIONBOARD_ENDPOINT)
                .addQueryParameter("stop", stationId)
                .addQueryParameter("date", DATE_FORMATTER.format(time))
                .addQueryParameter("time", TIME_FORMATTER.format(time))
                .addQueryParameter("limit", String.valueOf(maxDepartures))
                .addQueryParameter("show_tracks", "1")
                .addQueryParameter("show_delays", "1")
                .build();

        CharSequence res = httpClient.get(queryUrl);
        try {
            JSONObject rawResult = new JSONObject(res.toString());
            if (rawResult.has("messages")) {
                // This could be bit more refined "messages" is also set when there are simply no departures
                return new QueryDeparturesResult(header, QueryDeparturesResult.Status.INVALID_STATION);
            }
            StationBoardResult sb = new StationBoardResult(rawResult);
            Location boardLocation = new Location(LocationType.STATION, sb.stationID, Point.fromDouble(sb.lat, sb.lon), null, sb.name);
            sb.entries.forEach(stationBoardEntry -> {

            });

            return null;
        } catch (final JSONException | ParseException x) {
            throw new ParserException("queryNearbyLocations: cannot parse json:" + x);
        }
    }

    /**
     * Suggests stations, POIs or addresses based on user input
     *
     * @param constraint   Input by user so far
     * @param types        Types of locations to suggest (not supported!)
     * @param maxLocations Number of locations (not supported, is always 10!)
     * @return A possibly empty list of <L>{@link Location}s</L>
     */
    @Override
    public SuggestLocationsResult suggestLocations(CharSequence constraint, @Nullable Set<LocationType> types, int maxLocations) throws IOException {
        HttpUrl queryUrl = API_BASE.newBuilder()
                .addPathSegment(COMPLETION_ENDPOINT)
                .addQueryParameter("term", constraint.toString())
                .addQueryParameter("show_ids", "1")
                .addQueryParameter("show_coordinates", "1")
                .build();
        CharSequence res = httpClient.get(queryUrl);
        try {
            JSONArray rawResult = new JSONArray(res.toString());
            List<SuggestedLocation> suggestions = new ArrayList<>();
            for (int i = 0; i < rawResult.length(); i++) {
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
        ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
        HashMap<String, String> rawParameters = new HashMap<>();
        rawParameters.put("from", from.id == null ? from.name : from.id);
        rawParameters.put("to", to.id == null ? to.name : to.id);
        rawParameters.put("via", via != null ? via.id : null);
        rawParameters.put("date", DATE_FORMATTER.format(date));
        rawParameters.put("time", TIME_FORMATTER.format(date));
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
            RouteResult routeResult = new RouteResult(new JSONObject(res.toString()));
            if (routeResult.connections.size() == 0) {
                // More granularity would be very tedious to implement since reasons are free-text an possibly in 4 different languages...
                return new QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS);
            }
            List<Trip> tripsList = new ArrayList<>(N_TRIPS);
            routeResult.connections.forEach(connection -> {
                // Format disruptions (if any)
                List<String> disruptions = connection.disruptions
                        .stream()
                        .map(dis -> String.format("%s | %s - %s", dis.header, dis.timeStart, dis.timeEnd))
                        .collect(Collectors.toList());
                AtomicInteger numChanges = new AtomicInteger(-1);
                List<Trip.Leg> legsList = new ArrayList<>(10);
                connection.legs.forEach(leg -> {
                    List<Stop> intermediateStops = new ArrayList<>(20);

                    // Departure
                    Date plannedDeparture = leg.departure;
                    Date expectedDeparture = addMinutesToDate(leg.departure, leg.dep_delay);
                    Position planedDeparturePos = null;

                    // Arrival Stop
                    Position planedArrivalPos = null;

                    Location arrivalLocation;
                    Location departureLocation;

                    if (leg.exit != null) {
                        // Some legs do not have location data...
                        Point legExitLocation = leg.exit.lon != null ? Point.fromDouble(leg.exit.lat, leg.exit.lon) : null;
                        Point legLocation = leg.lon != null ? Point.fromDouble(leg.lat, leg.lon) : null;

                        // Some bus-stops in rural areas do not have a track name..
                        planedDeparturePos = leg.track != null ? new Position(leg.track) : null;
                        planedArrivalPos = leg.exit.track != null ? new Position(leg.exit.track) : null;

                        Date plannedArrival = leg.exit.arrival;
                        Date expectedArrival = addMinutesToDate(leg.exit.arrival, leg.exit.arr_delay);
                        arrivalLocation = new Location(leg.exit.isAddress ? LocationType.ADDRESS : LocationType.STATION, leg.exit.stopID, legExitLocation, null, leg.exit.name);
                        departureLocation = new Location(leg.isAddress ? LocationType.ADDRESS : LocationType.STATION, leg.stopID, legLocation, null, leg.name);

                        Stop departureStop = new Stop(departureLocation, true, plannedDeparture, expectedDeparture, planedDeparturePos, null, leg.cancelled);
                        Stop arrivalStop = new Stop(arrivalLocation, false, plannedArrival, expectedArrival, planedArrivalPos, null, leg.cancelled);
                        String infoText = String.join(",", leg.infotexts);
                        if (leg.is_walk) {
                            legsList.add(new Trip.Individual(Trip.Individual.Type.WALK, departureLocation, plannedDeparture, arrivalLocation, plannedArrival, null, 0));
                        } else {
                            numChanges.getAndIncrement();
                            Line line = new Line(leg.Z, leg.operator, type2Product(leg.G), leg.line, new Style(Style.Shape.RECT, leg.bgColor, leg.fgColor));
                            legsList.add(new Trip.Public(line, arrivalLocation, departureStop, arrivalStop, intermediateStops, null, infoText + String.join(",", disruptions)));
                        }

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

                    } else {
                        // We reached the final leg which is our destination (and identical with the last "Exit")
                        return;
                    }
                });
                tripsList.add(new Trip("generated_" + UUID.randomUUID(), from, to, legsList, null, null, numChanges.get()));
            });
            Date lastDeparture = tripsList.get(tripsList.size() - 1).getFirstPublicLeg().getDepartureTime();
            Date firstArrival = tripsList.get(0).getLastPublicLeg().getArrivalTime();
            CHSearchContext context = new CHSearchContext(from, to, via, firstArrival, lastDeparture, dep, options);
            return new QueryTripsResult(header, requestURL.toString(), from, via, to, context, tripsList);

        } catch (final JSONException x) {
            throw new ParserException("JSON Error:" + x);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public QueryTripsResult queryMoreTrips(QueryTripsContext context, boolean later) throws IOException {
        CHSearchContext chCont = (CHSearchContext) context;
        if (later) {
            // later
            return queryTrips(chCont.from, chCont.via, chCont.to, addMinutesToDate(chCont.lastDeparture, 1), true, chCont.options);
        } else {
            // before
            return queryTrips(chCont.from, chCont.via, chCont.to, addMinutesToDate(chCont.firstArrival, -1), false, chCont.options);
        }

    }

    private static Product type2Product(String chSearchType) {
        HashMap<String, Product> mapping = new HashMap<>();
        mapping.put("IC", Product.HIGH_SPEED_TRAIN);
        mapping.put("ICE", Product.HIGH_SPEED_TRAIN);
        mapping.put("RJX", Product.HIGH_SPEED_TRAIN); // RailJetExpress
        mapping.put("IR", Product.HIGH_SPEED_TRAIN);
        mapping.put("EC", Product.HIGH_SPEED_TRAIN);
        mapping.put("RE", Product.REGIONAL_TRAIN);
        mapping.put("B", Product.BUS);
        mapping.put("S", Product.REGIONAL_TRAIN);
        mapping.put("T", Product.TRAM);
        mapping.put("PB", Product.CABLECAR);
        mapping.put("BAT", Product.FERRY);
        return mapping.get(chSearchType);
    }

    private Location extractLocation(JSONObject locationEntry) throws JSONException {
        // Sometime there is no station-id and location
        String stationID = locationEntry.has("id") ? locationEntry.getString("id") : null;
        Point stationLocation = locationEntry.has("lat") ? Point.fromDouble(locationEntry.getDouble("lat"), locationEntry.getDouble("lon")) : null;
        return new Location(
                LocationType.STATION,
                stationID,
                stationLocation,
                null,
                locationEntry.getString("label"));
    }

    private static Date addMinutesToDate(Date orig, long minutes) {
        if (orig == null) return null;
        long newTime = orig.getTime() + (1000 * 60 * minutes);
        return new Date(newTime);
    }

    private static int delayParser(String delay) {
        try {
            // "X" translates to canceled
            if ("X".equals(delay)) return 0;
            return Integer.parseInt(delay);
        } catch (NumberFormatException x) {
            System.out.println(x);
        }
        return 0;
    }

    private static int hexColor2int(String hexValue) {
        //Unfortunately they mix between short and long from...
        //ToDo: This method does not work correctly (yet)....
        if (hexValue.length() == 3) {
            char[] seq = {
                    hexValue.charAt(0), hexValue.charAt(0),
                    hexValue.charAt(1), hexValue.charAt(1),
                    hexValue.charAt(2), hexValue.charAt(2),
                    '0','0'
            };
            return Integer.parseUnsignedInt(new String(seq), 16);
        } else if (hexValue.length() == 6) {
            return Integer.parseUnsignedInt(hexValue + "00", 16);
        } else {
            throw new NumberFormatException("hex value has more than six bytes");
        }
    }

    private static class RouteResult {
        public final int nConnections;
        public final String error;
        public final List<String> messages = new ArrayList<>();
        public final List<Connection> connections = new ArrayList<>(N_TRIPS);

        RouteResult(JSONObject rawResult) throws JSONException, ParseException {
            if (rawResult.has("error")) {
                this.error = rawResult.getString("error");
                this.nConnections = 0;
            } else if (rawResult.has("count")) {
                nConnections = rawResult.getInt("count");
                this.error = "";
                JSONArray rawCons = rawResult.getJSONArray("connections");
                for (int i = 0; i < rawCons.length(); i++) {
                    connections.add(new Connection(rawCons.getJSONObject(i)));
                }
            } else {
                if (rawResult.has("messages")) {
                    JSONArray rawMessages = rawResult.getJSONArray("messages");
                    for (int j = 0; j < rawMessages.length(); j++) {
                        messages.add(rawMessages.getString(j));
                    }
                }
                this.nConnections = 0;
                this.error = "No connections found";
            }


        }

        private static class Connection {
            public final List<Leg> legs = new ArrayList<>();
            public final String from;
            public final String to;
            public final Date arrival;
            public final Date departure;
            public final double duration;
            public final List<Disruption> disruptions;

            Connection(JSONObject rawConnection) throws JSONException, ParseException {
                try {
                    this.from = rawConnection.getString("from");
                    this.to = rawConnection.getString("to");
                    this.duration = rawConnection.getDouble("duration");
                    this.arrival = DATE_TIME_FORMATTER.parse(rawConnection.getString("arrival"));
                    this.departure = DATE_TIME_FORMATTER.parse(rawConnection.getString("departure"));
                    this.disruptions = Disruption.extractDisruptionsToList(rawConnection);

                    JSONArray rawLegs = rawConnection.getJSONArray("legs");
                    for (int i = 0; i < rawLegs.length(); i++) {
                        legs.add(new Leg(rawLegs.getJSONObject(i)));
                    }
                } catch (JSONException x) {
                    throw new JSONException("Connection::" + x);
                }
            }


            private static class Disruption {
                /**
                 * Returns a (possibly empty) List of Disruptions from a JsonObject
                 *
                 * @param rawObject JSONObject, usually a "leg" or "connection"
                 * @return list of Disruptions (empty, if key "disruptions" not present)
                 */
                public static List<Disruption> extractDisruptionsToList(JSONObject rawObject) throws JSONException, ParseException {
                    List<Disruption> disruptions = new ArrayList<>();
                    if (rawObject.has("disruptions")) {
                        JSONObject rawDisruptions = rawObject.getJSONObject("disruptions");
                        // Since the individual disruptions have their url as key(!) we have to do a bit of ugliness here...
                        JSONArray dis = rawDisruptions.names();
                        for (int k = 0; k < rawDisruptions.length(); k++) {
                            String disruptionKey = dis.getString(k);
                            disruptions.add(new Disruption(disruptionKey, rawDisruptions.getJSONObject(disruptionKey)));
                        }
                    }
                    return disruptions;
                }

                private static final SimpleDateFormat date_formatter_en = new SimpleDateFormat("dd-MM-yyyy HH:mm");
                // unfortunately, they change the format depending on request lang..
                private static final SimpleDateFormat date_formatter_de = new SimpleDateFormat("dd.MM.yyyy HH:mm");
                public final String externalURL;
                public final String ID;
                public final String header;
                public final String lead;
                public final String text;
                public final @Nullable
                Date timeStart;
                public final @Nullable
                Date timeEnd;

                private Disruption(String externalURL, JSONObject rawDisruption) throws JSONException, ParseException {
                    this.externalURL = externalURL;
                    this.ID = rawDisruption.getString("id");
                    this.header = rawDisruption.getString("header");
                    this.lead = rawDisruption.getString("lead");
                    this.text = rawDisruption.getString("text");
                    Date tempStart = null;
                    Date tempEnd = null;
                    if (rawDisruption.has("timerange")) {
                        String[] timeRanges = rawDisruption.getString("timerange").split("-", 3);
                        try {
                            tempStart = date_formatter_en.parse(timeRanges[0]);
                            tempEnd = date_formatter_en.parse(timeRanges[1]);
                        } catch (ParseException e) {
                            tempStart = date_formatter_de.parse(timeRanges[0]);
                            tempEnd = date_formatter_de.parse(timeRanges[1]);
                        }

                    }
                    this.timeStart = tempStart;
                    this.timeEnd = tempEnd;

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
                public final boolean cancelled;
                public final Double lon;
                public final boolean isAddress;
                public final Exit exit;
                public final List<Stop> stops = new ArrayList<>();
                public final boolean is_walk;
                public final List<String> infotexts = new ArrayList<>();
                public final List<Disruption> disruptions;


                public Leg(JSONObject rawLeg) throws JSONException, ParseException {
                    try {
                        this.departure = rawLeg.has("departure") ? DATE_TIME_FORMATTER.parse(rawLeg.getString("departure")) : null;
                        this.arrival = rawLeg.has("arrival") ? DATE_TIME_FORMATTER.parse(rawLeg.getString("arrival")) : null;
                        this.type = rawLeg.has("type") ? rawLeg.getString("type") : "unknown";
                        this.is_walk = "walk".equals(this.type);
                        this.Z = rawLeg.has("*Z") ? rawLeg.getString("*Z") : "00000";
                        this.G = rawLeg.has("*G") ? rawLeg.getString("*G") : "UNKN";
                        this.name = rawLeg.getString("name");
                        this.terminal = rawLeg.has("terminal") ? rawLeg.getString("terminal") : null;
                        this.tripID = rawLeg.has("tripid") ? rawLeg.getString("tripid") : "generated_" + UUID.randomUUID();
                        this.line = rawLeg.has("line") ? rawLeg.getString("line") : null;
                        this.stopID = rawLeg.has("stopid") ? rawLeg.getString("stopid") : null;
                        this.operator = rawLeg.has("operator") ? rawLeg.getString("operator") : null;
                        if (rawLeg.has("bgcolor")) {
                            this.fgColor = hexColor2int(rawLeg.getString("fgcolor"));
                            this.bgColor = hexColor2int(rawLeg.getString("bgcolor"));
                        } else {
                            this.bgColor = 0xFFFFFF; // Withe
                            this.fgColor = 0xFF0000; // Black
                        }
                        this.runningTime = rawLeg.has("runningtime") ? rawLeg.getDouble("runningtime") : 0;
                        this.dep_delay = rawLeg.has("dep_delay") ? delayParser(rawLeg.getString("dep_delay")) : 0;
                        this.arr_delay = rawLeg.has("arr_delay") ? delayParser(rawLeg.getString("arr_delay")) : 0;
                        this.track = rawLeg.has("track") ? rawLeg.getString("track") : null;
                        this.lat = rawLeg.has("lat") ? rawLeg.getDouble("lat") : null;
                        this.lon = rawLeg.has("lon") ? rawLeg.getDouble("lon") : null;
                        this.isAddress = rawLeg.has("isaddress") && rawLeg.getBoolean("isaddress");
                        this.exit = rawLeg.has("exit") ? new Exit(rawLeg.getJSONObject("exit")) : null;
                        this.cancelled = rawLeg.has("cancelled") && rawLeg.getBoolean("cancelled");
                        this.disruptions = Disruption.extractDisruptionsToList(rawLeg);

                        if (rawLeg.has("stops") && !rawLeg.isNull("stops")) {
                            JSONArray rawStops = rawLeg.getJSONArray("stops");
                            for (int i = 0; i < rawStops.length(); i++) {
                                stops.add(new Stop(rawStops.getJSONObject(i)));
                            }
                        }
                        // Sometimes we have an "infotext" attribute which e.g holds the phone number of on-demand services
                        if (rawLeg.has("infotext")) {
                            JSONArray rawInfoTexts = rawLeg.getJSONArray("infotext");
                            for (int i = 0; i < rawInfoTexts.length(); i++) {
                                infotexts.add(rawInfoTexts.getString(i));
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
                            this.arrival = DATE_TIME_FORMATTER.parse(rawExit.getString("arrival"));
                            this.stopID = rawExit.has("stopid") ? rawExit.getString("stopid") : null;
                            this.name = rawExit.getString("name");
                            this.waitTime = rawExit.has("waittime") ? rawExit.getDouble("waittime") : 0;
                            this.track = rawExit.has("track") ? rawExit.getString("track") : null;
                            this.arr_delay = rawExit.has("arr_delay") ? delayParser(rawExit.getString("arr_delay")) : 0;
                            this.lat = rawExit.has("lat") ? rawExit.getDouble("lat") : null;
                            this.lon = rawExit.has("lon") ? rawExit.getDouble("lon") : null;
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
                                this.departure = rawStop.has("departure") ? DATE_TIME_FORMATTER.parse(rawStop.getString("departure")) : DATE_TIME_FORMATTER.parse(rawStop.getString("arrival"));
                                this.arrival = rawStop.has("arrival") ? DATE_TIME_FORMATTER.parse(rawStop.getString("arrival")) : DATE_TIME_FORMATTER.parse(rawStop.getString("departure"));
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

    private static class StationBoardResult {
        public final String stationID;
        public final String name;
        public final double lat;
        public final double lon;
        public final List<StationBoardEntry> entries = new ArrayList<>();

        public StationBoardResult(JSONObject rawStationBoard) throws JSONException, ParseException {
            JSONObject rawStop = rawStationBoard.getJSONObject("stop");
            this.stationID = rawStop.getString("stationid");
            this.name = rawStop.getString("name");
            this.lat = rawStop.getDouble("lat");
            this.lon = rawStop.getDouble("lon");
            JSONArray rawEntries = rawStationBoard.getJSONArray("connections");
            for (int i = 0; i < rawEntries.length(); i++) {
                entries.add(new StationBoardEntry(rawEntries.getJSONObject(i)));
            }
        }

        private static class StationBoardEntry {
            public final Date time;
            public final String G;
            public final String L;
            public final String line;
            public final String operator;
            public final int fgColor;
            public final int bgColor;
            public final int arr_delay;
            public final int dep_delay;
            public Terminal terminal;


            public StationBoardEntry(JSONObject rawEntry) throws JSONException, ParseException {
                this.time = DATE_TIME_FORMATTER.parse(rawEntry.getString("time"));
                G = rawEntry.getString("*G");
                L = rawEntry.getString("*L");
                this.line = rawEntry.getString("line");
                this.operator = rawEntry.getString("operator");
                String[] colors = rawEntry.getString("color").split("~", 3);
                this.dep_delay = rawEntry.has("dep_delay") ? delayParser(rawEntry.getString("dep_delay")) : 0;
                this.arr_delay = rawEntry.has("arr_delay") ? delayParser(rawEntry.getString("arr_delay")) : 0;
                this.fgColor = hexColor2int(colors[0]);
                this.bgColor = hexColor2int(colors[1]);
                this.terminal = new Terminal(rawEntry.getJSONObject("terminal"));
            }

            private static class Terminal {
                public final String stationID;
                public final String name;
                public final double lat;
                public final double lon;

                public Terminal(JSONObject rawTerminal) throws JSONException {
                    this.stationID = rawTerminal.getString("id");
                    this.name = rawTerminal.getString("name");
                    this.lat = rawTerminal.getDouble("lat");
                    this.lon = rawTerminal.getDouble("lon");
                }
            }
        }

    }

    public static class CHSearchContext implements QueryTripsContext {
        private final Location from;
        private final Location to;
        private final @Nullable
        Location via;
        private final @Nullable
        Date lastDeparture;
        private final @Nullable
        Date firstArrival;
        private final boolean isDeparture;
        private final @Nullable
        TripOptions options;

        public CHSearchContext(Location from, Location to, @Nullable Location via, @Nullable Date fristArrival, @Nullable Date lastDeparture, boolean isDeparture, @Nullable TripOptions options) {
            this.from = from;
            this.to = to;
            this.via = via;
            this.lastDeparture = lastDeparture;
            this.firstArrival = fristArrival;
            this.isDeparture = isDeparture;
            this.options = options;
        }

        @Override
        public boolean canQueryLater() {
            return lastDeparture != null;
        }

        @Override
        public boolean canQueryEarlier() {
            return firstArrival != null;
        }
    }
}
