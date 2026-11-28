-- Dedicated DB only. Database p3_nfr must already exist and Flyway must have applied V11.
\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'p3_nfr' OR current_user <> 'p3qa' THEN
    RAISE EXCEPTION 'Expected dedicated p3_nfr database and p3qa role';
  END IF;
  IF EXISTS (SELECT 1 FROM swiftletcare.houses WHERE name IN ('P3 NFR', 'P3 NFR foreign')) THEN
    RAISE EXCEPTION 'Fixture already exists; reuse its IDs or start a new dedicated db';
  END IF;
END $$;

INSERT INTO swiftletcare.houses(name,active) VALUES ('P3 NFR',true) RETURNING id AS house \gset
INSERT INTO swiftletcare.houses(name,active) VALUES ('P3 NFR foreign',true) RETURNING id AS foreign_house \gset
INSERT INTO swiftletcare.zones(house_id,name,active) VALUES (:house,'Load',true) RETURNING id AS load_zone \gset
INSERT INTO swiftletcare.devices(zone_id,device_id,name,type,enabled)
SELECT :load_zone,'B3P3-QA-'||lpad(n::text,2,'0'),'NFR fixture','ESP32',true FROM generate_series(1,50) n;
INSERT INTO swiftletcare.devices(zone_id,device_id,name,type,enabled)
VALUES (:load_zone,'B3P3-NFR-CAMERA','NFR snapshot fixture','CAMERA',true) RETURNING id AS camera \gset

INSERT INTO swiftletcare.users(email,password_hash,role,active)
VALUES ('p3nfr-owner@qa.invalid','unused','OWNER',true) RETURNING id AS owner_id \gset
INSERT INTO swiftletcare.users(email,password_hash,role,active)
VALUES ('p3nfr-foreign@qa.invalid','unused','OWNER',true) RETURNING id AS foreign_id \gset
INSERT INTO swiftletcare.house_memberships(user_id,house_id,role,active,joined_at)
VALUES (:owner_id,:house,'OWNER',true,now()),(:foreign_id,:foreign_house,'OWNER',true,now());

WITH zones AS (
    INSERT INTO swiftletcare.zones(house_id,name,active)
    SELECT :house,'B3P3-ALERT-'||n,true FROM generate_series(1,5) n RETURNING id,name
), fixtures AS (
    INSERT INTO swiftletcare.devices(zone_id,device_id,name,type,enabled)
    SELECT id,name,'NFR alert fixture','ESP32',true FROM zones
), thresholds AS (
    INSERT INTO swiftletcare.zone_configs(zone_id,humidity_on,humidity_off)
    SELECT id,75,80 FROM zones
)
SELECT id AS alert_zone,name AS device_id FROM zones ORDER BY id;
COMMIT;
\echo house :house load_zone :load_zone camera :camera owner_id :owner_id foreign_id :foreign_id
