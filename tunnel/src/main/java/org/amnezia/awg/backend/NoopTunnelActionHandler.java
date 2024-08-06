package org.amnezia.awg.backend;

import java.util.Collection;

/**
 * A {@link TunnelActionHandler} implementation that does not execute any scripts.
 */
public final class NoopTunnelActionHandler implements TunnelActionHandler {

    @Override
    public void runPreUp(final Collection<String> scripts) {

    }

    @Override
    public void runPostUp(final Collection<String> scripts) {

    }

    @Override
    public void runPreDown(final Collection<String> scripts) {

    }

    @Override
    public void runPostDown(final Collection<String> scripts) {

    }
}
