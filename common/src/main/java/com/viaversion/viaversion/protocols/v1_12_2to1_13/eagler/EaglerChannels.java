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

import java.util.Collection;
import java.util.Locale;

/**
 * Naming rules for the plugin channels Eaglercraft clients use.
 *
 * <p>Eaglercraft's custom channels carry names like {@code EAG|Skins-1.8}. Before 1.13 any string was a legal channel
 * name, but from 1.13 on a channel has to be a lower-case {@code namespace:path} identifier, and {@code |} and upper-case
 * letters are not allowed. ViaVersion therefore cannot translate such a channel for a 1.13+ server and drops it, which
 * is why Eagler clients that depend on these channels lose them behind a newer server.
 *
 * <p>This class defines a reversible mapping: {@code EAG|Skins-1.8} becomes {@code eag:skins-1.8}. The mapping is
 * one-way lossless only for the characters that are legal on both sides; the original spelling (upper-case letters) is
 * recovered from what the client itself registered, see {@link EaglerChannelStorage}.
 */
public final class EaglerChannels {

    /** Prefix of Eaglercraft's own channels as spelled by the client. */
    public static final String LEGACY_PREFIX = "EAG|";
    /** Namespace the channels get on 1.13+. */
    public static final String NAMESPACE = "eag";

    private EaglerChannels() {
    }

    /** Whether {@code channel} (pre-1.13 spelling) is an Eaglercraft channel; {@code extra} lists additional channel names. */
    public static boolean isLegacyEaglerChannel(final String channel, final Collection<String> extra) {
        return channel != null && (channel.startsWith(LEGACY_PREFIX) || extra != null && extra.contains(channel));
    }

    /** Whether {@code channel} is in the namespace this class maps Eaglercraft channels to. */
    public static boolean isModernEaglerChannel(final String channel) {
        return channel != null && channel.startsWith(NAMESPACE + ":");
    }

    /**
     * Converts a pre-1.13 Eaglercraft channel name to a valid 1.13+ identifier.
     * Upper-case letters are lowered and every character that identifiers do not allow becomes {@code _}.
     */
    public static String toModern(final String legacyChannel) {
        String name = legacyChannel.startsWith(LEGACY_PREFIX) ? legacyChannel.substring(LEGACY_PREFIX.length()) : legacyChannel;
        name = name.toLowerCase(Locale.ROOT);
        final StringBuilder path = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean legal = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || c == '/';
            path.append(legal ? c : '_');
        }
        if (path.length() == 0) {
            path.append('_');
        }
        return NAMESPACE + ":" + path;
    }
}
