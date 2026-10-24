/*
 * This file is part of ViaVersion - https://github.com/ViaVersion/ViaVersion
 * Copyright (C) 2016-2026 ViaVersion and contributors
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.viaversion.viaversion.client;

import com.google.gson.JsonObject;
import com.viaversion.viaversion.ViaAPIBase;
import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaAPI;
import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.ViaInjector;
import com.viaversion.viaversion.api.platform.ViaPlatform;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.protocol.ProtocolManagerImpl;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.logging.Logger;

/**
 * Wyrm inside an Eaglercraft client: Via runs in the game itself, so the client can join servers that speak another
 * protocol than the game, with nothing installed on the server.
 *
 * <p>The game is the "player" and its protocol is the <em>native</em> protocol. For each connection Via is told the
 * server's protocol and translates both ways. Servers older than the game need only Wyrm. Servers newer than the
 * game also need the Wyvern protocols registered (see {@link #supports}); until they are, only older servers are
 * reachable.
 *
 * <p>Use: call {@link #start()} off the render thread (it loads mapping data), then for each connection
 * {@link ClientPipeline#install}. Eagler proxies log the player in themselves, see {@link EaglerLogin}.
 */
public final class ClientVia {

    private static final Logger LOGGER = Logger.getLogger("Via");
    private static final Object LOCK = new Object();
    private static volatile boolean started;
    private static volatile int traceLimit;
    private static String clientName = "Eaglercraft";
    private static String clientVersion = "";

    private ClientVia() {
    }

    /** Names the client in Via's platform info. Only has an effect before {@link #start()}. */
    public static void setClient(final String name, final String version) {
        synchronized (LOCK) {
            clientName = name;
            clientVersion = version;
        }
    }

    /** How many packets per direction are logged at the start of each connection (default 0), to show where a join goes wrong. */
    public static void setTraceLimit(final int packets) {
        traceLimit = packets;
    }

    static int traceLimit() {
        return traceLimit;
    }

    public static boolean isStarted() {
        return started;
    }

    /** A release a server can speak. */
    public record Release(int protocol, String name) {
    }

    /**
     * Every release from 1.8 on that Via knows other than the game's own, newest first. Whether a given one can be
     * reached is answered by {@link #supports} once Via has started.
     */
    public static List<Release> releases(final int nativeProtocol) {
        final List<Release> releases = new ArrayList<>();
        for (final ProtocolVersion version : ProtocolVersion.getProtocols()) {
            if (version.getVersionType() == VersionType.RELEASE
                && version.getVersion() >= ProtocolVersion.v1_8.getVersion()
                && version.getVersion() != nativeProtocol) {
                releases.add(new Release(version.getVersion(), version.getName()));
            }
        }
        releases.sort((a, b) -> Integer.compare(b.protocol(), a.protocol()));
        return releases;
    }

    /**
     * Whether a game speaking {@code nativeProtocol} can join a server speaking {@code serverProtocol}. The game's own
     * protocol always works (no translation). Once started this asks Via for a protocol path, so servers newer than
     * the game count only when the protocols that go there (Wyvern) are registered; before that, any older release.
     */
    public static boolean supports(final int nativeProtocol, final int serverProtocol) {
        if (nativeProtocol == serverProtocol) {
            return true;
        }
        final ProtocolVersion server = release(serverProtocol);
        final ProtocolVersion client = release(nativeProtocol);
        if (server == null || client == null || server.getVersion() < ProtocolVersion.v1_8.getVersion()) {
            return false;
        }
        if (!started) {
            return server.getVersion() < client.getVersion();
        }
        return Via.getManager().getProtocolManager().getProtocolPath(client, server) != null;
    }

    static ProtocolVersion release(final int protocol) {
        return ProtocolVersion.isRegistered(VersionType.RELEASE, protocol)
            ? ProtocolVersion.getProtocol(VersionType.RELEASE, protocol)
            : null;
    }

    /**
     * Starts Via if it is not running and waits for its mapping data. Blocks, so call it off the render thread, and
     * before the first connection opens so a slow first load cannot make the connection idle out.
     *
     * @param addons run once, when Via first starts, after it is up and before it works out its protocol paths. Wyvern
     *               registers its protocols here (see its {@code WyvernClient}), which is what makes servers newer than
     *               the game reachable. Ignored if Via is already running.
     */
    public static void start(final Runnable... addons) {
        synchronized (LOCK) {
            if (!started) {
                doStart(addons);
                started = true;
            }
        }
        awaitMappings();
    }

    /** Waits for the mapping data Via loads on its own pool, so a handshake does not race it. */
    private static void awaitMappings() {
        final ProtocolManagerImpl protocols = (ProtocolManagerImpl) Via.getManager().getProtocolManager();
        // Propagating errors: a protocol whose data failed to load would otherwise fail mid-game.
        while (!protocols.hasLoadedMappings() && !protocols.checkForMappingCompletion(true)) {
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while Via loaded its mappings", e);
            }
        }
    }

    private static void doStart(final Runnable[] addons) {
        final long began = System.currentTimeMillis();
        final Platform platform = new Platform(new File("via"), clientName, clientVersion);
        final ViaManagerImpl manager = ViaManagerImpl.builder()
            .platform(platform)
            .injector(new Injector())
            .loader(new Loader())
            .commandHandler(new ViaCommandHandler(false))
            .build();
        Via.init(manager);
        platform.getConf().reload();
        manager.init();
        manager.onServerLoaded();
        for (final Runnable addon : addons) {
            addon.run();
        }
        final ProtocolManagerImpl protocols = (ProtocolManagerImpl) manager.getProtocolManager();
        // A client can take any path to a server, however long.
        protocols.setMaxProtocolPathSize(Integer.MAX_VALUE);
        protocols.setMaxPathDeltaIncrease(-1);
        protocols.refreshVersions();
        LOGGER.info("Via " + platform.getPluginVersion() + " started in " + (System.currentTimeMillis() - began) + " ms");
    }

    /** Via's view of the game: a proxy in front of servers of every version. */
    private static final class Platform implements ViaPlatform<UserConnection> {

        private final File folder;
        private final String name;
        private final String version;
        private final ViaVersionConfig config;
        private final ViaAPI<UserConnection> api = new ViaAPIBase<>() {
            @Override
            public ProtocolVersion getPlayerProtocolVersion(final UserConnection player) {
                return player.getProtocolInfo().protocolVersion();
            }

            @Override
            public void sendRawPacket(final UserConnection player, final ByteBuf packet) {
                player.scheduleSendRawPacket(packet);
            }
        };

        Platform(final File folder, final String name, final String version) {
            this.folder = folder;
            this.name = name;
            this.version = version;
            this.config = new ClientConfig(new File("viaversion.yml"), LOGGER);
        }

        @Override
        public Logger getLogger() {
            return LOGGER;
        }

        @Override
        public String getPlatformName() {
            return name;
        }

        @Override
        public String getPlatformVersion() {
            return version;
        }

        @Override
        public boolean isProxy() {
            return true;
        }

        @Override
        public ViaAPI<UserConnection> getApi() {
            return api;
        }

        @Override
        public ViaVersionConfig getConf() {
            return config;
        }

        @Override
        public File getDataFolder() {
            return folder;
        }
    }

    /** Nothing to inject: each connection's pipeline gets Via from {@link ClientPipeline}. */
    private static final class Injector implements ViaInjector {

        @Override
        public void inject() {
        }

        @Override
        public void uninject() {
        }

        @Override
        public ProtocolVersion getServerProtocolVersion() {
            return getServerProtocolVersions().first();
        }

        /** Every version: the server behind each connection is whichever one it names. */
        @Override
        public SortedSet<ProtocolVersion> getServerProtocolVersions() {
            final SortedSet<ProtocolVersion> versions = new ObjectLinkedOpenHashSet<>();
            versions.addAll(ProtocolVersion.getProtocols());
            return versions;
        }

        @Override
        public String getEncoderName() {
            return ClientPipeline.ENCODER;
        }

        @Override
        public String getDecoderName() {
            return ClientPipeline.DECODER;
        }

        @Override
        public JsonObject getDump() {
            return new JsonObject();
        }
    }

    /** Registers the per-connection server version with Via in place of its own guess. */
    private static final class Loader implements ViaPlatformLoader {

        @Override
        public void load() {
            Via.getManager().getProviders().use(VersionProvider.class, new ServerVersionProvider());
        }

        @Override
        public void unload() {
        }
    }
}
