/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.pte.live;

import de.schildbach.pte.CHSearchProvider;
import de.schildbach.pte.dto.*;
import org.junit.Test;

import java.util.Date;


/**
 * @author Tobias Bossert
 */
public class CHSearchProviderLiveTest extends AbstractProviderLiveTest {
    public CHSearchProviderLiveTest() {
        super(new CHSearchProvider());
    }

    @Test
    public void nearbyStations() throws Exception {
        final NearbyLocationsResult result = queryNearbyStations(new Location(LocationType.STATION, "8572547"));
        print(result);
    }

    @Test
    public void suggestLocations() throws Exception {
        final SuggestLocationsResult result = suggestLocations("haupt");
        print(result);
    }

    @Test
    public void suggestLocationsUmlaut() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Höhle");
        print(result);
    }
//    @Test
//    // Address suggestions are not supported...
//    public void suggestLocationsAddress() throws Exception {
//        final SuggestLocationsResult result = suggestLocations("Dorfstrasse 10, Dällikon, Schweiz");
//        print(result);
//    }
//
    @Test
    public void shortTrip() throws Exception {
        final QueryTripsResult result = queryTrips(new Location(LocationType.STATION, "8503000", null, "Zürich HB"),
                null, new Location(LocationType.STATION, "8507785", null, "Bern, Hauptbahnhof"), new Date(), true,
                null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }
//
    @Test
    public void slowTrip() throws Exception {
        final QueryTripsResult result = queryTrips(
                new Location(LocationType.STATION, "8587210", null, "Schocherswil, Alte Post"), null,
                new Location(LocationType.STATION, "8592972", null, "Laconnex, Mollach"), new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
        final QueryTripsResult beforeResult = queryMoreTrips(result.context, false);
        print(beforeResult);
    }
//
    @Test
    public void tripWithFootway() throws Exception {
        final Location from = new Location(LocationType.ADDRESS, null, Point.from1E6(46689354, 7683444), null,
                "Spiez, Seestraße 62");
        final Location to = new Location(LocationType.ADDRESS, null, Point.from1E6(47133169, 8767425), null,
                "Einsiedeln, Erlenmoosweg 24");
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }

    @Test
    public void tripFromAddress() throws Exception {
        final Location from = new Location(LocationType.ADDRESS, null, Point.from1E6(47438595, 8437369), null,
                "Dorfstrasse 10, Dällikon, Schweiz");
        final Location to = new Location(LocationType.STATION, "8500010", null, "Basel");
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }
}
