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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs a game speaking the newest protocol Wyrm knows through Via's handlers against older servers: a real
 * handshake and login go out, a Login Success comes back, all as bytes. No socket and no game: the channel is Netty's
 * embedded one and the "game" writes the packets by hand.
 */
class ClientViaTest {

    private static final int NATIVE = ProtocolVersion.v26_2.getVersion();
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeAll
    static void startVia() {
        ClientVia.setClient("Test", "1");
        ClientVia.start();
    }

    @Test
    void listsOlderAndNewerReleases() {
        final var releases = ClientVia.releases(NATIVE);
        assertTrue(releases.stream().anyMatch(r -> r.protocol() == ProtocolVersion.v1_8.getVersion()));
        assertTrue(releases.stream().anyMatch(r -> r.protocol() == ProtocolVersion.v1_12_2.getVersion()));
        assertTrue(releases.stream().noneMatch(r -> r.protocol() == NATIVE));
        assertTrue(releases.get(0).protocol() > releases.get(releases.size() - 1).protocol(), "newest first");
    }

    @Test
    void supportsOlderServersButNotNewerOnesWithoutWyvern() {
        assertTrue(ClientVia.supports(NATIVE, NATIVE));
        assertTrue(ClientVia.supports(NATIVE, ProtocolVersion.v1_8.getVersion()));
        assertTrue(ClientVia.supports(NATIVE, ProtocolVersion.v1_12_2.getVersion()));
        assertTrue(ClientVia.supports(NATIVE, ProtocolVersion.v1_21.getVersion()));
        assertFalse(ClientVia.supports(NATIVE, ProtocolVersion.v26_3.getVersion()), "needs Wyvern");
        assertFalse(ClientVia.supports(NATIVE, 12345));
    }

    @Test
    void translatesHandshakeAndLoginForA1_12_2Server() {
        assertLogin(ProtocolVersion.v1_12_2.getVersion());
    }

    @Test
    void translatesHandshakeAndLoginForA1_8Server() {
        assertLogin(ProtocolVersion.v1_8.getVersion());
    }

    @Test
    void translatesHandshakeAndLoginForA1_21Server() {
        assertLogin(ProtocolVersion.v1_21.getVersion());
    }

    private static void assertLogin(final int server) {
        final EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
        });
        // Stand-ins for the game's framing handlers, which Via sits right after.
        channel.pipeline().addFirst("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        ClientPipeline.install(channel, server, "splitter", "prepender");
        final EaglerLogin login = new EaglerLogin(server, "Wyrm", PLAYER);

        // Handshake, as the game writes it: the game's own protocol, intent "login".
        final ByteBuf handshake = Unpooled.buffer();
        Types.VAR_INT.writePrimitive(handshake, 0x00);
        Types.VAR_INT.writePrimitive(handshake, NATIVE);
        Types.STRING.write(handshake, "localhost");
        Types.UNSIGNED_SHORT.write(handshake, 25565);
        Types.VAR_INT.writePrimitive(handshake, 2);
        channel.writeOutbound(handshake);
        final ByteBuf sent = (ByteBuf) channel.readOutbound();
        assertNotNull(sent, "the handshake must reach the server");
        assertEquals(0x00, Types.VAR_INT.readPrimitive(sent));
        assertEquals(server, Types.VAR_INT.readPrimitive(sent), "the server sees its own protocol");
        sent.readerIndex(0);
        assertTrue(login.swallow(bytes(sent)));

        // Login Start: name and UUID from 1.20.2 on.
        final ByteBuf start = Unpooled.buffer();
        Types.VAR_INT.writePrimitive(start, 0x00);
        Types.STRING.write(start, "Wyrm");
        Types.UUID.write(start, PLAYER);
        channel.writeOutbound(start);
        final ByteBuf loginStart = (ByteBuf) channel.readOutbound();
        assertNotNull(loginStart, "Login Start must reach the server");
        assertEquals(0x00, Types.VAR_INT.readPrimitive(loginStart));
        assertEquals("Wyrm", Types.STRING.read(loginStart));
        loginStart.readerIndex(0);
        assertTrue(login.swallow(bytes(loginStart)));

        // The proxy logged the player in; the stand-in Login Success goes back through Via to the game.
        final byte[] success = login.takeLoginSuccess();
        assertNotNull(success);
        assertNull(login.takeLoginSuccess(), "only once");
        channel.writeInbound(Unpooled.wrappedBuffer(success));
        final ByteBuf received = (ByteBuf) channel.readInbound();
        assertNotNull(received, "the game must get a Login Success");
        assertEquals(0x02, Types.VAR_INT.readPrimitive(received), "Login Success / Login Finished");
        assertEquals(PLAYER, Types.UUID.read(received));
        assertEquals("Wyrm", Types.STRING.read(received));
        assertEquals(0, Types.VAR_INT.readPrimitive(received), "no profile properties");

        channel.finish();
    }

    private static byte[] bytes(final ByteBuf buf) {
        final byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }
}
