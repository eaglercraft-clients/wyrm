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
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.exception.CancelCodecException;
import com.viaversion.viaversion.exception.CancelDecoderException;
import com.viaversion.viaversion.exception.CancelEncoderException;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import java.util.logging.Logger;

/**
 * Via as a stage of one connection's Netty pipeline: packets from the server are translated to the game's protocol
 * after framing (and decompression), and the game's packets to the server's protocol before it. Via sees whole,
 * uncompressed packets both ways, as it does on a proxy.
 *
 * <p>The stage sits right after the framing handlers (the splitter and the length prepender); the game adds
 * compression next to those too, so decompression lands before the decoder and compression after the encoder.
 *
 * <p>The handlers are written by hand rather than with Netty's {@code MessageToMessageDecoder}/{@code Encoder}, which
 * catch and rethrow every packet Via drops on purpose. In a browser (TeaVM) a throw is costly, and an old server drops
 * a dozen packets a second.
 */
public final class ClientPipeline {

    public static final String DECODER = "via-decoder";
    public static final String ENCODER = "via-encoder";
    public static final String RELAY = "via-relay";

    private static final Logger LOGGER = Logger.getLogger("Via");

    private ClientPipeline() {
    }

    /**
     * Adds Via to {@code channel} for a server speaking {@code serverProtocol}. {@link ClientVia#start()} must have
     * run, and {@link ClientVia#supports} must hold for the game's protocol and {@code serverProtocol}.
     *
     * @param splitter  name of the handler that frames incoming packets; Via goes right after it
     * @param prepender name of the handler that frames outgoing packets; Via goes right after it
     * @return Via's connection state, e.g. to read the player's state or close it
     */
    public static UserConnection install(final Channel channel, final int serverProtocol, final String splitter, final String prepender) {
        final ProtocolVersion server = ClientVia.release(serverProtocol);
        if (server == null) {
            throw new IllegalArgumentException("Unknown server protocol " + serverProtocol);
        }
        final UserConnection user = new UserConnectionImpl(channel, true);
        new ProtocolPipelineImpl(user);
        user.put(new ServerVersionProvider.Target(server));
        final Relay relay = new Relay();
        channel.pipeline().addAfter(splitter, DECODER, new Decoder(user));
        channel.pipeline().addAfter(DECODER, RELAY, relay);
        channel.pipeline().addAfter(prepender, ENCODER, new Encoder(user, relay, server.getVersion()));
        return user;
    }

    /** The packet id at the front of {@code buf}, without reading it; -1 if there is none. */
    private static int peekId(final ByteBuf buf) {
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

    private static String describe(final UserConnection user) {
        try {
            return user.getProtocolInfo().getState(Direction.CLIENTBOUND) + "/" + user.getProtocolInfo().getState(Direction.SERVERBOUND)
                + " path=" + user.getProtocolInfo().getPipeline().pipes().size();
        } catch (final RuntimeException e) {
            return "?";
        }
    }

    /** Whether the failure is Via dropping a packet on purpose. */
    static boolean cancelled(final Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancelCodecException) {
                return true;
            }
        }
        return false;
    }

    /** Translates each packet from the server. */
    private static final class Decoder extends ChannelInboundHandlerAdapter {

        private final UserConnection user;
        private int traced;
        private boolean decodeCalled;
        private boolean messageProduced;

        Decoder(final UserConnection user) {
            this.user = user;
        }

        /** The packet translated for the game, or null if Via drops it. */
        private ByteBuf decode(final ChannelHandlerContext ctx, final ByteBuf in) throws Exception {
            if (!user.checkIncomingPacket(in.readableBytes())) {
                return null;
            }
            if (!user.shouldTransformPacket()) {
                return in.retain();
            }
            final ByteBuf transformed = ctx.alloc().buffer(in.readableBytes()).writeBytes(in);
            final int traceId = traced < ClientVia.traceLimit() ? peekId(transformed) : -2;
            final String before = traceId != -2 ? describe(user) : null;
            try {
                user.transformIncoming(transformed, CancelDecoderException::generate);
                if (traceId != -2) {
                    ++traced;
                    LOGGER.info("[Via] server->game id " + traceId + " -> " + peekId(transformed) + " (" + before + ")");
                }
            } catch (final Throwable failure) {
                transformed.release();
                if (cancelled(failure)) {
                    if (traceId != -2) {
                        ++traced;
                        LOGGER.info("[Via] server->game id " + traceId + " dropped (" + before + ")");
                    }
                    return null;
                }
                LOGGER.warning("[Via] server->game id " + traceId + " failed (" + describe(user) + "): " + failure);
                throw failure;
            }
            return transformed;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object message) {
            decodeCalled = true;
            if (!(message instanceof ByteBuf)) {
                messageProduced = true;
                ctx.fireChannelRead(message);
                return;
            }
            final ByteBuf in = (ByteBuf) message;
            final ByteBuf out;
            try {
                out = decode(ctx, in);
            } catch (final Exception e) {
                if (cancelled(e)) {
                    return;
                }
                throw e instanceof DecoderException ? (DecoderException) e : new DecoderException(e);
            } finally {
                in.release();
            }
            if (out != null) {
                messageProduced = true;
                ctx.fireChannelRead(out);
            }
        }

        @Override
        public void channelReadComplete(final ChannelHandlerContext ctx) {
            if (decodeCalled && !messageProduced && !ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
            decodeCalled = false;
            messageProduced = false;
            ctx.fireChannelReadComplete();
        }
    }

    /** Translates each packet the game sends. */
    private static final class Encoder extends ChannelOutboundHandlerAdapter {

        private final UserConnection user;
        private final Relay relay;
        private final int serverProtocol;
        private TeleportAckOrder ackOrder;
        private boolean ackOrderResolved;
        private int traced;

        Encoder(final UserConnection user, final Relay relay, final int serverProtocol) {
            this.user = user;
            this.relay = relay;
            this.serverProtocol = serverProtocol;
        }

        private boolean playing() {
            try {
                return user.getProtocolInfo().getState(Direction.SERVERBOUND) == State.PLAY;
            } catch (final RuntimeException e) {
                return false;
            }
        }

        private ByteBuf encode(final ChannelHandlerContext ctx, final ByteBuf in) throws Exception {
            if (!user.checkOutgoingPacket()) {
                return null;
            }
            if (!user.shouldTransformPacket()) {
                return in.retain();
            }
            final ByteBuf transformed = ctx.alloc().buffer(in.readableBytes()).writeBytes(in);
            final int traceId = traced < ClientVia.traceLimit() ? peekId(transformed) : -2;
            final String before = traceId != -2 ? describe(user) : null;
            relay.encoding = true;
            try {
                user.transformOutgoing(transformed, CancelEncoderException::generate);
                if (traceId != -2) {
                    ++traced;
                    LOGGER.info("[Via] game->server id " + traceId + " -> " + peekId(transformed) + " (" + before + ")");
                }
            } catch (final Throwable failure) {
                relay.encoding = false;
                transformed.release();
                if (cancelled(failure)) {
                    return null;
                }
                throw failure;
            }
            relay.encoding = false;
            return transformed;
        }

        @Override
        public void write(final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise) {
            if (!(message instanceof ByteBuf)) {
                ctx.write(message, promise);
                return;
            }
            final ByteBuf in = (ByteBuf) message;
            final ByteBuf out;
            try {
                out = encode(ctx, in);
            } catch (final Exception e) {
                if (!cancelled(e)) {
                    throw e instanceof EncoderException ? (EncoderException) e : new EncoderException(e);
                }
                promise.trySuccess();
                return;
            } finally {
                in.release();
            }
            if (out == null) {
                // A packet the server's version has no equivalent for: done, as far as the game knows.
                promise.trySuccess();
                return;
            }
            if (playing() && !ackOrderResolved) {
                // the translation path is known once the game is in play
                ackOrderResolved = true;
                ackOrder = TeleportAckOrder.forServer(user, serverProtocol);
            }
            if (ackOrder != null && playing()) {
                // a teleport's acknowledgement goes after the position that follows it (see TeleportAckOrder)
                final ByteBuf[] order = ackOrder.next(out);
                if (order.length == 0) {
                    promise.trySuccess();
                    return;
                }
                for (int i = 0; i < order.length - 1; ++i) {
                    ctx.write(order[i], ctx.newPromise());
                }
                ctx.write(order[order.length - 1], promise);
                return;
            }
            ctx.write(out, promise);
        }

        @Override
        public void handlerRemoved(final ChannelHandlerContext ctx) {
            if (ackOrder != null) {
                final ByteBuf held = ackOrder.takeHeld();
                if (held != null) {
                    held.release();
                }
            }
        }
    }

    /**
     * Holds back packets Via hands the game while it translates one of the game's own (for example when the game
     * finishes the configuration phase an old server lacks, Via releases the server's queued play packets at once).
     * The game only switches protocol after that write returns, so they continue as the next task, in order.
     */
    private static final class Relay extends ChannelInboundHandlerAdapter {

        /** Set while the encoder translates; the connection's thread only. */
        boolean encoding;

        private final ArrayDeque<Object> held = new ArrayDeque<>();

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object message) {
            if (!encoding && held.isEmpty()) {
                ctx.fireChannelRead(message);
                return;
            }
            if (held.isEmpty()) {
                ctx.executor().execute(() -> release(ctx));
            }
            held.add(message);
        }

        private void release(final ChannelHandlerContext ctx) {
            while (!held.isEmpty()) {
                ctx.fireChannelRead(held.remove());
            }
        }

        @Override
        public void handlerRemoved(final ChannelHandlerContext ctx) {
            while (!held.isEmpty()) {
                ReferenceCountUtil.release(held.remove());
            }
        }
    }
}
