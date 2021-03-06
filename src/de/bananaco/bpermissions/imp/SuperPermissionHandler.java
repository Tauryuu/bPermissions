package de.bananaco.bpermissions.imp;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;

import de.bananaco.bpermissions.api.World;
import de.bananaco.bpermissions.api.WorldManager;
/**
 * Handles all the superperms registering/unregistering
 * for PermissionAttachments (it's basically just somewhere
 * to stick all the nasty SuperPerms stuff that wouldn't exist
 * if SuperPerms was a more flexible system.
 * 
 * What's wrong with a PermissionProvider interface where we can
 * register a single PermissionProvider?!
 */
public class SuperPermissionHandler implements Listener {

	private WorldManager wm = WorldManager.getInstance();
	private Map<Player, PermissionAttachment> attachments = new HashMap<Player, PermissionAttachment>();
	private Permissions plugin;
	
	private WorldChecker checker;

	private static Field permissions;

	static {
		try {
			permissions = PermissionAttachment.class.getDeclaredField("permissions");
			permissions.setAccessible(true);
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (NoSuchFieldException e) {
			e.printStackTrace();
		}
	}

	/**
	 * This is put in place until such a time as Bukkit pull 466 is implemented
	 * https://github.com/Bukkit/Bukkit/pull/466
	 */
	@SuppressWarnings("unchecked")
	public static void setPermissions(PermissionAttachment att, Map<String, Boolean> perm) throws IllegalArgumentException, IllegalAccessException {
		// Grab a reference to the original object
		Map<String, Boolean> orig = (Map<String, Boolean>) permissions.get(att);
		// Clear the map (faster than removing the attachment and recalculating)
		orig.clear();
		
		// DEBUG
		//for(String key : perm.keySet()) System.out.println(key);
		
		// Then whack our map into there
		orig.putAll(perm);
		// That's all folks!
		att.getPermissible().recalculatePermissions();
	}

	// Main constructor
	
	protected SuperPermissionHandler(Permissions plugin) {
		this.plugin = plugin;
		// This next bit is simply to make bPermissions.* work with superperms, since I now have my bulk adding, I will concede to this
		Map<String, Boolean> children = new HashMap<String, Boolean>();
		children.put("bPermissions.admin", true);
		Permission permission = new Permission("bPermissions.*", PermissionDefault.OP, children);
		plugin.getServer().getPluginManager().addPermission(permission);
		
		checker = new WorldChecker(plugin.getServer(), this);
		
		plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, checker, 10, 10);
	}

	/**
	 * A guaranteed way to setup all players in the server in one fell swoop
	 */
	public void setupAllPlayers() {
		for(Player player : plugin.getServer().getOnlinePlayers())
			setupPlayer(player, wm.getWorld(player.getWorld().getName()));
	}
	
	/**
	 * Set up the Player via the specified World object
	 * (note this is a bPermissions world, not a Bukkit world)
	 * @param player
	 * @param world
	 */
	public void setupPlayer(Player player, World world) {
		if(!plugin.isEnabled())
			return;
		
		long time = System.currentTimeMillis();
		// Some null checks, I guess?
		if(world == null) {
			System.err.println("Unable to setup! null world!");
			return;
		}
		if(player == null) {
			System.err.println("Unable to setup! null player!");
			return;
		}
		if(world.getUser(player.getName()) == null) {
			System.err.println("Unable to setup! null user!");
			return;
		}
		PermissionAttachment att;
		// Does the player have an attachment that we've assigned already?
		// Then we add a new one or grab the existing one
		if(attachments.containsKey(player)) {
			att = attachments.get(player);
		}
		else {
			att = player.addAttachment(plugin);
			attachments.put(player, att);
		}
		// Grab the pre-calculated effectivePermissions from the User object
		Map<String, Boolean> perms;
		// Implement global files
		if(wm.getUseGlobalFiles() && wm.getDefaultWorld() != null) {
		// If the global file is enabled, read from it first then override where applicable
		perms = wm.getWorld("*").getUser(player.getName()).getMappedPermissions();
		perms.putAll(world.getUser(player.getName()).getMappedPermissions());
		} else {
		perms = world.getUser(player.getName()).getMappedPermissions();
		}
		// Then whack it onto the player
		// TODO wait for the bukkit team to get their finger out, we'll use our reflection here!
		try {
			// The world
			perms.put("world."+player.getWorld().getName(), true);
			Debugger.log("world."+world.getName()+" set for "+player.getName());
			setPermissions(att, perms);
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
		long finish = System.currentTimeMillis()-time;
		Debugger.log("Setup superperms for "+player.getName()+". took "+finish+"ms.");
	}

	@EventHandler
	public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
		// In theory this should be all we need to detect world, it isn't cancellable so... should be fine?
		setupPlayer(event.getPlayer(), wm.getWorld(event.getPlayer().getWorld().getName()));
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerLogin(PlayerLoginEvent event) {
		// Likewise, in theory this should be all we need to detect when a player joins
		setupPlayer(event.getPlayer(), wm.getWorld(event.getPlayer().getWorld().getName()));		
	}
	
	@EventHandler
	public void onPlayerChat(PlayerChatEvent event) {
		Player player = event.getPlayer();
		// If the player is an op and has given themselves an * node, mess with their chat
		if(wm.getWorld(player.getWorld().getName()).getUser(player.getName()).hasPermission("*")) {
				event.setMessage(rawritise(event.getMessage()));
		}
	}

	/**
	 * Changes a String of any length
	 * into a String of "raaaaaaaaaawr"
	 * with "a" being the length of the 
	 * original String
	 * @param message
	 * @return String<raaaaaaawr>
	 */
	private String rawritise(String message) {
		int length = message.length()-5;
		StringBuilder sb = new StringBuilder();
		for(int i=0; i<length; i++)
			sb.append("a");
		return "The '*' node won't work with superperms! Ra"+sb.toString()+"wr!";
	}

	public void setupPlayer(String player) {
		if(this.plugin.getServer().getPlayerExact(player) == null)
			return;
		Player p = this.plugin.getServer().getPlayerExact(player);
		String w = p.getWorld().getName();
		World world = wm.getWorld(w);
		this.setupPlayer(p, world);
	}
}