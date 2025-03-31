/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.backend;

import static org.amnezia.awg.GoBackend.awgGetConfig;
import static org.amnezia.awg.GoBackend.awgGetSocketV4;
import static org.amnezia.awg.GoBackend.awgGetSocketV6;
import static org.amnezia.awg.GoBackend.awgTurnOff;
import static org.amnezia.awg.GoBackend.awgTurnOn;
import static org.amnezia.awg.GoBackend.awgVersion;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import com.zaneschepke.droiddns.DnsResolver;
import com.zaneschepke.droiddns.JavaDnsResolver;
import org.amnezia.awg.backend.BackendException.Reason;
import org.amnezia.awg.backend.Tunnel.State;
import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.InetEndpoint;
import org.amnezia.awg.config.InetNetwork;
import org.amnezia.awg.config.Peer;
import org.amnezia.awg.crypto.Key;
import org.amnezia.awg.crypto.KeyFormatException;
import org.amnezia.awg.util.NonNullForAll;
import org.amnezia.awg.util.SharedLibraryLoader;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Implementation of {@link Backend} that uses the amneziawg-go userspace implementation to provide
 * AmneziaWG tunnels.
 */
@NonNullForAll
public final class GoBackend implements Backend {
    private static final int DNS_RESOLUTION_RETRIES = 3;
    private static final String TAG = "AmneziaWG/GoBackend";
    @Nullable private static AlwaysOnCallback alwaysOnCallback;
    private static CompletableFuture<VpnService> vpnService = new CompletableFuture<>();
    private final Context context;
    private final TunnelActionHandler tunnelActionHandler;
    @Nullable private Config currentConfig;
    @Nullable private Tunnel currentTunnel;
    private int currentTunnelHandle = -1;
    private BackendState backendState = BackendState.INACTIVE;
    private BackendState backendSettingState = BackendState.INACTIVE;
    private Collection<String> allowedIps = Collections.emptyList();
    private final DnsResolver dnsResolver;

    /**
     * Public constructor for GoBackend.
     *
     * @param context An Android {@link Context}
     */
    public GoBackend(final Context context, final TunnelActionHandler tunnelActionHandler) {
        SharedLibraryLoader.loadSharedLibrary(context, "am-go");
        this.context = context;
        this.tunnelActionHandler = tunnelActionHandler;
        this.dnsResolver = new JavaDnsResolver(context);
    }

    /**
     * Set a {@link AlwaysOnCallback} to be invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     *
     * @param cb Callback to be invoked
     */
    public static void setAlwaysOnCallback(final AlwaysOnCallback cb) {
        alwaysOnCallback = cb;
    }



    /**
     * Method to get the names of running tunnels.
     *
     * @return A set of string values denoting names of running tunnels.
     */
    @Override
    public Set<String> getRunningTunnelNames() {
        if (currentTunnel != null) {
            final Set<String> runningTunnels = new ArraySet<>();
            runningTunnels.add(currentTunnel.getName());
            return runningTunnels;
        }
        return Collections.emptySet();
    }

    /**
     * Get the associated {@link State} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to examine the state of.
     * @return {@link State} associated with the given tunnel.
     */
    @Override
    public State getState(final Tunnel tunnel) {
        return currentTunnel == tunnel ? State.UP : State.DOWN;
    }

    @Override
    public BackendState getBackendState() throws Exception {
        return backendState;
    }

    /**
     * Get the associated {@link Statistics} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to retrieve statistics for.
     * @return {@link Statistics} associated with the given tunnel.
     */
    @Override
    public Statistics getStatistics(final Tunnel tunnel) {
        final Statistics stats = new Statistics();
        if (tunnel != currentTunnel || currentTunnelHandle == -1)
            return stats;
        final String config = awgGetConfig(currentTunnelHandle);
        if (config == null)
            return stats;
        Key key = null;
        long rx = 0;
        long tx = 0;
        long latestHandshakeMSec = 0;
        for (final String line : config.split("\\n")) {
            if (line.startsWith("public_key=")) {
                if (key != null)
                    stats.add(key, rx, tx, latestHandshakeMSec);
                rx = 0;
                tx = 0;
                latestHandshakeMSec = 0;
                try {
                    key = Key.fromHex(line.substring(11));
                } catch (final KeyFormatException ignored) {
                    key = null;
                }
            } else if (line.startsWith("rx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    rx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    rx = 0;
                }
            } else if (line.startsWith("tx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    tx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    tx = 0;
                }
            } else if (line.startsWith("last_handshake_time_sec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(24)) * 1000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            } else if (line.startsWith("last_handshake_time_nsec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(25)) / 1000000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            }
        }
        if (key != null)
            stats.add(key, rx, tx, latestHandshakeMSec);
        return stats;
    }

    /**
     * Get the version of the underlying amneziawg-go library.
     *
     * @return {@link String} value of the version of the amneziawg-go library.
     */
    @Override
    public String getVersion() {
        return awgVersion();
    }

    /**
     * Change the state of a given {@link Tunnel}, optionally applying a given {@link Config}.
     *
     * @param tunnel The tunnel to control the state of.
     * @param state  The new state for this tunnel. Must be {@code UP}, {@code DOWN}, or
     *               {@code TOGGLE}.
     * @param config The configuration for this tunnel, may be null if state is {@code DOWN}.
     * @return {@link State} of the tunnel after state changes are applied.
     * @throws Exception Exception raised while changing tunnel state.
     */
    @Override
    public State setState(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
        final State originalState = getState(tunnel);
        if (state == originalState && tunnel == currentTunnel && config == currentConfig)
            return originalState;
        if (state == State.UP) {
            final Config originalConfig = currentConfig;
            final Tunnel originalTunnel = currentTunnel;
            if (currentTunnel != null)
                setStateInternal(currentTunnel, null, State.DOWN);
            try {
                setStateInternal(tunnel, config, state);
            } catch (final Exception e) {
                if (originalTunnel != null)
                    setStateInternal(originalTunnel, originalConfig, State.UP);
                throw e;
            }
        } else if (state == State.DOWN && tunnel == currentTunnel) {
            setStateInternal(tunnel, null, State.DOWN);
        }
        return getState(tunnel);
    }

    @Override
    public BackendState setBackendState(BackendState backendState, Collection<String> allowedIps) throws Exception {
        Log.d(TAG, "Set backend state");
        this.backendSettingState = backendState;
        //already active, return
        if(this.backendState == backendState && this.allowedIps.equals(allowedIps)) {
            Log.d(TAG, "Already state set");
            return backendState;
        }
        //tunnel running, set allowedIps and return
        if(currentTunnel != null) {
            Log.d(TAG, "Tunnel already running");
            this.allowedIps = allowedIps;
            return backendState;
        }
        switch (backendSettingState) {
            case KILL_SWITCH_ACTIVE -> {
                Log.d(TAG, "Starting kill switch");
                activateKillSwitch(allowedIps);
            }
            case SERVICE_ACTIVE -> {
                activateService();
            }
            case INACTIVE -> {
                Log.d(TAG, "Inactive, shutting down");
                shutdown();
            }
        }
        return backendSettingState;
    }

    private void shutdown() throws Exception {
        Log.d(TAG, "Shutdown..");
        if(backendState == BackendState.INACTIVE) return;
        Log.d(TAG, "Shutting down vpn service");
        try {
            VpnService service = vpnService.get(0, TimeUnit.NANOSECONDS);
            Log.d(TAG, "Turning off killswitch");
            if(backendState == BackendState.KILL_SWITCH_ACTIVE) service.deactivateKillSwitch();
            Log.d(TAG, "Stopping self");
            service.stopSelf();
        } catch (final TimeoutException | ExecutionException | InterruptedException e) {
            final Exception be = new BackendException(Reason.SERVICE_NOT_RUNNING);
            be.initCause(e);
            throw be;
        }
    }

    private void setStateInternal(final Tunnel tunnel, @Nullable final Config config, final State state)
            throws Exception {
        Log.i(TAG, "Bringing tunnel " + tunnel.getName() + ' ' + state);

        if (state == State.UP) {
            if (config == null)
                throw new BackendException(Reason.TUNNEL_MISSING_CONFIG);

            if (VpnService.prepare(context) != null)
                throw new BackendException(Reason.VPN_NOT_AUTHORIZED);

            final VpnService service;
            if (!vpnService.isDone()) {
                Log.d(TAG, "Requesting to start VpnService");
                context.startService(new Intent(context, VpnService.class));
            }

            try {
                service = vpnService.get(2, TimeUnit.SECONDS);
            } catch (final TimeoutException e) {
                final Exception be = new BackendException(Reason.UNABLE_TO_START_VPN);
                be.initCause(e);
                throw be;
            }
            service.setOwner(this);

            if (currentTunnelHandle != -1) {
                Log.w(TAG, "Tunnel already up");
                return;
            }


            for (final Peer peer : config.getPeers()) {
                final InetEndpoint ep = peer.getEndpoint().orElse(null);
                if (ep == null) continue;
                final List<String> resolved = dnsResolver.resolveDns(ep.getHost(),tunnel.isIpv4ResolutionPreferred(), false);
                if(resolved.isEmpty()) throw new BackendException(Reason.DNS_RESOLUTION_FAILURE);
                Log.d(TAG, "Resolved DN: " + resolved.get(0));
                ep.setResolved(resolved.get(0));
            }

            // Build config
            final String goConfig = config.toAwgUserspaceString();


            // Create the vpn tunnel with android API
            final VpnService.Builder builder = service.getBuilder();
            builder.setSession(tunnel.getName());

            for (final String excludedApplication : config.getInterface().getExcludedApplications())
                builder.addDisallowedApplication(excludedApplication);

            for (final String includedApplication : config.getInterface().getIncludedApplications())
                builder.addAllowedApplication(includedApplication);

            for (final InetNetwork addr : config.getInterface().getAddresses())
                builder.addAddress(addr.getAddress(), addr.getMask());

            for (final InetAddress addr : config.getInterface().getDnsServers())
                builder.addDnsServer(addr.getHostAddress());

            for (final String dnsSearchDomain : config.getInterface().getDnsSearchDomains())
                builder.addSearchDomain(dnsSearchDomain);

            boolean sawDefaultRoute = false;
            for (final Peer peer : config.getPeers()) {
                for (final InetNetwork addr : peer.getAllowedIps()) {
                    if (addr.getMask() == 0)
                        sawDefaultRoute = true;
                    builder.addRoute(addr.getAddress(), addr.getMask());
                }
            }

            // "Kill-switch" semantics
            if (!(sawDefaultRoute && config.getPeers().size() == 1)) {
                builder.allowFamily(OsConstants.AF_INET);
                builder.allowFamily(OsConstants.AF_INET6);
            }

            builder.setMtu(config.getInterface().getMtu().orElse(1280));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                builder.setMetered(false);

            service.setUnderlyingNetworks(null);

            builder.setBlocking(true);
            service.deactivateKillSwitch();
            try (final ParcelFileDescriptor tun = builder.establish()) {
                if (tun == null)
                    throw new BackendException(Reason.TUN_CREATION_ERROR);
                Log.d(TAG, "Go backend " + awgVersion());
                tunnelActionHandler.runPreUp(config.getInterface().getPreUp());
                currentTunnelHandle = awgTurnOn(tunnel.getName(), tun.detachFd(), goConfig);
                tunnelActionHandler.runPostUp(config.getInterface().getPostUp());
            }
            if (currentTunnelHandle < 0)
                throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, currentTunnelHandle);

            currentTunnel = tunnel;
            currentConfig = config;

            service.protect(awgGetSocketV4(currentTunnelHandle));
            service.protect(awgGetSocketV6(currentTunnelHandle));
        } else {
            if (currentTunnelHandle == -1) {
                Log.w(TAG, "Tunnel already down");
                return;
            }
            int handleToClose = currentTunnelHandle;
            tunnelActionHandler.runPreDown(currentConfig.getInterface().getPreDown());
            awgTurnOff(handleToClose);
            tunnelActionHandler.runPostDown(currentConfig.getInterface().getPostDown());
            currentTunnel = null;
            currentTunnelHandle = -1;
            currentConfig = null;
            switch (backendSettingState) {
                case KILL_SWITCH_ACTIVE -> {
                    activateKillSwitch(this.allowedIps);
                }
                case SERVICE_ACTIVE -> {
                    activateService();
                }
                case INACTIVE -> {
                    shutdown();
                }
            }
        }
        tunnel.onStateChange(state);
    }

    private void activateService() {
        if (!vpnService.isDone()) {
            Log.d(TAG, "Requesting service activation");
            context.startService(new Intent(context, VpnService.class));
        }
        try {
            VpnService service = vpnService.get(2, TimeUnit.SECONDS);
            if(backendState == BackendState.KILL_SWITCH_ACTIVE) service.deactivateKillSwitch();
            Log.d(TAG, "Service is now active");
            service.setOwner(this);
            backendState = BackendState.SERVICE_ACTIVE;
        } catch (final TimeoutException | ExecutionException | InterruptedException ignored) {
            backendState = BackendState.INACTIVE;
        }
    }

    private void activateKillSwitch(Collection<String> allowedIps) {
        if(backendState == BackendState.KILL_SWITCH_ACTIVE && allowedIps.equals(this.allowedIps)) return;
        if (!vpnService.isDone() || !this.allowedIps.equals(allowedIps)) {
            activateService();
        }
        try {
            VpnService service = vpnService.get(0, TimeUnit.MILLISECONDS);
            this.allowedIps = allowedIps;
            service.activateKillSwitch(allowedIps);
        } catch (final TimeoutException | ExecutionException | InterruptedException ignored) {
            backendState = BackendState.INACTIVE;
        }
    }

    /**
     * Callback for {@link GoBackend} that is invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     */
    public interface AlwaysOnCallback {
        void alwaysOnTriggered();
    }

    /**
     * {@link android.net.VpnService} implementation for {@link GoBackend}
     */
    public static class VpnService extends android.net.VpnService {
        @Nullable private GoBackend owner;

        @Nullable private ParcelFileDescriptor mInterface;

        public Builder getBuilder() {
            return new Builder();
        }

        @Override
        public void onCreate() {
            vpnService.complete(this);
            super.onCreate();
        }

        @Override
        public void onDestroy() {
            if (owner != null) {
                final Tunnel tunnel = owner.currentTunnel;
                if (tunnel != null) {
                    if (owner.currentTunnelHandle != -1)
                        awgTurnOff(owner.currentTunnelHandle);
                    owner.currentTunnel = null;
                    owner.currentTunnelHandle = -1;
                    owner.currentConfig = null;
                    owner.backendState = BackendState.INACTIVE;
                    tunnel.onStateChange(State.DOWN);
                }

            }
            vpnService = new CompletableFuture<>();
            super.onDestroy();
        }

        void activateKillSwitch(Collection<String> allowedIps) {

            Builder builder = new Builder();
            Log.d(TAG, "Starting kill switch with allowedIps: " + allowedIps);
            builder.setSession("KillSwitchSession")
                    .addAddress("10.0.0.2", 32)
                    .addAddress("2001:db8::2", 64);
            if(allowedIps.isEmpty()) {
                builder
                        .addRoute("0.0.0.0", 0);
            } else allowedIps.forEach((net) -> {
                Log.d(TAG, "Adding allowedIp: " + net);
                String[] netSplit = net.split("/");
                builder.addRoute(netSplit[0], Integer.parseInt(netSplit[1]));
            });
            builder.addRoute("::", 0);


            try {
                mInterface = builder.establish();
                if(owner != null) owner.backendState = BackendState.KILL_SWITCH_ACTIVE;
            } catch (Exception ignored) {
                Log.e(TAG, "Failed to start kill switch");
            }
        }

        void deactivateKillSwitch() {
            if (mInterface != null) {
                try {
                    mInterface.close();
                    Log.d(TAG, "FD closed");
                    mInterface = null;
                    if(owner != null) owner.backendState = BackendState.SERVICE_ACTIVE;
                } catch (IOException e) {
                    Log.e(TAG, "Failed to stop kill switch");
                }
            } else Log.w(TAG, "FD unable to close because it is null");
        }

        @Override
        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
            vpnService.complete(this);
            if (intent == null || intent.getComponent() == null || !intent.getComponent().getPackageName().equals(getPackageName())) {
                Log.d(TAG, "Service started by Always-on VPN feature");
                if (alwaysOnCallback != null)
                    alwaysOnCallback.alwaysOnTriggered();
            }
            return super.onStartCommand(intent, flags, startId);
        }

        public void setOwner(final GoBackend owner) {
            this.owner = owner;
            owner.backendState = BackendState.SERVICE_ACTIVE;
        }
    }
}
