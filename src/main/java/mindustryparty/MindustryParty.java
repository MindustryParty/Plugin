package mindustryparty;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;
import java.util.TimerTask;

import arc.Core;
import arc.Events;
import arc.util.CommandHandler;
import arc.util.Timer;
import mindustry.entities.type.Player;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Call;
import mindustry.plugin.Plugin;

public class MindustryParty extends Plugin {

	private ArrayList<Player> players = new ArrayList<Player>();
	private HashMap<Player, String> playerRanks = new HashMap<Player, String>();
	private Connection connection;

	public MindustryParty() {

		// Setup config file.
		Properties config = new Properties();
		// Does the config already exist?
		if (!new File(Core.settings.getDataDirectory() + "/config.configerties").exists()) {
			try {
				// Set the defaults
				config.getProperty("dbstring", "jdbc:mysql://localhost:3306/mindustryparty");
				config.getProperty("dbuser", "root");
				config.getProperty("dbpass", "root");

				// Save the defaults.
				config.store(new FileOutputStream(Core.settings.getDataDirectory() + "/config.configerties"), null);
			} catch (IOException ex) {
				System.out.println("Something went wrong while attempting to save the config:");
				ex.printStackTrace();
			}
		} else {
			try {
				// Load the config file.
				config.load(new FileInputStream(new File(Core.settings.getDataDirectory() + "/config.configerties")));
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

				if (rank.equals("donator")) {
					e.player.name = "[accent]DONATOR[] " + e.player.name;
				} else if (rank.equals("admin")) {
					e.player.name = "[green]ADMIN[] " + e.player.name;
				}
			} catch (Exception ex) {
				e.player.con.kick("[red]Something went wrong. Please try joining again.");
			}
		});

		// Listen to PlayerLeaveEvent.
		Events.on(PlayerLeave.class, e -> {
			// Remove player from internal player list.
			players.remove(e.player);
			// Remove rank from tracking.
			playerRanks.remove(e.player);
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
								PreparedStatement increasePlaytimeStatement = connection
										.prepareStatement("UPDATE players SET playtime = playtime+1 WHERE uuid=?");
								increasePlaytimeStatement.setString(1, p.uuid);
								increasePlaytimeStatement.execute();
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
				Call.sendMessage("[#7289DA]Did you know that we have a discord server? Join it here: [blue]https://discord.gg/5dWM7Bt [#7289DA]or go to [blue]https://mindustry.party/discord[#7289DA] instead.");
			}
		}, 60 * 5, 60 * 5);
	}

	// Register player-invoked commands.
	@Override
	public void registerClientCommands(CommandHandler handler) {
		// Playtimetop command.
		handler.<Player>register("playtimetop", "", "List the ten players that have the most play-time.",
				(args, player) -> {

					try {
						PreparedStatement playerTopStatement = connection
								.prepareStatement("SELECT * FROM players ORDER BY playtime DESC LIMIT 10;");
						ResultSet playerTop = playerTopStatement.executeQuery();
						String text = "Top 10 - Playtime";
						int pos = 1;
						while (playerTop.next()) {
							int pt = playerTop.getInt("playtime");
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

		// Stats command.
		// Commented out because still TODO.
		/*handler.<Player>register("redeem", "", "Redeem a donator code.", (args, player) -> {

			if (!playerRanks.get(player).equals("default")) {
				Call.onInfoMessage(player.con,
						"[red]You cannot redeem a code, because you already have a rank higher than default.");
			} else {

			}

		});*/

		// Stats command.
		handler.<Player>register("stats", "", "Show stats about yourself.", (args, player) -> {

			try {
				PreparedStatement playerInfoStatement = connection
						.prepareStatement("SELECT * FROM players WHERE uuid=?;");
				playerInfoStatement.setString(1, player.uuid);
				ResultSet playerInfo = playerInfoStatement.executeQuery();
				playerInfo.next();
				int pt = playerInfo.getInt("playtime");
				Call.onInfoMessage(player.con,
						"Your statistics:" + "\n" + "Playtime: [accent]" + pt + " minute" + (pt == 1 ? "" : "s"));
			} catch (SQLException e) {
				// Something went wrong, inform them.
				Call.onInfoMessage(player.con, "[red]Something went wrong. Please try again.");
			}

		});
	}
}