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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stands in for the vanilla login on an Eagler connection that Via translates. An Eagler proxy logs the player in to
 * its backend itself, so after the Eagler handshake it expects play packets at once; it never sees the game's
 * handshake or Login Start. The game still sends them (Via needs to see them to learn the connection's state), so
 * they are swallowed on the way out, and a Login Success is handed back in the server's protocol version for Via to
 * translate. Whatever the server sends before that is held until the game has taken the login.
 *
 * <p>All packets are whole packets, packet id first, without a length prefix, in the server's protocol (so after the
 * Via encoder and before the Via decoder).
 */
public final class EaglerLogin {

    /** 1.16: the UUID is two longs instead of a string. */
    private static final int BINARY_UUID = 735;
    /** 1.19: Login Success carries the profile's properties. */
    private static final int PROFILE_PROPERTIES = 759;
    /** 1.20.2: the game acknowledges Login Success, and a configuration phase follows. */
    private static final int LOGIN_ACKNOWLEDGED = 764;
    /** 1.20.5 to 1.21.1: Login Success ends with a "strict error handling" flag. */
    private static final int STRICT_ERRORS_FROM = 766;
    private static final int STRICT_ERRORS_UNTIL = 767;
    /** 26.2: Login Success ends with a session id. */
    private static final int SESSION_ID = 776;

    private static final int HANDSHAKE = 0;
    private static final int LOGIN_START = 1;
    private static final int ACKNOWLEDGEMENT = 2;
    private static final int PLAY = 3;

    private final int serverProtocol;
    private final String name;
    private final UUID uuid;
    private final List<byte[]> held = new ArrayList<>();
    private int state = HANDSHAKE;
    private byte[] success;

    public EaglerLogin(final int serverProtocol, final String name, final UUID uuid) {
        this.serverProtocol = serverProtocol;
        this.name = name;
        this.uuid = uuid;
    }

    /**
     * Takes a packet the game wrote for the server. True if it is part of the vanilla login and must not be sent: the
     * handshake, Login Start, and from 1.20.2 the acknowledgement.
     */
    public boolean swallow(final byte[] packet) {
        switch (state) {
            case HANDSHAKE:
                state = LOGIN_START;
                return true;
            case LOGIN_START:
                success = loginSuccess();
                state = serverProtocol >= LOGIN_ACKNOWLEDGED ? ACKNOWLEDGEMENT : PLAY;
                return true;
            case ACKNOWLEDGEMENT:
                if (packet.length > 0 && (packet[0] & 0xFF) == 0x03) {
                    state = PLAY;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** Login Success to hand to the game (through Via) once, after Login Start was swallowed; null otherwise. */
    public byte[] takeLoginSuccess() {
        final byte[] out = success;
        success = null;
        return out;
    }

    /** Holds a packet from the server if the game has not taken the login yet; false means pass it on. */
    public boolean hold(final byte[] packet) {
        if (state == PLAY) {
            return false;
        }
        held.add(packet);
        return true;
    }

    /** The packets held back, once the login is through (empty before). */
    public List<byte[]> takeHeld() {
        if (state != PLAY || held.isEmpty()) {
            return List.of();
        }
        final List<byte[]> out = new ArrayList<>(held);
        held.clear();
        return out;
    }

    private byte[] loginSuccess() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        varInt(out, 0x02);
        if (serverProtocol >= BINARY_UUID) {
            writeLong(out, uuid.getMostSignificantBits());
            writeLong(out, uuid.getLeastSignificantBits());
        } else {
            string(out, uuid.toString());
        }
        string(out, name);
        if (serverProtocol >= PROFILE_PROPERTIES) {
            varInt(out, 0);
        }
        if (serverProtocol >= STRICT_ERRORS_FROM && serverProtocol <= STRICT_ERRORS_UNTIL) {
            out.write(0);
        }
        if (serverProtocol >= SESSION_ID) {
            final UUID session = UUID.randomUUID();
            writeLong(out, session.getMostSignificantBits());
            writeLong(out, session.getLeastSignificantBits());
        }
        return out.toByteArray();
    }

    private static void varInt(final ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void string(final ByteArrayOutputStream out, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeLong(final ByteArrayOutputStream out, final long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift) & 0xFF);
        }
    }
}
