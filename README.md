# Pano Market

Store plugin for [Pano](https://panomc.com): list, manage and sell products on your website, with a cart,
checkout, credits, orders and invoices, plus delivery to your Minecraft servers.

## Store background

Market > Settings > General lets administrators upload or remove a store background image. PNG, JPEG, GIF and WebP
images up to 5 MB are supported. Uploads and removals are applied when the General settings are saved.
The default theme displays the image behind the store heading and upper content, fading into the page background.

## Payment gateways and shipping carriers

The market plugin itself ships no payment gateway and no shipping carrier. Gateways (Stripe, ...) and carriers are
separate Pano plugins built against the market API jar (`pano-plugin-market-api-<version>.jar`, attached to every
release). Install the ones you need next to this plugin.

## Minecraft-side component (required for server deliveries)

Deliveries to a game server (commands, ranks, items) are executed by a small component on the Minecraft server. It is
required whenever a product is delivered to a server, and it must speak the market's protocol.

It needs [pano-mc-plugin](https://github.com/PanoMC/pano-mc-plugin/releases) 1.2.0 or newer on the same server (it receives Pano's
updates through it the moment they happen); with an older one the component stays off and says so in the console.

There is one jar per platform, attached to every GitHub release and downloadable from the panel (Market > Servers).
Drop the one for your server into its `plugins` (or `mods`) folder:

- Spigot / Paper / Folia / Purpur: `pano-plugin-market-spigot-<version>.jar`
- BungeeCord / Waterfall: `pano-plugin-market-bungee-<version>.jar`
- Velocity: `pano-plugin-market-velocity-<version>.jar`
- Fabric: `pano-plugin-market-fabric-<version>.jar`

## Build

Needs JDK 21, Bun and the Gradle wrapper. `./gradlew build -Pfabric` builds the plugin jar (`build/libs`), the API jar
(`build/api`) and the Minecraft jars (`build/mc`; the Fabric one only with `-Pfabric`). The database test tier needs MariaDB (`PANO_IT_MARIADB`).

Releases are produced by CI (semantic-release) on `dev` (prerelease, dev store) and `main` (production store).
