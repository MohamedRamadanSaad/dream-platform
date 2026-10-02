package com.saadat.common.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The MMDB reader over DB-IP-shaped fixtures written by {@link MmdbTestWriter}. */
class GeoIpCountryLookupTest {

    @TempDir
    Path dir;

    @Test
    void looksUpIpv4AndIpv6Networks() throws IOException {
        Path db = dir.resolve("country.mmdb");
        writeFixture(db, "EG", "Egypt");

        GeoIpCountryLookup lookup = new GeoIpCountryLookup(properties(db.toString()));

        assertThat(lookup.isLoaded()).isTrue();
        assertThat(lookup.countryCode(ip("203.0.113.7"))).contains("EG");
        assertThat(lookup.countryCode(ip("198.51.100.250"))).contains("SA");
        assertThat(lookup.countryCode(ip("2001:db8:1::5"))).contains("AE");
        assertThat(lookup.countryCode(ip("192.0.2.1"))).as("not in the database").isEmpty();
        assertThat(lookup.countryCode(null)).isEmpty();
    }

    @Test
    void withoutAPathOrAFileEveryLookupIsEmpty() throws IOException {
        GeoIpCountryLookup off = new GeoIpCountryLookup(properties(""));
        assertThat(off.isLoaded()).isFalse();
        assertThat(off.countryCode(ip("203.0.113.7"))).isEmpty();
        off.reloadIfChanged();
        assertThat(off.isLoaded()).isFalse();

        Path db = dir.resolve("later.mmdb");
        GeoIpCountryLookup waiting = new GeoIpCountryLookup(properties(db.toString()));
        assertThat(waiting.isLoaded()).isFalse();
        assertThat(waiting.countryCode(ip("203.0.113.7"))).isEmpty();

        writeFixture(db, "EG", "Egypt");          // the first deploy copies the file
        waiting.reloadIfChanged();
        assertThat(waiting.countryCode(ip("203.0.113.7"))).contains("EG");
    }

    @Test
    void aReplacedFileIsReloadedAndABrokenReplacementKeepsThePreviousDatabase() throws IOException {
        Path db = dir.resolve("country.mmdb");
        writeFixture(db, "EG", "Egypt");
        Files.setLastModifiedTime(db, FileTime.fromMillis(1_000_000L));
        GeoIpCountryLookup lookup = new GeoIpCountryLookup(properties(db.toString()));
        assertThat(lookup.countryCode(ip("203.0.113.7"))).contains("EG");

        writeFixture(db, "JO", "Jordan");         // next month's file
        Files.setLastModifiedTime(db, FileTime.fromMillis(2_000_000L));
        lookup.reloadIfChanged();
        assertThat(lookup.countryCode(ip("203.0.113.7"))).contains("JO");

        Files.write(db, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        Files.setLastModifiedTime(db, FileTime.fromMillis(3_000_000L));
        lookup.reloadIfChanged();
        assertThat(lookup.countryCode(ip("203.0.113.7"))).as("previous database stays in use").contains("JO");

        Files.delete(db);
        lookup.reloadIfChanged();
        assertThat(lookup.countryCode(ip("203.0.113.7"))).as("still served from memory").contains("JO");
    }

    @Test
    void aFileThatIsNotADatabaseLeavesLookupsOff() throws IOException {
        Path db = dir.resolve("broken.mmdb");
        Files.writeString(db, "<html>not found</html>");

        GeoIpCountryLookup lookup = new GeoIpCountryLookup(properties(db.toString()));

        assertThat(lookup.isLoaded()).isFalse();
        assertThat(lookup.countryCode(ip("203.0.113.7"))).isEmpty();
    }

    @Test
    void aRecordWithoutCountryGivesEmpty() throws IOException {
        Path db = dir.resolve("continent-only.mmdb");
        new MmdbTestWriter("DBIP-Country-Lite")
                .add("203.0.113.0/24", Map.of("continent", Map.of("code", "AF")))
                .write(db);

        assertThat(new GeoIpCountryLookup(properties(db.toString())).countryCode(ip("203.0.113.7")))
                .isEqualTo(Optional.empty());
    }

    static void writeFixture(Path db, String code203, String name203) throws IOException {
        new MmdbTestWriter("DBIP-Country-Lite")
                .add("203.0.113.0/24", MmdbTestWriter.country(code203, name203, 357_994L, "AF"))
                .add("198.51.100.0/24", MmdbTestWriter.country("SA", "Saudi Arabia", 102_358L, "AS"))
                .add("2001:db8::/32", MmdbTestWriter.country("AE", "United Arab Emirates", 290_557L, "AS"))
                .write(db);
    }

    private static GeoIpProperties properties(String path) {
        GeoIpProperties properties = new GeoIpProperties();
        properties.setDbPath(path);
        return properties;
    }

    private static InetAddress ip(String literal) throws IOException {
        return InetAddress.getByName(literal);
    }
}
