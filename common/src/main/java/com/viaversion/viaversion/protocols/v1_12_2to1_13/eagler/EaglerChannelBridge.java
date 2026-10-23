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

package com.viaversion.viaversion.protocols.v1_12_2to1_13.eagler;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;

/**
 * Carries Eaglercraft's plugin channels across the 1.12.2 / 1.13 boundary, where channel names stop being free text.
 * A 1.13+ Eaglercraft client behind a 1.12.2 (or older) server sees the server's {@code EAG|...} channels as
 * {@code eag:...}, and the client's replies go back under the spelling the server used.
 */
public final class EaglerChannelBridge {

    private EaglerChannelBridge() {
    }

    /**
     * Server to client: the 1.13+ name for the server's Eaglercraft channel, or null when the channel is not an
     * Eaglercraft one (or the bridge is off) and the regular mapping should decide. Remembers the original spelling.
     */
    public static String toModern(final UserConnection user, final String legacyChannel) {
        if (!Via.getConfig().isEaglerChannelBridge()
            || !EaglerChannels.isLegacyEaglerChannel(legacyChannel, Via.getConfig().getEaglerExtraChannels())) {
            return null;
        }
        final String modern = EaglerChannels.toModern(legacyChannel);
        EaglerChannelStorage storage = user.get(EaglerChannelStorage.class);
        if (storage == null) {
            storage = new EaglerChannelStorage();
            user.put(storage);
        }
        storage.learn(legacyChannel, modern);
        return modern;
    }

    /**
     * Client to server: the spelling the server uses for a 1.13+ channel, or null when the server has not used that
     * channel (then the regular mapping decides).
     */
    public static String toLegacy(final UserConnection user, final String modernChannel) {
        if (!Via.getConfig().isEaglerChannelBridge() || !EaglerChannels.isModernEaglerChannel(modernChannel)) {
            return null;
        }
        final EaglerChannelStorage storage = user.get(EaglerChannelStorage.class);
        return storage != null ? storage.legacyOf(modernChannel) : null;
    }
}
