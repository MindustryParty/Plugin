# Mindustry.Party Plugin

Build with `gradlew jar` / `./gradlew jar`

Output jar should be in `build/libs`.


### Installing

Simply place the output jar from the step above in your server's `config/mods` directory and restart the server.
Change the config values to match your database credentials in `config/config.properties`.
Then, run this SQL Query:
```
CREATE TABLE `players` (
  `uuid` varchar(100) NOT NULL,
  `playtime` int(50) NOT NULL DEFAULT '0',
  `playtime_modded` int(50) NOT NULL DEFAULT '0',
  `name` varchar(100) NOT NULL DEFAULT 'UNKNOWN PLAYER',
  `rank` varchar(50) NOT NULL DEFAULT 'default'
);
ALTER TABLE players ADD COLUMN id INT AUTO_INCREMENT PRIMARY KEY;
```
