package mindustryparty;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;
import java.util.TimerTask;

import arc.Core;
import arc.Events;
import arc.util.CommandHandler;
import arc.util.Strings;
import arc.util.Timer;
import mindustry.content.Mechs;
import mindustry.content.UnitTypes;
import mindustry.entities.type.BaseUnit;
import mindustry.entities.type.Player;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Call;
import mindustry.plugin.Plugin;
import mindustry.type.Mech;

public class MindustryParty extends Plugin {

	private ArrayList<Player> players = new ArrayList<Player>();
	private HashMap<Player, String> playerRanks = new HashMap<Player, String>();
	private HashMap<Player, String> playerRanksL = new HashMap<Player, String>();
	private ArrayList<Player> hasRainbow = new ArrayList<Player>();
	private HashMap<Player, Integer> hueStatus = new HashMap<Player, Integer>();
	private HashMap<Player, String> originalName = new HashMap<Player, String>();
	private Connection connection;
	private Properties config;

	public MindustryParty() {
		// Setup config file.
		config = new Properties();
		// Does the config already exist?
		if (!new File(Core.settings.getDataDirectory() + "/config.properties").exists()) {
			try {
				// Set the defaults
				config.getProperty("dbstring",
						"jdbc:mysql://localhost:3306/mindustryparty?useUnicode=true&characterEncoding=utf8&validationQuery=true");
				config.getProperty("dbuser", "root");
				config.getProperty("dbpass", "root");
				config.getProperty("serverType", "vanilla");

				// Save the defaults.
				config.store(new FileOutputStream(Core.settings.getDataDirectory() + "/config.properties"), null);
			} catch (IOException ex) {
				System.out.println("Something went wrong while attempting to save the config:");
				ex.printStackTrace();
			}
		} else {
			try {
				// Load the config file.
				config.load(new FileInputStream(new File(Core.settings.getDataDirectory() + "/config.properties")));
			} catch (Exception e1) {
				System.out.println("Something went wrong while attempting to load the config:");
				e1.printStackTrace();
			}
		}

		// Connect to database.
		try {
			Class.forName("com.mysql.jdbc.Driver");
			connection = DriverManager.getConnection(config.getProperty("dbstring"), config.getProperty("dbuser"),
					config.getProperty("dbpass"));
		} catch (Exception e1) {
			e1.printStackTrace();
		}

		// Listen to PlayerJoinEvent.
		Events.on(PlayerJoin.class, e -> {
			// Add player to internal player list.
			players.add(e.player);
			try {
				// Count the number of rows of players with this uuid.
				PreparedStatement rowCountStatement = connection
						.prepareStatement("SELECT COUNT(*) AS rowcount FROM players WHERE uuid=?;");
				rowCountStatement.setString(1, e.player.uuid);

				ResultSet rowResult = rowCountStatement.executeQuery();
				rowResult.next();

				if (rowResult.getInt("rowcount") == 0) {
					// Player doesn't exist yet, add them to the database.
					PreparedStatement insertStatement = connection
							.prepareStatement("INSERT INTO players (uuid) VALUES (?)");
					insertStatement.setString(1, e.player.uuid);
					insertStatement.execute();
				}

				// Update their last known name.
				PreparedStatement nameStatement = connection.prepareStatement("UPDATE players SET name=? WHERE uuid=?");
				nameStatement.setString(1, e.player.name);
				nameStatement.setString(2, e.player.uuid);
				nameStatement.execute();

				// Fetch player info.
				PreparedStatement playerInfoStatement = connection
						.prepareStatement("SELECT * FROM players WHERE uuid=?;");
				playerInfoStatement.setString(1, e.player.uuid);
				ResultSet playerInfo = playerInfoStatement.executeQuery();
				playerInfo.next();
				String rank = playerInfo.getString("rank");

				playerRanks.put(e.player, rank);

				String rankD = "";

				e.player.isAdmin = false;

				if (rank.equals("active")) {
					rankD = "[orange]ACTIVE PLAYER[] ";
				} else if (rank.equals("donator")) {
					rankD = "[accent]DONATOR[] ";
				} else if (rank.equals("moderator")) {
					rankD = "[green]MODERATOR[] ";
					e.player.isAdmin = true;
				} else if (rank.equals("admin")) {
					rankD = "[red]ADMIN[] ";
					e.player.isAdmin = true;
				} else {
					e.player.name = Strings.stripColors(e.player.name);
				}

				originalName.put(e.player, e.player.name);

				e.player.name = rankD + e.player.name;
				playerRanksL.put(e.player, rankD);
				Call.onInfoToast("[green]+[] " + e.player.name, 5);
				Call.sendMessage(e.player.name + " [accent]joined.[]");
			} catch (Exception ex) {
				ex.printStackTrace();
				e.player.con.close();
			}
		});

		// Listen to PlayerLeaveEvent.
		Events.on(PlayerLeave.class, e -> {
			Call.onInfoToast("[red]-[] " + e.player.name, 5);
			Call.sendMessage(e.player.name + " [accent]left.[]");
			// Remove player from internal player list.
			players.remove(e.player);
			// Remove rank from tracking.
			playerRanks.remove(e.player);
			if (hasRainbow.contains(e.player)) {
				hasRainbow.remove(e.player);
				hueStatus.remove(e.player);
			}
		});

		// Every tick
		Events.on(Trigger.update, () -> {
			for (Player r : hasRainbow) {
				Integer hue = hueStatus.get(r);
				hue++;
				if (hue > 360) {
					hue = 0;
				}
				hueStatus.put(r, hue);
				String hexCode = Integer.toHexString(Color.getHSBColor(hue / 360f, 1f, 1f).getRGB()).substring(2);
				r.name = playerRanksL.get(r) + "[#" + hexCode + "]" + Strings.stripColors(originalName.get(r));
			}
		});

		// Playtime-tracking task.
		Timer.schedule(new TimerTask() {
			@Override
			public void run() {
				for (Player p : players) {
					if (!p.con.isConnected()) {
						players.remove(p);
					} else {
						try {
							// Count the number of rows of players with this uuid.
							PreparedStatement rowCountStatement = connection
									.prepareStatement("SELECT COUNT(*) AS rowcount FROM players WHERE uuid=?;");
							rowCountStatement.setString(1, p.uuid);

							ResultSet rowResult = rowCountStatement.executeQuery();
							rowResult.next();

							if (rowResult.getInt("rowcount") == 0) {
								// Player doesn't exist yet, add them to the database.
								PreparedStatement insertStatement = connection
										.prepareStatement("INSERT INTO players (uuid) VALUES (?)");
								insertStatement.setString(1, p.uuid);
								insertStatement.execute();
							} else {
								if (config.getProperty("serverType").equals("modded")) {
									PreparedStatement increasePlaytimeStatement = connection.prepareStatement(
											"UPDATE players SET playtime_modded = playtime_modded+1 WHERE uuid=?");
									increasePlaytimeStatement.setString(1, p.uuid);
									increasePlaytimeStatement.execute();
								} else {
									PreparedStatement increasePlaytimeStatement = connection
											.prepareStatement("UPDATE players SET playtime = playtime+1 WHERE uuid=?");
									increasePlaytimeStatement.setString(1, p.uuid);
									increasePlaytimeStatement.execute();
								}
							}
						} catch (SQLException e) {
							System.out.println("Something went wrong during the playtime increase:");
							e.printStackTrace();
						}
					}
				}
			}
		}, 60, 60);

		// Discord announcement.
		Timer.schedule(new TimerTask() {
			@Override
			public void run() {
				Call.sendMessage(
						"[#7289DA]Did you know that we have a discord server? Join it here: [purple]https://mindustry.party/discord[#7289DA].");
			}
		}, 15 * 60, 15 * 60);

		// Keep connection active.
		Timer.schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					connection.isValid(1000);
				} catch (SQLException e) {
					System.out.println("WARNING Connection is not valid!");
				}
			}
		}, 30, 30);
	}

	// Register player-invoked commands.
	@Override
	public void registerClientCommands(CommandHandler handler) {
		// Playtimetop command.
		handler.<Player>register("playtimetop", "", "List the ten players that have the most play-time.",
				(args, player) -> {

					try {
						PreparedStatement playerTopStatement;
						String server = "";
						if (config.getProperty("serverType").equals("modded")) {
							server = "Modded";
							playerTopStatement = connection
									.prepareStatement("SELECT * FROM players ORDER BY playtime_modded DESC LIMIT 10;");
						} else {
							server = "Vanilla";
							playerTopStatement = connection
									.prepareStatement("SELECT * FROM players ORDER BY playtime DESC LIMIT 10;");
						}
						ResultSet playerTop = playerTopStatement.executeQuery();
						String text = "Top 10 - Playtime (" + server + " Server)";
						int pos = 1;
						while (playerTop.next()) {
							int pt = (server == "Vanilla" ? playerTop.getInt("playtime")
									: playerTop.getInt("playtime_modded"));
							text += "\n[accent]#" + pos + " -[white] " + playerTop.getString("name") + " [accent]- "
									+ pt + " minute" + (pt == 1 ? "" : "s");
							pos++;
						}
						Call.onInfoMessage(player.con, text);
					} catch (SQLException e) {
						// Something went wrong, inform them.
						Call.onInfoMessage(player.con, "[red]Something went wrong. Please try again.");
					}

				});

		handler.<Player>register("rainbow", "", "[Donator-only] Toggle the rainbow-name effect.", (args, player) -> {

			try {
				PreparedStatement playerInfoStatement = connection
						.prepareStatement("SELECT * FROM players WHERE uuid=?;");
				playerInfoStatement.setString(1, player.uuid);
				ResultSet playerInfo = playerInfoStatement.executeQuery();
				playerInfo.next();
				String rank = playerInfo.getString("rank");

				if (rank.equals("default")) {
					Call.onInfoMessage(player.con, "[red]You need [accent]DONATOR [red]rank to do that.");
				} else {
					if (hasRainbow.contains(player)) {
						hasRainbow.remove(player);
						hueStatus.remove(player);
						player.sendMessage("[accent]Disabled rainbow-name effect.");
						player.name = playerRanksL.get(player) + originalName.get(player);
					} else {
						hueStatus.put(player, 0);
						hasRainbow.add(player);
						player.sendMessage("[accent]Enabled rainbow-name effect.");
					}
				}
			} catch (SQLException e) {
				// Something went wrong, inform them.
				Call.onInfoMessage(player.con, "[red]Something went wrong. Please try again.");
			}

		});

		handler.<Player>register("transform", "[mech]", "[Donator-only] Transform yourself into a mech.",
				(args, player) -> {

					try {
						PreparedStatement playerInfoStatement = connection
								.prepareStatement("SELECT * FROM players WHERE uuid=?;");
						playerInfoStatement.setString(1, player.uuid);
						ResultSet playerInfo = playerInfoStatement.executeQuery();
						playerInfo.next();
						String rank = playerInfo.getString("rank");

						if (rank.equals("default")) {
							Call.onInfoMessage(player.con, "[red]You need [accent]DONATOR [red]rank to do that.");
						} else {
							Mech mechWanted = Mechs.dart;
							if (args.length == 1) {
								try {
									Field field = Mechs.class.getDeclaredField(args[0].toLowerCase());
									mechWanted = (Mech) field.get(null);
								} catch (NoSuchFieldException | IllegalAccessException ex) {
									player.sendMessage(
											"[red]That is not a valid mech. You have been transformed into a dart mech.");
									player.sendMessage(
											"[red]Valid mechs: Alpha, Dart (Default), Delta, Glaive, Javelin, Omega, Tau, Trident.");
								}
							} else {
								player.sendMessage(
										"[red]Available mechs: Alpha, Dart (Default), Delta, Glaive, Javelin, Omega, Tau, Trident.");
							}
							player.mech = mechWanted;
							player.sendMessage("[green]You have been transformed into a [][accent]" + mechWanted.name
									+ "[][green] mech.");
						}
					} catch (SQLException e) {
						// Something went wrong, inform them.
						Call.onInfoMessage(player.con, "[red]Something went wrong. Please try again.");
					}

				});

		handler.<Player>register("pet", "[petname]", "[Donator-only] Spawn a pet.", (args, player) -> {

			try {
				PreparedStatement playerInfoStatement = connection
						.prepareStatement("SELECT * FROM players WHERE uuid=?;");
				playerInfoStatement.setString(1, player.uuid);
				ResultSet playerInfo = playerInfoStatement.executeQuery();
				playerInfo.next();
				String rank = playerInfo.getString("rank");

				if (rank.equals("default")) {
					Call.onInfoMessage(player.con, "[red]You need [accent]DONATOR [red]rank to do that.");
				} else {
					String available = "[accent]Available pets: draug, phantom";
					if (args.length == 0) {
						player.sendMessage(available);
					} else if (args[0].equalsIgnoreCase("draug")) {
						BaseUnit baseUnit = UnitTypes.draug.create(player.getTeam());
						baseUnit.set(player.getX(), player.getY());
						baseUnit.add();
						Call.sendMessage(player.name + " [accent]spawned a draug pet!");
					} else if (args[0].equalsIgnoreCase("phantom")) {
						BaseUnit baseUnit = UnitTypes.phantom.create(player.getTeam());
						baseUnit.set(player.getX(), player.getY());
						baseUnit.add();
						Call.sendMessage(player.name + " [accent]spawned a phantom pet!");
					} else {
						player.sendMessage(available);
					}
				}
			} catch (SQLException e) {
				// Something went wrong, inform them.
				Call.onInfoMessage(player.con, "[red]Something went wrong. Please try again.");
			}

		});

		// Redeem command.
		// Commented out because still TODO.
		/*
		 * handler.<Player>register("redeem", "", "Redeem a donator code.", (args,
		 * player) -> {
		 * 
		 * if (!playerRanks.get(player).equals("default")) {
		 * Call.onInfoMessage(player.con,
		 * "[red]You cannot redeem a code, because you already have a rank higher than default."
		 * ); } else {
		 * 
		 * }
		 * 
		 * });
		 */

		// Staffchat command.
		handler.<Player>register("sc", "<message...>", "[Staff-only] Staff-chat.", (args, player) -> {

			try {
				PreparedStatement playerInfoStatement = connection
						.prepareStatement("SELECT * FROM players WHERE uuid=?;");
				playerInfoStatement.setString(1, player.uuid);
				ResultSet playerInfo = playerInfoStatement.executeQuery();
				playerInfo.next();
				String rank = playerInfo.getString("rank");

				if (!rank.equals("moderator") && !rank.equals("admin")) {
					Call.onInfoMessage(player.con, "[red]You need to be [accent]STAFF [red]to do that.");
				} else {
					if (args.length == 0) {
						player.sendMessage("[red]You need to specify a message.");
					} else {
						for (Player p : players) {
							String rankP = playerRanks.get(p);
							if (rankP.equals("moderator") || rankP.contentEquals("admin")) {
								p.sendMessage("[#3bcfe2][Staff-Chat] " + player.name + "[accent]: [white]"
										+ String.join(" ", args));
							}
						}
					}
				}
			} catch (SQLException e) {
				// Something went wrong, inform them.
				Call.onInfoMessage(player.con, "[red]Something went wrong. Please try again.");
			}

		});

		// Setrank command.
		handler.<Player>register("setrank", "<player> <rank>", "[Admin-only] Set a player rank.", (args, player) -> {

			try {
				PreparedStatement playerInfoStatement = connection
						.prepareStatement("SELECT * FROM players WHERE uuid=?;");
				playerInfoStatement.setString(1, player.uuid);
				ResultSet playerInfo = playerInfoStatement.executeQuery();
				playerInfo.next();
				String rank = playerInfo.getString("rank");

				if (!rank.equals("admin")) {
					Call.onInfoMessage(player.con, "[red]You need to be [accent]ADMIN [red]to do that.");
				} else {
					String rankToSet = args[1].toLowerCase();
					String[] valid = { "default", "active", "donator", "moderator" };
					if (!Arrays.stream(valid).anyMatch(t -> t.equals(rankToSet))) {
						player.sendMessage("[red]Valid ranks: " + Strings.join(", ", valid) + ".");
						return;
					}

					boolean found = false;
					for (Player p : players) {
						if (Strings.stripColors(originalName.get(p)).equalsIgnoreCase(args[0])) {
							found = true;
						}
					}
					if (!found) {
						player.sendMessage("[red]That player is not online!");
					} else {
						for (Player p : players) {
							if (Strings.stripColors(originalName.get(p)).equalsIgnoreCase(args[0])) {
								PreparedStatement playerInfoStatement2 = connection
										.prepareStatement("SELECT * FROM players WHERE uuid=?;");
								playerInfoStatement2.setString(1, p.uuid);
								ResultSet playerInfo2 = playerInfoStatement2.executeQuery();
								playerInfo2.next();
								String rankP = playerInfo2.getString("rank");
								if (rankP.equals("admin")) {
									player.sendMessage("[red]That player is an admin!");
									return;
								}
								PreparedStatement setRankStatement = connection
										.prepareStatement("UPDATE players SET rank = '" + rankToSet + "' WHERE uuid=?");
								setRankStatement.setString(1, p.uuid);
								setRankStatement.execute();
								player.sendMessage("[green]Gave " + p.name + " [green]" + rankToSet + "!");
								p.sendMessage("[green]You have a new rank (" + rankToSet + ")! Re-log to apply it.");
								return;
							}
						}
					}
				}
			} catch (SQLException e) {
				// Something went wrong, inform them.
				Call.onInfoMessage(player.con, "[red]Something went wrong. Please try again.");
			}

		});

		// Stats command.
		handler.<Player>register("stats", "", "Show stats about yourself.", (args, player) -> {

			try {
				PreparedStatement playerInfoStatement = connection
						.prepareStatement("SELECT * FROM players WHERE uuid=?;");
				playerInfoStatement.setString(1, player.uuid);
				ResultSet playerInfo = playerInfoStatement.executeQuery();
				playerInfo.next();
				int pt = playerInfo.getInt("playtime");
				int pt_m = playerInfo.getInt("playtime_modded");
				int pt_t = pt + pt_m;
				Call.onInfoMessage(player.con,
						"Your statistics:" + "\n" + "Playtime (Vanilla) : [accent]" + pt + " minute"
								+ (pt == 1 ? "" : "s") + "[]\n" + "Playtime (Modded) : [accent]" + pt_m + " minute"
								+ (pt_m == 1 ? "" : "s") + "[]\n" + "Playtime (Total) : [accent]" + pt_t + " minute"
								+ (pt_t == 1 ? "" : "s") + "[]");
			} catch (SQLException e) {
				// Something went wrong, inform them.
				Call.onInfoMessage(player.con, "[red]Something went wrong. Please try again.");
			}

		});
	}
}