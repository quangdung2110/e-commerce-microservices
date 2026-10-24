-- Databases of the services on the PDP path; each service migrates its own schema with Flyway on start.
CREATE DATABASE product_service;
CREATE DATABASE inventory_service;
CREATE DATABASE review_service;
