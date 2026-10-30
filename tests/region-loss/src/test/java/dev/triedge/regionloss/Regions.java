package dev.triedge.regionloss;

import java.sql.Connection;
import java.sql.SQLException;

interface Regions {

    Connection primary() throws SQLException;

    void losePrimaryRegion();

    Connection failOver() throws SQLException;
}
