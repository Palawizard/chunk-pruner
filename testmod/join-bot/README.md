# Join client

`JoinBot.java` joins a real player to an offline-mode dedicated server without a game client. The fixture world script uses it to stand a player in the world while RCON moves it, so the server generates and ticks chunks the way it does for a real player. It is a test tool: it has no dependency and is never part of a mod JAR.

## Origin

It is a copy of the client written for Player Data Rescue (`testmod/join-bot/JoinBot.java` on its `dev` branch, commit `1171da1`, ADR-PDR-006 there), checked there on the eight Tier A dedicated servers. This copy is maintained here on its own. It differs from the original in its position reports and in the two fixes listed below. It reports its position after each teleport and then every second (`ServerboundMovePlayerPacket.PosRot`), and on 26.3 it ends each of those client ticks with `ServerboundClientTickEndPacket`.

Two fixes made in Player Data Rescue on the same change (its pull request 4: commit `7f51aca`, and commits `0a310d7` and `caa27a9`) are carried over here:

- When the server sends `start_configuration` (1.20.2 and later), the client stops its position reports until the next teleport, since play packets are not allowed in the configuration phase. A vanilla server only sends it from `/debugconfig`, which the 26.3 server registers only with the system properties `MC_DEBUG_ENABLED` and `MC_DEBUG_DEV_COMMANDS` set to true (`Commands`, `SharedConstants`, read in the 26.3 server JAR), so the fixture worlds never reach it.
- A position report that fails to write no longer ends the client with a network error. The server may close the connection right after its disconnect packet; the client then stops reporting and reads on, so it still logs the disconnect and its reason.

Why the change: on 1.20.1 and 1.21.1, `ServerGamePacketListenerImpl.handleAcceptTeleportPacket` moves the player without moving the server's chunk tracking, which only follows a position packet (`handleMovePlayer`). With the original client, a player teleported with RCON `tp` stayed tracked at its join position: the destination only got the 3 by 3 chunks of the `POST_TELEPORT` ticket, and `fill` there answered "That position is not loaded". On 26.3 the server accepts one position packet per client tick and resets that limit on `ServerboundClientTickEndPacket`, so a second position packet without it disconnects the client with "Invalid move player packet received".

## Packet ids and layouts

One profile per protocol: 763 (1.20.1), 767 (1.21.1) and 777 (26.3).

- 1.20.1: ids are the registration order of the serverbound play packets in `ConnectionProtocol` (decompiled with Loom `genSources`). `move_player_pos_rot` is 21.
- 1.21.1 and 26.3: ids from the server's own report, `java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports` (`reports/packets.json`). `move_player_pos_rot` is 27 on 1.21.1 and 31 on 26.3, `client_tick_end` is 13 on 26.3 and does not exist on 1.21.1.
- `PosRot` layout, checked in each version's `ServerboundMovePlayerPacket`: x, y and z as doubles, y rotation and x rotation as floats, then one byte. That byte is the on-ground boolean on 1.20.1 and 1.21.1 and a flag byte on 26.3 (bit 0 on ground, bit 1 horizontal collision). The client always sends 0.

The serverbound play ids kept from the original client (teleport accept, client information, keep-alive, pong, chunk batch received, player loaded) match the same sources and reports.

## Checks

The scripts check the client on every run. A `fill` around the player must answer "Successfully filled" (the server only fills loaded chunks) after each of the four 15 s stops of the explored strip in `scripts/make-fixture-world.ps1`, after the village stop of its `keep` plan, and after the teleport into the pruned area in `scripts/check-pruned-world.ps1`. After the final RCON `kick`, in both scripts and on every plan, the client must log "disconnected by the server" with the kick reason. The teleports of the flown-over area are not checked this way.

Checked once on 2026-11-26 on the vanilla 26.3 dedicated server started with `-DMC_DEBUG_ENABLED=true -DMC_DEBUG_DEV_COMMANDS=true`: `tp Explorer 400 120 0`, a `fill` there ("Successfully filled 25 block(s)"), `debugconfig config Explorer`, 5 s in the configuration phase, `debugconfig unconfig <uuid>`, then `tp Explorer -400 120 0` and a `fill` there ("Successfully filled 25 block(s)"), and the kick reason logged by the client. The client before these fixes was disconnected as soon as it reported a position in the configuration phase ("Received unknown packet id 31", the id of `move_player_pos_rot`).

Checked on 2026-11-16 on the vanilla dedicated servers 1.20.1, 1.21.1 and 26.3 (`scripts/make-fixture-world.ps1` helpers): after `tp Bot 400 100 0`, `data get entity Bot Pos` answered `[400.5d, 100.0d, 0.5d]`, `fill 392 99 -8 407 99 7 minecraft:glass` answered "Successfully filled 256 block(s)", chunks generated around the destination up to the view distance, and the client stayed until it was kicked.

## Use

Run it with the JDK of the server's Minecraft version (17, 21 or 25):

```text
java JoinBot.java --port 25600 --protocol 763 --name Explorer --stay-seconds 3600 --view-distance 5
```

It prints `[join-bot]` lines and exits with 0 when it joined and stayed, 3 when the server disconnected it (the reason is printed), 4 on a network error or timeout, and 2 on a usage error. The server needs `online-mode=false`, and `white-list=false` on 26.3, whose server enables the whitelist by default.
