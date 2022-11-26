/*******************************************************************************
 * Copyright 2018, 2021 Jorel Ali (Skepter) - MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package dev.jorel.commandapi;

import java.io.File;
import java.util.Map.Entry;

import dev.jorel.commandapi.arguments.*;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import org.bukkit.plugin.java.JavaPlugin;

import de.tr7zw.changeme.nbtapi.NBTContainer;
import de.tr7zw.changeme.nbtapi.utils.MinecraftVersion;

public class CommandAPIMain extends JavaPlugin {

	@Override
	public void onLoad() {
		// Configure the NBT API - we're not allowing tracking at all, according
		// to the CommandAPI's design principles. The CommandAPI isn't used very
		// much, so this tiny proportion of servers makes very little impact to
		// the NBT API's stats.
		MinecraftVersion.disableBStats();
		MinecraftVersion.disableUpdateCheck();

		// Config loading
		CommandAPI.logger = getLogger();
		saveDefaultConfig();
		CommandAPI.config = new InternalConfig(getConfig(), NBTContainer.class, NBTContainer::new, new File(getDataFolder(), "command_registration.json"));

		// Check dependencies for CommandAPI
		CommandAPIHandler.getInstance().checkDependencies();
		CommandAPIHandler.getInstance().getNMS().registerCustomArgumentType();

		// Convert all plugins to be converted
		for (Entry<JavaPlugin, String[]> pluginToConvert : CommandAPI.config.getPluginsToConvert()) {
			if (pluginToConvert.getValue().length == 0) {
				Converter.convert(pluginToConvert.getKey());
			} else {
				for (String command : pluginToConvert.getValue()) {
					new AdvancedConverter(pluginToConvert.getKey(), command).convert();
				}
			}
		}

		// Convert all arbitrary commands
		for (String commandName : CommandAPI.config.getCommandsToConvert()) {
			new AdvancedConverter(commandName).convertCommand();
		}

		new CommandAPICommand("veryearly").withArguments(new LiteralArgument("Hello")).executes((sender, args) -> {sender.sendMessage("Did I register?");}).register();
	}

	@Override
	public void onEnable() {
		CommandAPI.onEnable(this);

		new CommandAPICommand("test")
			.withArguments(
				new MultiLiteralArgument("a", "b", "c"),
				new StringArgument("string")
					.withInitialParseExceptionHandler(this::printInfo)
					.withArgumentParseExceptionHandler(this::printInfo),
				new IntegerArgument("int", 0, 10)
					.withInitialParseExceptionHandler(this::printInfo)
					.withArgumentParseExceptionHandler(this::printInfo),
				new PlayerArgument("player")
					.withInitialParseExceptionHandler(this::printInfo)
					.withArgumentParseExceptionHandler(this::printInfo)
			)
			.executes((sender, args) -> {
				sender.sendMessage(args[0].toString());
				sender.sendMessage(args[1].toString());
				sender.sendMessage(args[2].toString());
				sender.sendMessage(args[3].toString());
			})
			.register();
	}

	private <T> T printInfo(InitialParseExceptionContext context) throws WrapperCommandSyntaxException {
		CommandAPI.logNormal("Intercepted error with message: " + context.exception().getMessage());
		throw CommandAPI.failWithString("Haha! Custom Error has intercepted " + context.exception().getMessage());
	}

	private <T> T printInfo(ArgumentParseExceptionContext context) throws WrapperCommandSyntaxException {
		CommandAPI.logNormal("Intercepted error with message: " + context.exception().getMessage());
		throw CommandAPI.failWithString("Haha! Custom Error has intercepted " + context.exception().getMessage());
	}
}