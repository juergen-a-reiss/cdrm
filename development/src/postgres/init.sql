CREATE DATABASE keycloak;

create database cdrm ;
create user cdrm ;

-- Now connect to database. E.c. \c cdrms
\c cdrm
grant ALL PRIVILEGES on ALL TABLES IN SCHEMA public TO cdrm ;
GRANT ALL PRIVILEGES ON SCHEMA public TO cdrm;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO cdrm;
alter user cdrm password 'cdrm' ;
