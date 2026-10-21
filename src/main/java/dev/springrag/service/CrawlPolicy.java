package dev.springrag.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

public final class CrawlPolicy {
    private final URI seed;
    private final String pathPrefix;

    public CrawlPolicy(URI seed) {
        this.seed = normalize(seed);
        if (!"https".equals(this.seed.getScheme()) && !"http".equals(this.seed.getScheme())) {
            throw new IllegalArgumentException("HTTP(S) URL만 허용합니다");
        }
        if (!publicHost(this.seed.getHost())) throw new IllegalArgumentException("공개 호스트 URL만 허용합니다");
        String path = this.seed.getPath();
        this.pathPrefix = path.endsWith("/") ? path
            : path.matches("(?i).*\\.(html?|pdf|docx?|txt)$") ? path.substring(0, path.lastIndexOf('/') + 1)
            : path + "/";
    }

    public boolean accepts(URI candidate) {
        try {
            URI url = normalize(candidate);
            return seed.getScheme().equals(url.getScheme()) && seed.getHost().equals(url.getHost())
                && seed.getPort() == url.getPort() && (url.equals(seed) || url.getPath().startsWith(pathPrefix))
                && publicHost(url.getHost());
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void requirePublicResolution(URI candidate) throws UnknownHostException {
        for (InetAddress address : InetAddress.getAllByName(candidate.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("사설 주소는 수집할 수 없습니다");
            }
        }
    }

    public static URI normalize(URI uri) {
        URI normalized = uri.normalize();
        if (normalized.getHost() == null || normalized.getRawUserInfo() != null) throw new IllegalArgumentException("잘못된 URL");
        return URI.create(normalized.getScheme().toLowerCase(Locale.ROOT) + "://"
            + normalized.getHost().toLowerCase(Locale.ROOT)
            + (normalized.getPort() < 0 ? "" : ":" + normalized.getPort())
            + (normalized.getRawPath().isEmpty() ? "/" : normalized.getRawPath())
            + (normalized.getRawQuery() == null ? "" : "?" + normalized.getRawQuery()));
    }

    private static boolean publicHost(String host) {
        if (host == null || host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
            }
            return true;
        } catch (UnknownHostException e) {
            // DNS may be unavailable while validating; the request will fail closed at fetch time.
            return !host.matches("(?i)(10|127|169\\.254|172\\.(1[6-9]|2[0-9]|3[01])|192\\.168)\\..*")
                && !host.contains(":");
        }
    }
}
