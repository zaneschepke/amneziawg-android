package org.amnezia.awg.backend;

import java.util.Collection;

/**
 * Handles executing Pre/Post Up/Down scripts when the state of the WireGuard tunnel changes
 */
public interface TunnelActionHandler {

    /**
     * Execute scripts before bringing up the tunnel
     *
     * @param scripts Collection of scripts to execute
     */
    void runPreUp(Collection<String> scripts);

    /**
     * Execute scripts after bringing up the tunnel
     *
     * @param scripts Collection of scripts to execute
     */
    void runPostUp(Collection<String> scripts);

    /**
     * Execute scripts before bringing down the tunnel
     *
     * @param scripts Collection of scripts to execute
     */
    void runPreDown(Collection<String> scripts);

    /**
     * Execute scripts after bringing down the tunnel
     *
     * @param scripts Collection of scripts to execute
     */
    void runPostDown(Collection<String> scripts);
}
