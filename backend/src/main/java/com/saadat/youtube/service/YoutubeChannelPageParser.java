package com.saadat.youtube.service;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the channel id ({@code UC…}) a YouTube channel page declares for itself, strongest signal first: the
 * canonical link ({@code <link rel="canonical" href="https://www.youtube.com/channel/UC…">}), {@code "externalId"} in
 * the page data, the channel meta tags, the RSS link ({@code feeds/videos.xml?channel_id=UC…}) and {@code og:url};
 * finally {@code "channelId"} values, but only when every one of them names the same channel (a page also lists
 * other channels). Every result is a valid id: {@code UC} followed by 22 base64url characters.
 */
public final class YoutubeChannelPageParser {

    /** A YouTube channel id. */
    public static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");

    private static final String ID = "(UC[A-Za-z0-9_-]{22})(?![A-Za-z0-9_-])";
    private static final Pattern CANONICAL_LINK =
            Pattern.compile("<link\\b[^>]*\\brel\\s*=\\s*[\"']canonical[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANNEL_PATH = Pattern.compile("/channel/" + ID);
    private static final List<Pattern> OWN_CHANNEL = List.of(
            Pattern.compile("\"externalId\"\\s*:\\s*\"" + ID + "\""),
            Pattern.compile("<meta\\b[^>]*\\bitemprop\\s*=\\s*[\"'](?:identifier|channelId)[\"'][^>]*"
                    + "\\bcontent\\s*=\\s*[\"']" + ID, Pattern.CASE_INSENSITIVE),
            Pattern.compile("/feeds/videos\\.xml\\?channel_id=" + ID),
            Pattern.compile("<meta\\b[^>]*\\bproperty\\s*=\\s*[\"']og:url[\"'][^>]*\\bcontent\\s*=\\s*[\"'][^\"']*"
                    + "/channel/" + ID, Pattern.CASE_INSENSITIVE));
    private static final Pattern ANY_CHANNEL_ID = Pattern.compile("\"channelId\"\\s*:\\s*\"" + ID + "\"");

    private YoutubeChannelPageParser() {
    }

    /** The id the page declares for its own channel, or empty. */
    public static Optional<String> channelId(String html) {
        if (html == null || html.isEmpty()) {
            return Optional.empty();
        }
        Matcher canonical = CANONICAL_LINK.matcher(html);
        if (canonical.find()) {
            Matcher id = CHANNEL_PATH.matcher(canonical.group());
            if (id.find()) {
                return valid(id.group(1));
            }
        }
        for (Pattern pattern : OWN_CHANNEL) {
            Matcher m = pattern.matcher(html);
            if (m.find()) {
                return valid(m.group(1));
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher any = ANY_CHANNEL_ID.matcher(html);
        while (any.find()) {
            ids.add(any.group(1));
        }
        return ids.size() == 1 ? valid(ids.iterator().next()) : Optional.empty();
    }

    /** The id in a {@code …/channel/UC…} URL (nothing to fetch), or empty. */
    public static Optional<String> channelIdInUrl(URI url) {
        String path = url == null ? null : url.getRawPath();
        if (path == null) {
            return Optional.empty();
        }
        Matcher m = CHANNEL_PATH.matcher(path);
        return m.find() ? valid(m.group(1)) : Optional.empty();
    }

    public static boolean isChannelId(String value) {
        return value != null && CHANNEL_ID.matcher(value).matches();
    }

    private static Optional<String> valid(String id) {
        return isChannelId(id) ? Optional.of(id) : Optional.empty();
    }
}
