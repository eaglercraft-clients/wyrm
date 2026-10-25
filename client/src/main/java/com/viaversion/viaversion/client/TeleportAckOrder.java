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

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.packet.provider.PacketTypeMap;
import com.viaversion.viaversion.api.protocol.packet.provider.PacketTypesProvider;
import java.util.List;
import io.netty.buffer.ByteBuf;

/**
 * The order a teleport is confirmed in. A game that has just been teleported sends the acknowledgement first and
 * then its position (the 26.x game does). A vanilla server older than 1.21.2 compares that position with where the
 * player stood at the start of the tick, and the acknowledgement has not moved that point yet, so a position that
 * follows it in the same tick is rejected as "moved too quickly", the player is teleported back, the game confirms
 * again and the loop repeats until the two packets happen to fall into different ticks: the player rubber-bands.
 * Those servers expect the position first (it is ignored while the teleport is open) and the acknowledgement after
 * it, which is what the games of their own era send.
 *
 * <p>This holds an acknowledgement back until the next movement packet and writes that packet first. It works on the
 * packets as the server will see them (after Via has translated them, and cancelled the fake teleport it makes up on
 * login), so it needs the server's own packet ids.
 */
final class TeleportAckOrder {

    private final int accept;
    private final int[] moves;
    private ByteBuf held;

    private TeleportAckOrder(final int accept, final int... moves) {
        this.accept = accept;
        this.moves = moves;
    }

    /** Protocol numbers of 1.9 (the first version with teleport acknowledgements) and 1.21.1 (the last that needs this). */
    private static final int FIRST_SERVER = 107;
    private static final int LAST_SERVER = 767;

    /**
     * The reordering for the server behind {@code user}, or null when it does not need one (no teleport
     * acknowledgements before 1.9; from 1.21.2 the server takes either order). The packet ids are the server's own,
     * read from the last protocol of the translation path, whose serverbound packets are the ones the server gets.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static TeleportAckOrder forServer(final UserConnection user, final int serverProtocol) {
        if (serverProtocol < FIRST_SERVER || serverProtocol > LAST_SERVER) {
            return null;
        }
        final List<Protocol> pipes = user.getProtocolInfo().getPipeline().pipes();
        for (int i = pipes.size() - 1; i >= 0; --i) {
            final PacketTypesProvider provider = pipes.get(i).getPacketTypesProvider();
            final PacketTypeMap<PacketType> play = provider == null ? null : (PacketTypeMap<PacketType>) provider.mappedServerboundPacketTypes().get(State.PLAY);
            if (play == null) {
                continue;
            }
            final PacketType accept = play.typeByName("ACCEPT_TELEPORTATION");
            if (accept == null) {
                return null;
            }
            final int[] moves = new int[MOVES.length];
            int count = 0;
            for (final String name : MOVES) {
                final PacketType move = play.typeByName(name);
                if (move != null) {
                    moves[count++] = move.getId();
                }
            }
            return new TeleportAckOrder(accept.getId(), java.util.Arrays.copyOf(moves, count));
        }
        return null;
    }

    private static final String[] MOVES = {"MOVE_PLAYER_POS", "MOVE_PLAYER_POS_ROT", "MOVE_PLAYER_ROT", "MOVE_PLAYER_STATUS_ONLY"};

    /**
     * Takes a packet bound for the server (play state) and returns what to write now, in order: nothing (it was an
     * acknowledgement, now held), the packet itself, or the packet followed by the acknowledgement it was waiting
     * for (or preceded by it, for a packet that is not a movement).
     */
    ByteBuf[] next(final ByteBuf packet) {
        final int id = id(packet);
        if (held != null) {
            final ByteBuf ack = held;
            held = null;
            return isMove(id) ? new ByteBuf[] {packet, ack} : new ByteBuf[] {ack, packet};
        }
        if (id == accept) {
            held = packet;
            return new ByteBuf[0];
        }
        return new ByteBuf[] {packet};
    }

    /** The acknowledgement still held, to be written as it is (the connection is ending), or null. */
    ByteBuf takeHeld() {
        final ByteBuf was = held;
        held = null;
        return was;
    }

    private boolean isMove(final int id) {
        for (final int move : moves) {
            if (move == id) {
                return true;
            }
        }
        return false;
    }

    private static int id(final ByteBuf buf) {
        int result = 0;
        int index = buf.readerIndex();
        for (int shift = 0; shift < 35 && index < buf.writerIndex(); shift += 7) {
            final byte b = buf.getByte(index++);
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        return -1;
    }
}
