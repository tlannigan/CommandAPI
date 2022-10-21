package dev.jorel.commandapi;

import dev.jorel.commandapi.abstractions.AbstractCommandSender;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * This is a base class for {@link AbstractCommandAPICommand} and {@link AbstractCommandTree} command definitions
 *
 * @param <Impl> The class extending this class, used as the return type for chain calls
 * @param <CommandSender> The CommandSender class used by the class extending this class
 */
abstract class ExecutableCommand<Impl extends ExecutableCommand<Impl, CommandSender>, CommandSender> extends Executable<Impl, CommandSender> {

	/**
	 * The Command's meta-data for this executable command
	 */
	protected final CommandMetaData meta;

	ExecutableCommand(final String commandName) {
		this.meta = new CommandMetaData(commandName);
	}

	protected ExecutableCommand(final CommandMetaData meta) {
		this.meta = meta;
	}

	/**
	 * Returns the name of this command
	 * @return the name of this command
	 */
	public String getName() {
		return meta.commandName;
	}

	/**
	 * Applies a permission to the current command builder
	 * @param permission The permission node required to execute this command
	 * @return this command builder
	 */
	@SuppressWarnings("unchecked")
	public Impl withPermission(CommandPermission permission) {
		this.meta.permission = permission;
		return (Impl) this;
	}

	/**
	 * Applies a permission to the current command builder
	 * @param permission The permission node required to execute this command
	 * @return this command builder
	 */
	@SuppressWarnings("unchecked")
	public Impl withPermission(String permission) {
		this.meta.permission = CommandPermission.fromString(permission);
		return (Impl) this;
	}

	/**
	 * Applies a permission to the current command builder
	 * @param permission The permission node required to execute this command
	 * @return this command builder
	 */
	@SuppressWarnings("unchecked")
	public Impl withoutPermission(CommandPermission permission) {
		this.meta.permission = permission.negate();
		return (Impl) this;
	}

	/**
	 * Applies a permission to the current command builder
	 * @param permission The permission node required to execute this command
	 * @return this command builder
	 */
	@SuppressWarnings("unchecked")
	public Impl withoutPermission(String permission) {
		this.meta.permission = CommandPermission.fromString(permission).negate();
		return (Impl) this;
	}

	/**
	 * Adds a requirement that has to be satisfied to use this command. This method
	 * can be used multiple times and each use of this method will AND its
	 * requirement with the previously declared ones
	 *
	 * @param requirement the predicate that must be satisfied to use this command
	 * @return this command builder
	 */
	@SuppressWarnings("unchecked")
	public Impl withRequirement(Predicate<AbstractCommandSender<?>> requirement) {
		this.meta.requirements = this.meta.requirements.and(requirement);
		return (Impl) this;
	}

	/**
	 * Adds an array of aliases to the current command builder
	 * @param aliases An array of aliases which can be used to execute this command
	 * @return this command builder
	 */
	@SuppressWarnings("unchecked")
	public Impl withAliases(String... aliases) {
		this.meta.aliases = aliases;
		return (Impl) this;
	}



	/**
	 * Returns the permission associated with this command
	 * @return the permission associated with this command
	 */
	public CommandPermission getPermission() {
		return this.meta.permission;
	}

	/**
	 * Sets the permission required to run this command
	 * @param permission the permission required to run this command
	 */
	public void setPermission(CommandPermission permission) {
		this.meta.permission = permission;
	}

	/**
	 * Returns an array of aliases that can be used to run this command
	 * @return an array of aliases that can be used to run this command
	 */
	public String[] getAliases() {
		return meta.aliases;
	}

	/**
	 * Sets the aliases for this command
	 * @param aliases the aliases for this command
	 */
	public void setAliases(String[] aliases) {
		this.meta.aliases = aliases;
	}

	/**
	 * Returns the requirements that must be satisfied to run this command
	 * @return the requirements that must be satisfied to run this command
	 */
	public Predicate<AbstractCommandSender<?>> getRequirements() {
		return this.meta.requirements;
	}

	/**
	 * Sets the requirements that must be satisfied to run this command
	 * @param requirements the requirements that must be satisfied to run this command
	 */
	public void setRequirements(Predicate<AbstractCommandSender<?>> requirements) {
		this.meta.requirements = requirements;
	}
	
	/**
	 * Returns the short description for this command
	 * @return the short description for this command
	 */
	public String getShortDescription() {
		return this.meta.shortDescription.orElse(null);
	}

	/**
	 * Sets the short description for this command. This is the help which is
	 * shown in the main /help menu.
	 * @param description the short description for this command
	 * @return this command builder
	 */
	@SuppressWarnings("unchecked")
	public Impl withShortDescription(String description) {
		this.meta.shortDescription = Optional.ofNullable(description);
		return (Impl) this;
	}
	
	/**
	 * Returns the full description for this command
	 * @return the full description for this command
	 */
	public String getFullDescription() {
		return this.meta.fullDescription.orElse(null);
	}

	/**
	 * Sets the full description for this command. This is the help which is
	 * shown in the specific /help page for this command (e.g. /help mycommand).
	 * @param description the full description for this command
	 * @return this command builder
	 */
	@SuppressWarnings("unchecked")
	public Impl withFullDescription(String description) {
		this.meta.fullDescription = Optional.ofNullable(description);
		return (Impl) this;
	}

	/**
	 * Sets the short and full description for this command. This is a short-hand
	 * for the {@link ExecutableCommand#withShortDescription} and
	 * {@link ExecutableCommand#withFullDescription} methods.
	 * @param shortDescription the short description for this command
	 * @param fullDescription the full description for this command
	 * @return this command builder
	 */
	@SuppressWarnings("unchecked")
	public Impl withHelp(String shortDescription, String fullDescription) {
		this.meta.shortDescription = Optional.ofNullable(shortDescription);
		this.meta.fullDescription = Optional.ofNullable(fullDescription);
		return (Impl) this;
	}

	/**
	 * Overrides a command. Effectively the same as unregistering the command using
	 * CommandAPI.unregister() and then registering the command using .register()
	 */
	public void override() {
		CommandAPI.unregister(this.meta.commandName, true);
		register();
	}

	/**
	 * Registers this command
	 */
	public abstract void register();
	
}
