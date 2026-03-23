package com.ariana.ge;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ArianaPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ArianaPlugin.class);
		RuneLite.main(args);
	}
}
