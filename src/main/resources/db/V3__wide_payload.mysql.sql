-- Schema 3 on MySQL/MariaDB: a BLOB holds at most 64 KB, too little for a chest of shulker boxes or books.
ALTER TABLE mail MODIFY payload LONGBLOB
