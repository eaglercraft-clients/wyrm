# Wyrm

**A ViaVersion fork for Eaglercraft: lets newer clients, Eaglercraft 1.12 to 26.2 included, join older servers.**

Wyrm is the base layer of the Wyvern project. It is [ViaVersion](https://github.com/ViaVersion/ViaVersion) plus a bridge
for Eaglercraft's plugin channels, which are not valid channel names on 1.13 and newer and were dropped before.
It is a fork, licensed GPL-3.0-or-later; see [NOTICE.md](NOTICE.md) and [THIRD_PARTY.md](THIRD_PARTY.md).

## What changed from upstream

- Eaglercraft plugin channel bridge: a pre-1.13 server's `EAG|Skins-1.8` reaches a 1.13+ Eaglercraft client as `eag:skins-1.8`,
  and the client's replies go back under the server's spelling. Options `eagler-channel-bridge` and `eagler-extra-channels`
  in `config.yml`. Code in `common/.../protocols/v1_12_2to1_13/eagler`.
- Own jar name (`Wyrm-<version>.jar`), links, security contact, issue templates and release workflow; upstream publishing removed.
- Java packages and the plugin id are unchanged on purpose, so addons such as Wyvern (ViaBackwards fork) and ViaRewind keep working.

## Which jars you need

| Players joining with | Install |
| :--- | :--- |
| Newer versions than the server | Wyrm |
| Older versions than the server, down to 1.9 | Wyrm + [Wyvern](https://github.com/eaglercraft-clients/wyvern) |
| 1.7.x to 1.8.x | Wyrm + Wyvern + ViaRewind (copy in [`third_party/viarewind`](third_party/viarewind)) |

## In the client (`client` module)

Eaglercraft clients that embed Via instead of relying on a proxy can use the `viaversion-client` module. It holds the
game-agnostic parts and has no dependency on any game classes:

- `ClientVia`: starts Via once, as a proxy-like platform named after the client; lists releases; `supports(native, server)`
  asks Via for a protocol path, so servers newer than the game count only when Wyvern's protocols are registered.
- `ClientPipeline.install(channel, serverProtocol, splitter, prepender)`: adds Via to a connection's Netty pipeline
  (hand-written handlers, because dropping packets by throwing is slow under TeaVM).
- `EaglerLogin`: an Eagler proxy logs the player in to its backend itself, so the game's handshake and Login Start are
  swallowed and a Login Success in the server's protocol is handed back for Via to translate.

```java
ClientVia.setClient("MyClient", "26.2");
ClientVia.start(new WyvernClient());      // off the render thread; WyvernClient (from Wyvern) is optional, for newer servers
// 1.8 game: ClientVia.start(new WyvernClient(), new RewindClient());  // RewindClient from Wyvern's rewind module
if (ClientVia.supports(nativeProtocol, serverProtocol)) {
    ClientPipeline.install(channel, serverProtocol, "splitter", "prepender");   // after the framing handlers
    EaglerLogin login = new EaglerLogin(serverProtocol, name, uuid);            // swallow/hold/takeLoginSuccess, see its javadoc
}
```

Tests push a 26.2 handshake and login through the pipeline to 1.8, 1.12.2 and 1.21 servers and check the bytes
(`ClientViaTest`); Wyvern's tests cover older games joining newer servers. A 1.8 game going up also needs Wyvern's `rewind` module (`new RewindClient()`), tested the same way in Wyvern. They run on the JVM with Netty's embedded channel: **nothing here has been compiled with TeaVM or run in
a browser**.

## Honest status

CI compiles and runs the unit tests. The bridge has not been tested with a real Eaglercraft client and server yet.

## Links

[Website](website/index.html) | [Building](#building) | [Third party](THIRD_PARTY.md) | [Security](SECURITY.md) | [Licence](LICENSE)

## Building

JDK 17+: `./gradlew build`. The jar is `build/libs/Wyrm-<version>.jar`. Pushing a `v*` tag creates a GitHub release.
