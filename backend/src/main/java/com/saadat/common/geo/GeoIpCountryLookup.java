package com.saadat.common.geo;

import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader.FileMode;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * IP → country from a MaxMind-format database: in production DB-IP's "IP to Country Lite" (CC BY 4.0), downloaded
 * monthly by {@code deploy-vps.yml} to {@code /opt/saadat/geoip} and mounted read-only. Path {@code app.geoip.db-path}
 * (env {@code GEOIP_DB_PATH}); without a path or a file every lookup is empty, so pricing behaves as before.
 *
 * <p>The file is read into memory, so replacing it on disk never disturbs running lookups. Every
 * {@value #CHECK_MINUTES} minutes its modification time and size are compared and a changed file is loaded without a
 * restart; a file that cannot be opened is logged once and the previously loaded database stays in use.
 */
@Slf4j
@Component
public class GeoIpCountryLookup implements IpCountryLookup {

    static final long CHECK_MINUTES = 10;

    private final Path path;
    private final AtomicReference<Loaded> loaded = new AtomicReference<>();
    /** The file version (or absence) last reported in the log, so a broken or missing file is logged once. */
    private volatile FileVersion reported;

    public GeoIpCountryLookup(GeoIpProperties properties) {
        String configured = properties.getDbPath() == null ? "" : properties.getDbPath().trim();
        this.path = configured.isEmpty() ? null : Path.of(configured);
        if (path == null) {
            log.info("GeoIP lookup off: app.geoip.db-path (GEOIP_DB_PATH) is empty");
        } else {
            reloadIfChanged();
        }
    }

    /** True once a database has been loaded. */
    public boolean isLoaded() {
        return loaded.get() != null;
    }

    @Override
    public Optional<String> countryCode(InetAddress address) {
        Loaded current = loaded.get();
        if (current == null || address == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(current.reader().country(address).getCountry().getIsoCode());
        } catch (AddressNotFoundException e) {
            return Optional.empty();
        } catch (IOException | GeoIp2Exception | RuntimeException e) {
            log.debug("GeoIP lookup failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Loads the database when the file appeared or changed since the last load. */
    @Scheduled(initialDelay = CHECK_MINUTES, fixedDelay = CHECK_MINUTES, timeUnit = TimeUnit.MINUTES)
    public void reloadIfChanged() {
        if (path == null) {
            return;
        }
        FileVersion version;
        try {
            if (!Files.isRegularFile(path)) {
                boolean none = loaded.get() == null;
                report(FileVersion.MISSING, () -> log.warn("GeoIP database {} not found; {}", path,
                        none ? "countries come from CF-IPCountry or the default" : "the loaded database stays in use"));
                return;
            }
            version = new FileVersion(Files.getLastModifiedTime(path), Files.size(path));
        } catch (IOException | RuntimeException e) {
            log.warn("GeoIP database {} cannot be read: {}", path, e.toString());
            return;
        }
        Loaded current = loaded.get();
        if ((current != null && current.version().equals(version)) || version.equals(reported)) {
            return;
        }
        try {
            DatabaseReader reader = new DatabaseReader.Builder(path.toFile())
                    .fileMode(FileMode.MEMORY)
                    .withCache(new CHMCache())
                    .build();
            loaded.set(new Loaded(reader, version));
            reported = null;
            log.info("GeoIP database loaded: {} ({}, built {})", path, reader.getMetadata().getDatabaseType(),
                    reader.getMetadata().getBuildDate().toInstant());
        } catch (IOException | RuntimeException e) {
            report(version, () -> log.warn("GeoIP database {} could not be opened ({}); {}", path, e.toString(),
                    current == null ? "lookups stay off" : "the previously loaded database stays in use"));
        }
    }

    private void report(FileVersion version, Runnable logLine) {
        if (!version.equals(reported)) {
            reported = version;
            logLine.run();
        }
    }

    private record FileVersion(FileTime modified, long size) {
        static final FileVersion MISSING = new FileVersion(FileTime.fromMillis(0), -1);
    }

    private record Loaded(DatabaseReader reader, FileVersion version) {
    }
}
