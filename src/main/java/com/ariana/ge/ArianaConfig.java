package com.ariana.ge;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("ariana")
public interface ArianaConfig extends Config
{
    @ConfigSection(
        name = "General",
        description = "General settings",
        position = 0
    )
    String generalSection = "general";

    @ConfigSection(
        name = "Connection",
        description = "Relay connection settings",
        position = 1
    )
    String connectionSection = "connection";

    @ConfigItem(
        keyName = "autoLaunchOnLogin",
        name = "Auto-launch on login",
        description = "Automatically open ariana.trade when you log into the game",
        section = generalSection,
        position = 1
    )
    default boolean autoLaunchOnLogin() { return false; }

    @ConfigItem(
        keyName = "autoLaunchOnGE",
        name = "Auto-launch on GE open",
        description = "Automatically open ariana.trade when you open the Grand Exchange",
        section = generalSection,
        position = 2
    )
    default boolean autoLaunchOnGE() { return false; }

    @ConfigItem(
        keyName = "enableContextMenu",
        name = "Right-click 'Open in ariana'",
        description = "Add 'Open in ariana' to right-click menus for items in inventory, bank, and equipment",
        section = generalSection,
        position = 3
    )
    default boolean enableContextMenu() { return true; }

    @ConfigItem(
        keyName = "contextMenuBankOnly",
        name = "Right-click only near bank",
        description = "Only show 'Open in ariana' when you are near a bank",
        section = generalSection,
        position = 4
    )
    default boolean contextMenuBankOnly() { return false; }

    @ConfigItem(
        keyName = "arianaUrl",
        name = "ariana.trade URL",
        description = "URL to open ariana.trade in your browser",
        section = generalSection,
        position = 99,
        hidden = true
    )
    default String arianaUrl() { return "https://ariana.trade"; }

    @ConfigItem(
        keyName = "relayToken",
        name = "Relay Token",
        description = "Paste your relay token from ariana.trade to connect the plugin",
        section = connectionSection,
        position = 10,
        secret = true
    )
    default String relayToken() { return ""; }
}
