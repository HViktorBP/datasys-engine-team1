CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'src/test/resources/trips.csv';
SELECT * FROM trips WHERE distance > 100;
