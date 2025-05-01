/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.config;

import android.util.Log;
import org.amnezia.awg.util.NonNullForAll;

import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

import androidx.annotation.Nullable;


/**
 * An external endpoint (host and port) used to connect to an AmneziaWG {@link Peer}.
 * <p>
 * Instances of this class are externally immutable.
 */
@NonNullForAll
public final class InetEndpoint {
    private static final Pattern BARE_IPV6 = Pattern.compile("^[^\\[\\]]*:[^\\[\\]]*");
    private static final Pattern FORBIDDEN_CHARACTERS = Pattern.compile("[/?#]");
    private static final String TAG = "PEER";

    private static final long DEFAULT_TTL_SECONDS = 300;
    private static final long NEGATIVE_TTL_SECONDS = 30;

    private final String host;
    private final boolean isResolved;
    private final Object lock = new Object();
    private final int port;
    private Instant lastResolution = Instant.MIN;
    private Instant lastFailedResolution = Instant.MIN;
    @Nullable private InetEndpoint resolved;

    private InetEndpoint(final String host, final boolean isResolved, final int port) {
        this.host = host;
        this.isResolved = isResolved;
        this.port = port;
    }

    public static InetEndpoint parse(final String endpoint) throws ParseException {
        if (FORBIDDEN_CHARACTERS.matcher(endpoint).find())
            throw new ParseException(InetEndpoint.class, endpoint, "Forbidden characters");
        final URI uri;
        try {
            uri = new URI("awg://" + endpoint);
        } catch (final URISyntaxException e) {
            throw new ParseException(InetEndpoint.class, endpoint, e);
        }
        if (uri.getPort() < 0 || uri.getPort() > 65535)
            throw new ParseException(InetEndpoint.class, endpoint, "Missing/invalid port number");
        try {
            InetAddresses.parse(uri.getHost());
            // Parsing ths host as a numeric address worked, so we don't need to do DNS lookups.
            return new InetEndpoint(uri.getHost(), true, uri.getPort());
        } catch (final ParseException ignored) {
            // Failed to parse the host as a numeric address, so it must be a DNS hostname/FQDN.
            return new InetEndpoint(uri.getHost(), false, uri.getPort());
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof InetEndpoint other))
            return false;
        return host.equals(other.host) && port == other.port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * Generate an {@code InetEndpoint} instance with the same port and the host resolved using DNS
     * to a numeric address. If the host is already numeric, the existing instance may be returned.
     * Because this function may perform network I/O, it must not be called from the main thread.
     * @param preferIpv4 whether ipv4 resolution should be preferred over the default ipv6
     * @return the resolved endpoint, or {@link Optional#empty()}
     */
    public Optional<InetEndpoint> getResolved(Boolean preferIpv4) {
        if (isResolved) return Optional.of(this);
        if (Duration.between(lastResolution, Instant.now()).getSeconds() <= DEFAULT_TTL_SECONDS) {
            synchronized (lock) {
                return Optional.ofNullable(resolved);
            }
        }
        if (Duration.between(lastFailedResolution, Instant.now()).getSeconds() <= NEGATIVE_TTL_SECONDS) {
            return Optional.empty();
        }
        synchronized (lock) {
            if (Duration.between(lastResolution, Instant.now()).getSeconds() <= DEFAULT_TTL_SECONDS) {
                return Optional.ofNullable(resolved);
            }
            try {
                final InetAddress[] candidates = InetAddress.getAllByName(host);
                if (candidates == null || candidates.length == 0) {
                    Log.w(TAG,"No addresses resolved for host: " + host);
                    lastFailedResolution = Instant.now();
                    resolved = null;
                    return Optional.empty();
                }
                InetAddress address = candidates[0];
                if (preferIpv4) {
                    for (final InetAddress candidate : candidates) {
                        if (candidate instanceof Inet4Address) {
                            address = candidate;
                            break;
                        }
                    }
                }
                resolved = new InetEndpoint(address.getHostAddress(), true, port);
                lastResolution = Instant.now();
            } catch (final UnknownHostException e) {
                Log.w(TAG,"Failed to resolve host " + host + ": " + e.getMessage());
                lastFailedResolution = Instant.now();
                resolved = null;
            }
            return Optional.ofNullable(resolved);
        }
    }

    @Override
    public int hashCode() {
        return host.hashCode() ^ port;
    }

    @Override
    public String toString() {
        final boolean isBareIpv6 = isResolved && BARE_IPV6.matcher(host).matches();
        return (isBareIpv6 ? '[' + host + ']' : host) + ':' + port;
    }
}
