# Test plan: persistent mail state, phase P3 (network-ready storage)

What P3 promises, and what this plan checks:
- **SQLite still works exactly as before.** One server, nothing to configure.
- **MySQL/MariaDB is a config change.** Point Postal at a database and mail is tracked there, the same way.
- **The directory and server registry** fill in by themselves, and two servers can't share a server id unnoticed.
- **`/postal store`** shows whether the database is healthy and fast enough.

Allow about 45 minutes. You need a MySQL or MariaDB server you can create a database on (a local MariaDB is
fine: on Ubuntu, `sudo apt install mariadb-server`).

## 0. Setup

```
dev/test-server.sh reset
dev/test-server.sh start --seed
```

## 1. SQLite (the default)

1. `/postal store`.
- [ ] It says `Mail store: SQLite (postal.db)`, schema 4, server id `main`, and `Failures: 0`.
- [ ] It lists `Server main (this one): last seen … s ago`.
2. `/postal testletter Testville Testville Home`, then `/postal start`, and wait for it to be delivered
   (`/postal track last`).
- [ ] Delivered as in P1. `/postal store` shows calls going up, an average well under 1 ms, still no failures.
3. Wait a minute, then `/postal directory`.
- [ ] `main / central (Central)`, and each town with its number of addresses (Testville: 5).
4. `/postal directory main`.
- [ ] Each town's addresses are listed.

## 2. Switching to MySQL/MariaDB

1. Create a database and a user (in the `mariadb` or `mysql` client):
   ```sql
   CREATE DATABASE postal CHARACTER SET utf8mb4;
   CREATE USER 'postal'@'localhost' IDENTIFIED BY 'postal';
   GRANT ALL ON postal.* TO 'postal'@'localhost';
   ```
2. Stop the server. In `plugins/Postal/config.yml` set:
   ```yaml
   Storage:
     Type: 'mysql'
     Mysql:
       Host: 'localhost'
       Port: 3306
       Database: 'postal'
       User: 'postal'
       Password: 'postal'
   ```
3. Start the server.
- [ ] The log says `Mail store: MySQL (localhost:3306/postal), schema 4, server id 'main'`, and warns that
  `main` is fine for one server but a network needs its own ids.
- [ ] `/postal store` says MySQL, `Failures: 0`.
4. Repeat 1.2 to 1.4 (a test letter, `/postal directory`).
- [ ] Same results. The average call time is a little higher than SQLite's, still around a millisecond.
5. `/postal testparcel Testville Testville Home` and accept it as in P2 (`/postal accept last`).
- [ ] The Test Blade arrives intact: parcels work on MySQL too.
6. In the database client: `SELECT state, kind FROM postal.mail;` and `SELECT * FROM postal.directory_office;`
- [ ] The letter and the parcel are there, and the directory has your towns.

## 3. A crash on MySQL

1. `/postal testletter Testville Testville Farm`, wait for `OUT_FOR_DELIVERY`, then crash
   (`pkill -9 -f "paper.jar nogui"`) and `dev/test-server.sh start --no-build`.
- [ ] It's delivered once, as in P1's crash tests.

## 4. The database goes away

1. With the server running on MySQL, stop the database (`sudo systemctl stop mariadb`).
2. `/postal testletter Testville Testville Home`, and `/postal store`.
- [ ] The server keeps running: no freeze longer than a few seconds. `/postal store` shows failures and the
  last error.
3. Start the database again (`sudo systemctl start mariadb`).
- [ ] New mail is tracked again (`/postal testletter …`, then `/postal track last`).

## 5. Two servers, one id (optional; needs a second server)

1. Copy the server folder, give the copy another port (`server.properties`), keep `Server_id: 'main'` and the
   same database, and start both.
- [ ] Within two minutes both logs say another server is using `Network.Server_id 'main'`, and
  `/postal store` shows it.
2. Give the copy `Server_id: 'second'` and restart it.
- [ ] The warnings stop. `/postal store` lists both servers, and `/postal directory` shows both servers' towns.

## If something fails

Run `dev/test-server.sh report` and send me the bundle, with `/postal store` output and the step you were on.
