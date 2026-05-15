**LoginPhaseProxy** is a somewhat "*simple*" Velocity Plugin that allows you to proxy the LoginPluginMessagePacket from backend server to the player and LoginPluginResponsePacket from the player to the backend server. This is useful for modded backend servers that rely on Login Plugin Message communication to work, such as [AutoModpack](https://github.com/Skidamek/AutoModpack).

> Disclaimer: This plugin is in early development and may contain bugs and performance issues. Use it at your own risk.

## 🔁 When to use

| Author(s)                               | Mod                                                                            |
|-----------------------------------------|--------------------------------------------------------------------------------|
| [Skidam](https://github.com/Skidamek)   | [AutoModpack](https://github.com/Skidamek/AutoModpack) (Fabric/Forge/Neoforge) |

> You're welcome to suggest new use cases via [Issue](https://github.com/caiostoduto/LoginPhaseProxy/issues/new/choose) or [Pull Request](https://github.com/caiostoduto/LoginPhaseProxy/pulls)!

## ❓ What does it solve (detailed)

![Sequence Diagram](/docs/sequence_diagram_original.png)
<p align="center">
  <sup>Normal Setup ⦁ Made in <a href="https://www.figma.com/">Figma</a></sup>
</p>

On a normal Velocity setup, when a player connects to the proxy, they go through the (P ↔ V) Login Phase, exchanging packets with the Velocity until the proxy sends a Server Login Success Packet, completing the (P ↔ V) Login Phase and triggering the backend connection process. The backend server then goes through its own (V ↔ B) Login Phase, where it may send Login Plugin Message Packets to the Velocity, and they can't be forwarded to the player, because the (P ↔ V) Login Phase is already complete, so Velocity reponds them with an empty data Login Plugin Response Packet, as it doesn't know how to handle them. This is a problem for modded backend servers that rely on Login Plugin Message communication to work, such as [AutoModpack](https://github.com/Skidamek/AutoModpack), as they won't be able to send the necessary data to the player during the Login Phase, and the player won't be able to join the backend server. This plugin tries to solve this issue by proxying the Login Plugin Message communication between the player and the backend server, allowing modded backend servers to work with Velocity without any issues.

## ✨ How it works (detailed)

~~Basically black magic 🪄🔮~~. It uses [Java Reflection](https://www.oracle.com/technical-resources/articles/java/javareflection.html) to access the internal Velocity classes [ProxyServer](https://github.com/PaperMC/Velocity/blob/ad8de4361c9d6e93b818d3381e85b14e0c90ad05/proxy/src/main/java/com/velocitypowered/proxy/VelocityServer.java) -> [ConnectionManager](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/network/ConnectionManager.java)<sup>[[1]](/src/main/java/com/caiostoduto/loginPhaseProxy/initializer/VelocityChannelInitializer.java)</sup> to override the serverChannelInitializer<sup>[[2]](/src/main/java/com/caiostoduto/loginPhaseProxy/initializer/FrontendChannelInitializer.java)</sup> and backendChannelInitializer<sup>[[3]](/src/main/java/com/caiostoduto/loginPhaseProxy/initializer/BackendChannelInitializer.java)</sup>, so it can intercept the Velocity communication with the player (P <-> V)<sup>[[4]](/src/main/java/com/caiostoduto/loginPhaseProxy/intercept/FrontendInterceptor.java)</sup> and backend server (V <-> S)<sup>[[5]](/src/main/java/com/caiostoduto/loginPhaseProxy/intercept/BackendInterceptor.java)</sup>, respectively.

![Sequence Diagram](/docs/sequence_diagram_plugin.png)
<p align="center">
  <sup>Plugin Implementation ⦁ Made in <a href="https://www.figma.com/">Figma</a></sup>
</p>

With that, our plugin watches the Login Phase player connection until it sees a SetCompressionPacket<sup>(5)</sup> (optionally) and ServerLoginSuccessPacket<sup>(6)</sup>, adding them to a buffer instead of sending them to the player and synthetically sends a loginAcknowledgedPacket<sup>(7)</sup> to the Velocity pipeline, tricking it into thinking the Login Phase is complete and starting the backend connection process. To make sure the Velocity doesn't mess the packet interception and sending process, our plugin removes stealthly (so that it doesn't trigger it's handlerRemoved lifecycle<sup>[[6]](/src/main/java/com/caiostoduto/loginPhaseProxy/utils/StealthPipeline.java)</sup>) the handlers from the Velocity serverChannel pipeline<sup>[[4]](/src/main/java/com/caiostoduto/loginPhaseProxy/intercept/FrontendInterceptor.java)</sup> that were added after the Login Phase to Config Phase transition ([MinecraftCompressorAndLengthEncoder](https://github.com/PaperMC/Velocity/blob/ad8de4361c9d6e93b818d3381e85b14e0c90ad05/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/MinecraftCompressorAndLengthEncoder.java#L33) and [MinecraftCompressDecoder](https://github.com/PaperMC/Velocity/blob/ad8de4361c9d6e93b818d3381e85b14e0c90ad05/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/MinecraftCompressDecoder.java#L34), others are removed naturally afterwards). Also, our plugin adds the [MinecraftVarintLengthEncoder](https://github.com/PaperMC/Velocity/blob/ad8de4361c9d6e93b818d3381e85b14e0c90ad05/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/MinecraftVarintLengthEncoder.java#L33)<sup>[[4]](/src/main/java/com/caiostoduto/loginPhaseProxy/intercept/FrontendInterceptor.java)</sup> back (was removed at the transition) and sets the [MinecraftDecoder](https://github.com/PaperMC/Velocity/blob/ad8de4361c9d6e93b818d3381e85b14e0c90ad05/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/MinecraftDecoder.java#L34) state to StateRegistry.LOGIN<sup>[[4]](/src/main/java/com/caiostoduto/loginPhaseProxy/intercept/FrontendInterceptor.java)</sup> so the packets are properly encoded and decoded.

If the backend server sends a LoginPluginMessagePacket<sup>(10)</sup> during its Login Phase, our plugin will intercept it and send it to the player<sup>(11)</sup>, and if the player sends a LoginPluginResponsePacket<sup>(12)</sup>, our plugin will intercept it and send it to the backend server<sup>(13)</sup>. This way, we can effectively proxy the LoginPluginMessagePacket and LoginPluginResponsePacket between the player and the backend server, allowing modded backend servers to work with Velocity without any issues. Then, when the backend server ends the Login Phase sending the ServerLoginSuccessPacket<sup>(15)</sup>, our plugin will flush the buffered packets to the player, completing the Login Phase and allowing the player to join the backend server as normal. Afterwards, if the user sends a LoginAcknowledgedPacket (clientProtocolVersion >= ProtocolVersion.MINECRAFT_1_20_2)<sup>(16)</sup> after the Login Phase is complete, our plugin will simply ignore it, as it is not expected to be sent by the player at that point. Finally, the plugin will restore the [MinecraftDecoder](https://github.com/PaperMC/Velocity/blob/ad8de4361c9d6e93b818d3381e85b14e0c90ad05/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/MinecraftDecoder.java#L34) state to its previous state (Config Phase).

So, yeah, *basically black magic* 🪄🔮. Yayyyy!

## 🙏 Acknowledgements

- [Skidam](https://github.com/Skidamek), who inspired me to create this plugin!
- [lucas-gcp](https://github.com/lucas-gcp), who supported me and helped with testing!
